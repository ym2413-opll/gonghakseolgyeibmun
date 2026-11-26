package com.gonghak.service

import com.gonghak.config.DatabaseFactory.dbQuery
import com.gonghak.config.EnvConfig
import com.gonghak.dto.EmailLogDto
import com.gonghak.models.AnswersTable
import com.gonghak.models.AttachmentsTable
import com.gonghak.models.CourseProfessorsTable
import com.gonghak.models.CoursesTable
import com.gonghak.models.EmailLogsTable
import com.gonghak.models.QuestionCategory
import com.gonghak.models.QuestionsTable
import com.gonghak.models.SystemSettingsTable
import com.gonghak.models.UserRole
import com.gonghak.models.UsersTable
import com.gonghak.util.SemesterUtils
import com.gonghak.worker.SmtpInlineImage
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
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.Base64
import java.util.UUID

private const val MAX_EMAIL_INLINE_IMAGES = 8
private const val MAX_EMAIL_SINGLE_IMAGE_BYTES = 2 * 1024 * 1024L
private const val MAX_EMAIL_TOTAL_IMAGE_BYTES = 4 * 1024 * 1024L

data class PendingEmailJob(
    val logId: UUID,
    val recipientEmail: String,
    val subject: String,
    val body: String,
    val replyTo: String? = null,
    val messageId: String? = null,
    val htmlBody: String? = null,
    val inlineImages: List<SmtpInlineImage> = emptyList()
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
        val safeCourseName = PasswordSecurity.sanitizeHeaderValue(courseName)
        val safeTitle = PasswordSecurity.sanitizeHeaderValue(questionTitle)
        val subject = "$prefix [$safeCourseName - $categoryLabel] $safeTitle"
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
                appendLine("이 메일에 답장을 보내거나 포털에서 직접 답변을 작성할 수 있습니다.")
            } else {
                appendLine("포털에 로그인해 직접 답변을 작성할 수 있습니다.")
            }
        }

        var htmlBody: String? = null
        val inlineImages = mutableListOf<SmtpInlineImage>()
        val logId = UUID.randomUUID()
        val insertOutboxAndBuildHtml: () -> Unit = {
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

            runCatching {
                val (renderedHtml, collectedImages) = buildThreadHtmlInTx(
                    questionId = questionId,
                    fallbackCourseName = courseName,
                    fallbackCategory = category,
                    fallbackProfessorName = professorName,
                    fallbackTitle = questionTitle,
                    fallbackContent = questionContent,
                    fallbackAuthorDisplay = authorDisplay,
                    isCoProfessor = isCoProfessor,
                    hasReplyRoute = replyRoute != null
                )
                htmlBody = renderedHtml
                inlineImages.addAll(collectedImages)
            }.onFailure { ex ->
                logger.warn("[EmailService] Failed to build rich HTML thread for question $questionId: ${ex.message}")
            }
        }

        if (TransactionManager.currentOrNull() != null) {
            insertOutboxAndBuildHtml()
        } else {
            transaction { insertOutboxAndBuildHtml() }
        }

        return PendingEmailJob(
            logId = logId,
            recipientEmail = cleanEmail,
            subject = subject,
            body = body,
            replyTo = replyRoute?.replyToAddress,
            messageId = replyRoute?.messageId,
            htmlBody = htmlBody,
            inlineImages = inlineImages
        )
    }

    private fun buildThreadHtmlInTx(
        questionId: UUID,
        fallbackCourseName: String,
        fallbackCategory: QuestionCategory,
        fallbackProfessorName: String,
        fallbackTitle: String,
        fallbackContent: String,
        fallbackAuthorDisplay: String,
        isCoProfessor: Boolean,
        hasReplyRoute: Boolean
    ): Pair<String, List<SmtpInlineImage>> {
        val uploadDirPath = System.getProperty("gonghak.uploadDir")
            ?: EnvConfig.get("UPLOAD_DIR")
            ?: "uploads/attachments"
        val uploadRoot = runCatching { Paths.get(uploadDirPath).toRealPath() }.getOrNull()

        val inlineImages = mutableListOf<SmtpInlineImage>()
        var totalImgBytes = 0L

        fun toInlineAttachment(row: org.jetbrains.exposed.sql.ResultRow): EmailInlineAttachment {
            val attId = row[AttachmentsTable.id].value.toString()
            val originalName = row[AttachmentsTable.originalName]
            val savedName = row[AttachmentsTable.savedName]
            val normalizedMime = row[AttachmentsTable.mimeType].substringBefore(';').trim().lowercase()
            val fileSize = row[AttachmentsTable.fileSize]
            val isSafeRasterImg = normalizedMime in EmailThreadHtmlRenderer.ALLOWED_INLINE_IMAGE_MIME_TYPES
            var cid: String? = null
            if (isSafeRasterImg && uploadRoot != null &&
                inlineImages.size < MAX_EMAIL_INLINE_IMAGES &&
                fileSize in 1..MAX_EMAIL_SINGLE_IMAGE_BYTES
            ) {
                val rawCandidate = uploadRoot.resolve(savedName).normalize()
                if (rawCandidate.startsWith(uploadRoot) && !Files.isSymbolicLink(rawCandidate)) {
                    val realFile = runCatching { rawCandidate.toRealPath() }.getOrNull()
                    if (realFile != null && realFile.startsWith(uploadRoot) && Files.isRegularFile(realFile)) {
                        val diskLen = runCatching { Files.size(realFile) }.getOrDefault(-1L)
                        if (diskLen in 1..MAX_EMAIL_SINGLE_IMAGE_BYTES &&
                            totalImgBytes + diskLen <= MAX_EMAIL_TOTAL_IMAGE_BYTES
                        ) {
                            val bytes = runCatching { Files.readAllBytes(realFile) }.getOrNull()
                            if (bytes != null && bytes.isNotEmpty() &&
                                bytes.size <= MAX_EMAIL_SINGLE_IMAGE_BYTES &&
                                totalImgBytes + bytes.size <= MAX_EMAIL_TOTAL_IMAGE_BYTES
                            ) {
                                val candidateCid = "att-${attId.replace("-", "")}@cistus"
                                inlineImages.add(
                                    SmtpInlineImage(
                                        cid = candidateCid,
                                        fileName = originalName,
                                        contentType = normalizedMime,
                                        base64Data = Base64.getEncoder().encodeToString(bytes)
                                    )
                                )
                                totalImgBytes += bytes.size
                                cid = candidateCid
                            }
                        }
                    }
                }
            }
            return EmailInlineAttachment(
                id = attId,
                originalName = originalName,
                contentType = normalizedMime,
                fileSize = fileSize,
                cid = cid
            )
        }

        val qRow = QuestionsTable.select { QuestionsTable.id eq questionId }.singleOrNull()
        val allAttachments = AttachmentsTable.select { AttachmentsTable.questionId eq questionId }
            .orderBy(AttachmentsTable.createdAt to SortOrder.ASC)
            .toList()
        val qAttachmentRows = allAttachments.filter { it[AttachmentsTable.answerId] == null }
        val ansAttachmentRowsMap = allAttachments
            .filter { it[AttachmentsTable.answerId] != null }
            .groupBy { it[AttachmentsTable.answerId]!! }

        val posts = mutableListOf<EmailThreadPost>()

        if (qRow != null) {
            val qStudentId = qRow[QuestionsTable.studentId]
            val qProfId = qRow[QuestionsTable.assignedProfessorId]
            val isAnon = qRow[QuestionsTable.isAnonymous]
            val isQuestionHidden = qRow[QuestionsTable.isHidden]
            val isQuestionDeleted = qRow[QuestionsTable.isDeletedByAuthor]
            val isOpMasked = isQuestionHidden || isQuestionDeleted
            val studentRow = UsersTable.select { UsersTable.id eq qStudentId }.singleOrNull()
            val profRow = UsersTable.select { UsersTable.id eq qProfId }.singleOrNull()
            val isAdminOp = studentRow?.get(UsersTable.isAdmin) == true ||
                studentRow?.get(UsersTable.role) == UserRole.ADMIN.name
            val effectiveIsAdminOp = isAdminOp && !isAnon
            val opDisplay = when {
                isAnon -> "익명 수강생"
                isAdminOp -> studentRow?.get(UsersTable.nickname)?.takeIf { it.isNotBlank() }
                    ?: studentRow?.get(UsersTable.name)?.takeIf { it.isNotBlank() }
                    ?: fallbackAuthorDisplay
                !studentRow?.get(UsersTable.displayTag).isNullOrBlank() -> studentRow!![UsersTable.displayTag]
                !studentRow?.get(UsersTable.name).isNullOrBlank() -> studentRow!![UsersTable.name]
                else -> fallbackAuthorDisplay
            }
            val opContent = when {
                isQuestionDeleted -> "작성자에 의해 삭제된 질문입니다."
                isQuestionHidden -> "관리자에 의해 숨겨진 글입니다."
                else -> qRow[QuestionsTable.refinedContent].ifBlank { qRow[QuestionsTable.rawContent] }
            }
            val qAttachments = if (isOpMasked) emptyList() else qAttachmentRows.map(::toInlineAttachment)
            posts.add(
                EmailThreadPost(
                    sequenceNumber = 1,
                    authorDisplay = opDisplay,
                    roleLabel = if (effectiveIsAdminOp) "관리자" else "질문 작성자",
                    roleKind = if (effectiveIsAdminOp) "admin" else "op",
                    isAnonymous = isAnon,
                    isEdited = false,
                    isHidden = isOpMasked,
                    createdAt = qRow[QuestionsTable.createdAt],
                    content = opContent,
                    attachments = qAttachments
                )
            )

            val answerRows = AnswersTable.select { AnswersTable.questionId eq questionId }
                .orderBy(AnswersTable.sequenceNumber to SortOrder.ASC, AnswersTable.createdAt to SortOrder.ASC)
                .toList()
            for (aRow in answerRows) {
                val aId = aRow[AnswersTable.id].value
                val aAuthorId = aRow[AnswersTable.professorId]
                val aUser = UsersTable.select { UsersTable.id eq aAuthorId }.singleOrNull()
                val aRoleStr = aUser?.get(UsersTable.role) ?: UserRole.PROFESSOR.name
                val aIsAdmin = aUser?.get(UsersTable.isAdmin) == true || aRoleStr == UserRole.ADMIN.name
                val aIsStudent = aRoleStr == UserRole.STUDENT.name && !aIsAdmin
                val aIsOriginalStudent = aAuthorId == qStudentId
                val aIsAnon = isAnon && aIsOriginalStudent
                val aIsMasked = aRow[AnswersTable.isHidden] || (isOpMasked && aIsOriginalStudent)
                val aDisplay = when {
                    aIsAnon -> "익명 수강생"
                    aIsAdmin -> aUser?.get(UsersTable.nickname)?.takeIf { it.isNotBlank() }
                        ?: aUser?.get(UsersTable.name)?.takeIf { it.isNotBlank() }
                        ?: "관리자"
                    aIsStudent -> aUser?.get(UsersTable.displayTag)?.takeIf { it.isNotBlank() }
                        ?: aUser?.get(UsersTable.name)?.takeIf { it.isNotBlank() }
                        ?: "수강생"
                    else -> aUser?.get(UsersTable.name)?.takeIf { it.isNotBlank() } ?: "교수"
                }
                val (roleLabel, roleKind) = when {
                    aIsAdmin && !aIsAnon -> "관리자" to "admin"
                    aIsOriginalStudent && (aIsStudent || aIsAnon) -> "질문 작성자 (추가 질문)" to "student"
                    aIsStudent -> "수강생 답변" to "student"
                    aRow[AnswersTable.isAssignedProfessor] -> "담당교수" to "prof"
                    else -> "공동교수" to "co-prof"
                }
                val aAttachments = if (aIsMasked) {
                    emptyList()
                } else {
                    ansAttachmentRowsMap[aId].orEmpty().map(::toInlineAttachment)
                }
                posts.add(
                    EmailThreadPost(
                        sequenceNumber = aRow[AnswersTable.sequenceNumber],
                        authorDisplay = aDisplay,
                        roleLabel = roleLabel,
                        roleKind = roleKind,
                        isAnonymous = aIsAnon,
                        isHidden = aIsMasked,
                        createdAt = aRow[AnswersTable.createdAt],
                        content = if (aIsMasked) "관리자에 의해 숨겨진 글입니다." else aRow[AnswersTable.content],
                        attachments = aAttachments
                    )
                )
            }

            val resolvedProfName = profRow?.get(UsersTable.name)?.takeIf { it.isNotBlank() } ?: fallbackProfessorName
            val resolvedTitle = when {
                isQuestionDeleted -> "작성자에 의해 삭제된 질문입니다."
                isQuestionHidden -> "관리자에 의해 숨겨진 글입니다."
                else -> qRow[QuestionsTable.title].ifBlank { fallbackTitle }
            }
            val curSem = SemesterUtils.normalizeSemester(
                SystemSettingsTable.select { SystemSettingsTable.settingKey eq "current_semester" }
                    .singleOrNull()?.get(SystemSettingsTable.settingValue),
                SemesterUtils.DEFAULT_SEMESTER
            )
            val courseRow = CoursesTable.select { CoursesTable.id eq qRow[QuestionsTable.courseId] }.singleOrNull()
            val qSem = SemesterUtils.normalizeSemester(
                qRow[QuestionsTable.createdSemester].ifBlank { courseRow?.get(CoursesTable.semester).orEmpty() },
                curSem
            )
            val qCpId = qRow[QuestionsTable.courseProfessorId]
            val qSec = if (qCpId != null) {
                CourseProfessorsTable.select { CourseProfessorsTable.id eq qCpId }
                    .singleOrNull()?.get(CourseProfessorsTable.section)
            } else null
            val snapshot = EmailThreadSnapshot(
                courseName = fallbackCourseName,
                category = fallbackCategory,
                professorName = resolvedProfName,
                title = resolvedTitle,
                isThreadOpen = qRow[QuestionsTable.isThreadOpen],
                isCoProfessorAlert = isCoProfessor,
                hasReplyRoute = hasReplyRoute,
                posts = posts,
                section = qSec,
                semester = qSem,
                currentSemester = curSem
            )
            return EmailThreadHtmlRenderer.renderThreadEmailHtml(snapshot) to inlineImages
        }

        val fallbackSnapshot = EmailThreadSnapshot(
            courseName = fallbackCourseName,
            category = fallbackCategory,
            professorName = fallbackProfessorName,
            title = fallbackTitle,
            isThreadOpen = false,
            isCoProfessorAlert = isCoProfessor,
            hasReplyRoute = hasReplyRoute,
            posts = listOf(
                EmailThreadPost(
                    sequenceNumber = 1,
                    authorDisplay = fallbackAuthorDisplay,
                    roleLabel = "질문 작성자",
                    roleKind = "op",
                    isAnonymous = fallbackAuthorDisplay.contains("익명"),
                    createdAt = Instant.now(),
                    content = fallbackContent,
                    attachments = emptyList()
                )
            )
        )
        return EmailThreadHtmlRenderer.renderThreadEmailHtml(fallbackSnapshot) to inlineImages
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
                    messageId = job.messageId,
                    htmlBody = job.htmlBody,
                    inlineImages = job.inlineImages
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
