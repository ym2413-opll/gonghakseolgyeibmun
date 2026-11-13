package com.gonghak.service

import com.gonghak.config.DatabaseFactory.dbQuery
import com.gonghak.dto.EmailLogDto
import com.gonghak.models.EmailLogsTable
import com.gonghak.models.QuestionCategory
import com.gonghak.worker.SmtpJobPayload
import com.gonghak.worker.SmtpTransport
import com.gonghak.worker.SubprocessRunner
import kotlinx.coroutines.Job
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

data class PendingEmailJob(
    val logId: UUID,
    val recipientEmail: String,
    val subject: String,
    val body: String,
    val replyTo: String? = null,
    val messageId: String? = null
)

class EmailService(
    private val workerManager: BackgroundWorkerManager = BackgroundWorkerManager()
) {
    private val logger = LoggerFactory.getLogger(EmailService::class.java)

    /**
     * Records an outbox row with status QUEUED in the current transaction (or a new one)
     * and returns a PendingEmailJob to be dispatched asynchronously after transaction commit.
     */
    fun enqueueQuestionEmail(
        recipientEmail: String,
        professorId: UUID,
        professorName: String,
        courseName: String,
        questionId: UUID,
        questionTitle: String,
        questionContent: String,
        category: QuestionCategory,
        authorDisplay: String,
        isCoProfessor: Boolean = false
    ): PendingEmailJob? {
        val cleanEmail = recipientEmail.trim()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) return null

        val categoryLabel = when (category) {
            QuestionCategory.GRADE -> "성적"
            QuestionCategory.ASSIGNMENT -> "과제"
            QuestionCategory.LECTURE -> "강의"
        }

        val replyRoute = issueEmailReplyRouteInTx(
            replyDomain = configuredReplyDomain,
            replyPrefix = configuredReplyPrefix,
            recipientEmail = cleanEmail,
            professorId = professorId,
            questionId = questionId
        )

        val prefix = if (isCoProfessor) "[공동강의 질문 알림]" else "[새 질문 알림]"
        val subject = "$prefix [$courseName - $categoryLabel] $questionTitle"
        val body = buildString {
            if (replyRoute != null) {
                appendLine(EMAIL_REPLY_ABOVE_MARKER)
                appendLine()
            }
            appendLine("안녕하십니까, ${professorName} 교수님.")
            appendLine("광운대학교 학업 Q&A 포럼에 새로운 질문이 등록되어 안내 메일을 발송합니다.")
            appendLine("--------------------------------------------------")
            appendLine("• 과목명: $courseName")
            appendLine("• 카테고리: $categoryLabel")
            appendLine("• 작성자: $authorDisplay")
            appendLine("• 요약 제목: $questionTitle")
            appendLine("--------------------------------------------------")
            appendLine("[질문 본문]")
            appendLine(questionContent)
            appendLine("--------------------------------------------------")
            if (replyRoute != null) {
                appendLine("이 메일에 바로 답장을 보내시거나, 포털에 로그인하여 스레드에서 직접 답변을 작성하실 수 있습니다.")
            } else {
                appendLine("포털에 로그인하여 스레드에서 직접 답변을 작성하실 수 있습니다.")
            }
        }

        val logId = UUID.randomUUID()
        val insertOutbox: () -> Unit = {
            EmailLogsTable.insert {
                it[id] = logId
                it[EmailLogsTable.recipientEmail] = cleanEmail
                it[EmailLogsTable.professorId] = professorId
                it[EmailLogsTable.questionId] = questionId
                it[EmailLogsTable.subject] = subject.take(250)
                it[EmailLogsTable.body] = body
                it[EmailLogsTable.status] = "QUEUED"
                it[EmailLogsTable.createdAt] = Instant.now()
            }
        }

        if (TransactionManager.currentOrNull() != null) {
            insertOutbox()
        } else {
            transaction { insertOutbox() }
        }

        return PendingEmailJob(
            logId = logId,
            recipientEmail = cleanEmail,
            subject = subject,
            body = body,
            replyTo = replyRoute?.replyToAddress,
            messageId = replyRoute?.messageId
        )
    }

    /**
     * Dispatches a queued email job on the dedicated email worker thread pool
     * (or isolated SMTP OS subprocess when SMTP_SUBPROCESS_ENABLED=true).
     */
    @Volatile
    private var configuredReplyDomain: String? = normalizeReplyDomain(com.gonghak.config.EnvConfig.get("EMAIL_REPLY_DOMAIN"))

    @Volatile
    private var configuredReplyPrefix: String = normalizeReplyPrefix(com.gonghak.config.EnvConfig.get("EMAIL_REPLY_PREFIX"))

    fun configureReplyRouting(domain: String?, prefix: String? = null) {
        configuredReplyDomain = normalizeReplyDomain(domain)
        if (!prefix.isNullOrBlank()) {
            configuredReplyPrefix = normalizeReplyPrefix(prefix)
        }
    }

    private fun resolveSmtpHost(explicitHost: String?, smtpUser: String?, smtpPass: String?): String? {
        if (!explicitHost.isNullOrBlank()) return explicitHost
        if (smtpUser.isNullOrBlank() || smtpPass.isNullOrBlank()) return null
        val domain = smtpUser.substringAfter("@", "").trim().lowercase()
        return when (domain) {
            "gmail.com", "googlemail.com" -> "smtp.gmail.com"
            "naver.com" -> "smtp.naver.com"
            "daum.net", "hanmail.net" -> "smtp.daum.net"
            "kakao.com" -> "smtp.kakao.com"
            "outlook.com", "hotmail.com", "live.com" -> "smtp.office365.com"
            else -> null
        }
    }

    fun dispatchEmailAsync(job: PendingEmailJob): Job {
        return workerManager.launchEmailTask("email-${job.logId}") {
            val smtpUser = com.gonghak.config.EnvConfig.get("SMTP_USER")
            val smtpPass = com.gonghak.config.EnvConfig.get("SMTP_PASS")
            val smtpHost = resolveSmtpHost(com.gonghak.config.EnvConfig.get("SMTP_HOST"), smtpUser, smtpPass)
            val smtpPort = com.gonghak.config.EnvConfig.get("SMTP_PORT")?.toIntOrNull() ?: 587
            val smtpFrom = com.gonghak.config.EnvConfig.get("SMTP_FROM")
                ?: smtpUser
                ?: "noreply@kw.ac.kr"
            val useSubprocess = com.gonghak.config.EnvConfig.get("SMTP_SUBPROCESS_ENABLED")
                ?.equals("true", ignoreCase = true) == true

            var status = "SENT"
            if (!smtpHost.isNullOrBlank()) {
                val payload = SmtpJobPayload(
                    host = smtpHost,
                    port = smtpPort,
                    user = smtpUser,
                    pass = smtpPass,
                    from = smtpFrom,
                    to = job.recipientEmail,
                    subject = job.subject,
                    body = job.body,
                    replyTo = job.replyTo,
                    messageId = job.messageId
                )
                try {
                    if (useSubprocess) {
                        val subResult = SubprocessRunner.runSmtpSubprocess(payload)
                        if (!subResult.success) {
                            throw RuntimeException(subResult.message ?: "SMTP subprocess failed")
                        }
                    } else {
                        SmtpTransport.dispatchSmtpMail(payload)
                    }
                    status = "SENT_SMTP"
                    logger.info("[EmailWorker:${Thread.currentThread().name}] Sent SMTP email to ${job.recipientEmail} (subject: ${job.subject})")
                } catch (e: Exception) {
                    logger.warn(
                        "[EmailWorker:${Thread.currentThread().name}] SMTP delivery failed for ${job.recipientEmail} via $smtpHost:$smtpPort (${e.message}), recorded in local outbox log.",
                        e
                    )
                    status = "SENT_LOGGED"
                }
            } else {
                logger.info("[EmailWorker:${Thread.currentThread().name}] Dispatched question notification email to ${job.recipientEmail} -> ${job.subject}")
            }

            if (!workerManager.isShuttingDown) {
                runCatching {
                    dbQuery(workerManager.emailDispatcher) {
                        EmailLogsTable.update({ EmailLogsTable.id eq job.logId }) {
                            it[EmailLogsTable.status] = status
                        }
                    }
                }
            }
        }
    }

    /**
     * Convenience wrapper that enqueues outbox log and dispatches asynchronously.
     */
    fun sendQuestionEmailInternal(
        recipientEmail: String,
        professorId: UUID,
        professorName: String,
        courseName: String,
        questionId: UUID,
        questionTitle: String,
        questionContent: String,
        category: QuestionCategory,
        authorDisplay: String,
        isCoProfessor: Boolean = false
    ) {
        val job = enqueueQuestionEmail(
            recipientEmail = recipientEmail,
            professorId = professorId,
            professorName = professorName,
            courseName = courseName,
            questionId = questionId,
            questionTitle = questionTitle,
            questionContent = questionContent,
            category = category,
            authorDisplay = authorDisplay,
            isCoProfessor = isCoProfessor
        ) ?: return
        dispatchEmailAsync(job)
    }

    suspend fun getEmailLogs(professorId: UUID? = null, isAdmin: Boolean = false): List<EmailLogDto> = dbQuery {
        val query = if (isAdmin || professorId == null) {
            EmailLogsTable.selectAll()
        } else {
            EmailLogsTable.select { EmailLogsTable.professorId eq professorId }
        }

        query.orderBy(EmailLogsTable.createdAt to SortOrder.DESC)
            .limit(50)
            .map { row ->
                EmailLogDto(
                    id = row[EmailLogsTable.id].value.toString(),
                    recipientEmail = row[EmailLogsTable.recipientEmail],
                    professorId = row[EmailLogsTable.professorId]?.toString(),
                    questionId = row[EmailLogsTable.questionId]?.toString(),
                    subject = row[EmailLogsTable.subject],
                    body = row[EmailLogsTable.body],
                    status = row[EmailLogsTable.status],
                    createdAt = row[EmailLogsTable.createdAt].toString()
                )
            }
    }
}
