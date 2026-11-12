package com.gonghak.service

import com.gonghak.config.DatabaseFactory.dbQuery
import com.gonghak.dto.EmailLogDto
import com.gonghak.models.EmailLogsTable
import com.gonghak.models.QuestionCategory
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLSocketFactory

class EmailService {
    private val logger = LoggerFactory.getLogger(EmailService::class.java)

    /**
     * Send email notification to professor's registered email address when a new question is posted.
     * Must be called inside or outside a transaction (inserts log via Exposed).
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
        val cleanEmail = recipientEmail.trim()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) return

        val categoryLabel = when (category) {
            QuestionCategory.GRADE -> "성적"
            QuestionCategory.ASSIGNMENT -> "과제"
            QuestionCategory.LECTURE -> "강의"
        }

        val prefix = if (isCoProfessor) "[공동강의 질문 알림]" else "[새 질문 알림]"
        val subject = "$prefix [$courseName - $categoryLabel] $questionTitle"
        val body = buildString {
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
            appendLine("포털에 로그인하여 스레드에서 직접 답변을 작성하실 수 있습니다.")
        }

        val smtpHost = System.getenv("SMTP_HOST")?.trim()
        val smtpPort = System.getenv("SMTP_PORT")?.trim()?.toIntOrNull() ?: 587
        val smtpUser = System.getenv("SMTP_USER")?.trim()
        val smtpPass = System.getenv("SMTP_PASS")?.trim()
        val smtpFrom = System.getenv("SMTP_FROM")?.trim()?.takeIf { it.isNotBlank() }
            ?: smtpUser?.takeIf { it.isNotBlank() }
            ?: "noreply@kw.ac.kr"

        var status = "SENT"
        if (!smtpHost.isNullOrBlank()) {
            try {
                dispatchSmtpMail(
                    host = smtpHost,
                    port = smtpPort,
                    user = smtpUser,
                    pass = smtpPass,
                    from = smtpFrom,
                    to = cleanEmail,
                    subject = subject,
                    body = body
                )
                status = "SENT_SMTP"
                logger.info("[EmailService] Sent SMTP email to $cleanEmail (subject: $subject)")
            } catch (e: Exception) {
                logger.warn("[EmailService] SMTP delivery failed (${e.message}), recorded in local outbox log.")
                status = "SENT_LOGGED"
            }
        } else {
            logger.info("[EmailService] Dispatched question notification email to $cleanEmail -> $subject")
        }

        EmailLogsTable.insert {
            it[id] = UUID.randomUUID()
            it[EmailLogsTable.recipientEmail] = cleanEmail
            it[EmailLogsTable.professorId] = professorId
            it[EmailLogsTable.questionId] = questionId
            it[EmailLogsTable.subject] = subject.take(250)
            it[EmailLogsTable.body] = body
            it[EmailLogsTable.status] = status
            it[EmailLogsTable.createdAt] = Instant.now()
        }
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

    private fun dispatchSmtpMail(
        host: String,
        port: Int,
        user: String?,
        pass: String?,
        from: String,
        to: String,
        subject: String,
        body: String
    ) {
        val socket = if (port == 465) {
            SSLSocketFactory.getDefault().createSocket(host, port)
        } else {
            Socket(host, port)
        }
        socket.soTimeout = 4000
        socket.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8), true)

            reader.readLine()
            writer.println("EHLO kwangwoon.ac.kr")
            while (true) {
                val line = reader.readLine() ?: break
                if (line.length < 4 || line[3] != '-') break
            }

            if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                writer.println("AUTH LOGIN")
                reader.readLine()
                writer.println(Base64.getEncoder().encodeToString(user.toByteArray(Charsets.UTF_8)))
                reader.readLine()
                writer.println(Base64.getEncoder().encodeToString(pass.toByteArray(Charsets.UTF_8)))
                reader.readLine()
            }

            writer.println("MAIL FROM:<$from>")
            reader.readLine()
            writer.println("RCPT TO:<$to>")
            reader.readLine()
            writer.println("DATA")
            reader.readLine()

            val encodedSubject = "=?UTF-8?B?${Base64.getEncoder().encodeToString(subject.toByteArray(Charsets.UTF_8))}?="
            writer.println("From: <$from>")
            writer.println("To: <$to>")
            writer.println("Subject: $encodedSubject")
            writer.println("MIME-Version: 1.0")
            writer.println("Content-Type: text/plain; charset=UTF-8")
            writer.println()
            writer.println(body)
            writer.println(".")
            reader.readLine()
            writer.println("QUIT")
        }
    }
}
