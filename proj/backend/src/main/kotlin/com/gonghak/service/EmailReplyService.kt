package com.gonghak.service

import com.gonghak.config.DatabaseFactory.dbQuery
import com.gonghak.dto.CreateAnswerRequest
import com.gonghak.dto.InboundEmailWebhookRequest
import com.gonghak.dto.InboundEmailWebhookResponse
import com.gonghak.models.EmailReplyTokensTable
import com.gonghak.models.ProcessedInboundEmailsTable
import com.gonghak.models.ProfessorNotificationSettingsTable
import com.gonghak.models.UserRole
import com.gonghak.models.UsersTable
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

const val EMAIL_REPLY_ABOVE_MARKER = "----- 이 선 위에 답변을 작성하시면 포럼에 자동 등록됩니다 -----"

class EmailReplyRejectedException(
    val status: HttpStatusCode,
    override val message: String
) : RuntimeException(message)

internal data class IssuedEmailReplyRoute(
    val token: String,
    val replyToAddress: String,
    val messageId: String
)

private val replyTokenRandom = SecureRandom()

internal fun normalizeReplyPrefix(rawPrefix: String?): String {
    val cleaned = rawPrefix?.trim()?.lowercase()?.replace(Regex("[^a-z0-9._-]"), "")
    return cleaned?.takeIf { it.isNotBlank() } ?: "reply"
}

internal fun normalizeReplyDomain(rawDomain: String?): String? {
    val cleaned = rawDomain?.trim()?.lowercase()?.replace(Regex("[\\r\\n<>\\s@]"), "")
    return cleaned?.takeIf { it.contains(".") }
}

/**
 * Generates and persists a cryptographic random reply token bound to `(questionId, professorId)`.
 * Uses lowercase hexadecimal to stay strictly under RFC 5321's 64-char local-part limit and
 * remain immune to MTA address lowercasing. Must be called inside an active Exposed transaction.
 */
internal fun issueEmailReplyRouteInTx(
    replyDomain: String?,
    replyPrefix: String?,
    recipientEmail: String,
    professorId: UUID,
    questionId: UUID,
    validityDays: Long = 90L
): IssuedEmailReplyRoute? {
    val domain = normalizeReplyDomain(replyDomain) ?: return null
    val prefix = normalizeReplyPrefix(replyPrefix)
    val randomBytes = ByteArray(16)
    replyTokenRandom.nextBytes(randomBytes)
    val token = randomBytes.joinToString("") { "%02x".format(it) }

    EmailReplyTokensTable.insert {
        it[id] = UUID.randomUUID()
        it[EmailReplyTokensTable.token] = token
        it[EmailReplyTokensTable.questionId] = questionId
        it[EmailReplyTokensTable.professorId] = professorId
        it[EmailReplyTokensTable.recipientEmail] = recipientEmail.trim().lowercase()
        it[createdAt] = Instant.now()
        it[expiresAt] = Instant.now().plus(validityDays, ChronoUnit.DAYS)
    }

    return IssuedEmailReplyRoute(
        token = token,
        replyToAddress = "$prefix+$token@$domain",
        messageId = "<q-$questionId-$token@$domain>"
    )
}

class EmailReplyService(
    private val questionService: QuestionService,
    replyDomainOverride: String? = null,
    replyPrefixOverride: String? = null,
    webhookSecretOverride: String? = null,
    enforceSenderAuthOverride: Boolean? = null
) {
    private val logger = LoggerFactory.getLogger(EmailReplyService::class.java)

    private val expectedReplyDomain: String? =
        normalizeReplyDomain(replyDomainOverride ?: System.getenv("EMAIL_REPLY_DOMAIN"))

    private val expectedReplyPrefix: String =
        normalizeReplyPrefix(replyPrefixOverride ?: System.getenv("EMAIL_REPLY_PREFIX"))

    private val webhookSecret: String? =
        (webhookSecretOverride ?: System.getenv("EMAIL_WEBHOOK_SECRET"))?.trim()?.takeIf { it.isNotBlank() }

    private val enforceSenderAuth: Boolean =
        enforceSenderAuthOverride
            ?: System.getenv("EMAIL_ENFORCE_SENDER_AUTH")
                ?.trim()
                ?.lowercase()
                ?.let { it != "false" && it != "0" }
            ?: true

    fun isWebhookConfigured(): Boolean = !webhookSecret.isNullOrBlank()

    fun verifyWebhookSecret(providedSecret: String?): Boolean {
        val expected = webhookSecret ?: return false
        val candidate = providedSecret?.trim() ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            candidate.toByteArray(StandardCharsets.UTF_8)
        )
    }

    suspend fun processInboundReply(request: InboundEmailWebhookRequest): InboundEmailWebhookResponse {
        val extractedToken = extractReplyToken(request.to)
            ?: throw EmailReplyRejectedException(
                HttpStatusCode.Forbidden,
                "유효한 회신 주소 토큰을 찾을 수 없습니다."
            )

        val senderEmail = extractBareEmails(request.from).firstOrNull()
            ?: throw EmailReplyRejectedException(
                HttpStatusCode.BadRequest,
                "발신자 이메일 주소를 확인할 수 없습니다."
            )

        if (enforceSenderAuth && !isSenderAuthValid(request)) {
            logger.warn("Rejected inbound email reply due to failed/missing SPF/DKIM/DMARC auth: from={}", senderEmail)
            throw EmailReplyRejectedException(
                HttpStatusCode.Forbidden,
                "발신자 이메일 인증(SPF/DKIM/DMARC)을 통과하지 못했습니다."
            )
        }

        val cleanReplyBody = extractCleanReplyText(request.textBody, request.htmlBody)
        if (cleanReplyBody.isBlank()) {
            throw EmailReplyRejectedException(
                HttpStatusCode.BadRequest,
                "이메일 본문에서 등록할 답변 내용을 찾을 수 없습니다."
            )
        }

        val effectiveMessageId = buildEffectiveMessageId(
            rawMessageId = request.messageId,
            token = extractedToken,
            senderEmail = senderEmail,
            replyBody = cleanReplyBody
        )

        val prep = dbQuery {
            val existingProcessed = ProcessedInboundEmailsTable.select {
                ProcessedInboundEmailsTable.messageId eq effectiveMessageId
            }.singleOrNull()
            if (existingProcessed != null) {
                return@dbQuery InboundPrepResult.AlreadyProcessed(
                    questionId = existingProcessed[ProcessedInboundEmailsTable.questionId],
                    answerId = existingProcessed[ProcessedInboundEmailsTable.answerId]
                )
            }

            val tokenRow = EmailReplyTokensTable.select {
                EmailReplyTokensTable.token eq extractedToken
            }.singleOrNull()
                ?: throw EmailReplyRejectedException(
                    HttpStatusCode.Forbidden,
                    "유효하지 않거나 존재하지 않는 회신 토큰입니다."
                )

            if (tokenRow[EmailReplyTokensTable.expiresAt].isBefore(Instant.now())) {
                throw EmailReplyRejectedException(
                    HttpStatusCode.Forbidden,
                    "만료된 회신 토큰입니다. 포럼 웹에서 직접 답변을 등록해 주세요."
                )
            }

            val questionId = tokenRow[EmailReplyTokensTable.questionId]
            val professorId = tokenRow[EmailReplyTokensTable.professorId]

            val profUser = UsersTable.select { UsersTable.id eq professorId }.singleOrNull()
                ?: throw EmailReplyRejectedException(
                    HttpStatusCode.Forbidden,
                    "담당 교수 계정을 찾을 수 없습니다."
                )

            if (profUser[UsersTable.isWithdrawn]) {
                throw EmailReplyRejectedException(
                    HttpStatusCode.Forbidden,
                    "비활성화된 계정으로는 이메일 답변을 등록할 수 없습니다."
                )
            }

            val role = UserRole.valueOf(profUser[UsersTable.role])
            val canAnswerRole = role == UserRole.PROFESSOR || role == UserRole.ADMIN || profUser[UsersTable.isAdmin]
            if (!canAnswerRole) {
                throw EmailReplyRejectedException(
                    HttpStatusCode.Forbidden,
                    "교수 또는 관리자 권한이 있는 계정만 답변할 수 있습니다."
                )
            }

            val profNotificationEmail = ProfessorNotificationSettingsTable
                .select { ProfessorNotificationSettingsTable.professorId eq professorId }
                .singleOrNull()
                ?.get(ProfessorNotificationSettingsTable.notificationEmail)

            val authorizedEmails = buildSet {
                profUser[UsersTable.email]?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let(::add)
                profNotificationEmail?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let(::add)
            }

            if (senderEmail !in authorizedEmails) {
                logger.warn(
                    "Rejected inbound email reply: sender={} not in professor {} authorized emails",
                    senderEmail,
                    professorId
                )
                throw EmailReplyRejectedException(
                    HttpStatusCode.Forbidden,
                    "발신 이메일($senderEmail)이 질문의 담당 교수 계정에 등록된 이메일과 일치하지 않습니다."
                )
            }

            // Atomically claim messageId before creating the answer to prevent concurrent duplicate answers.
            try {
                ProcessedInboundEmailsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[messageId] = effectiveMessageId
                    it[token] = extractedToken
                    it[ProcessedInboundEmailsTable.questionId] = questionId
                    it[ProcessedInboundEmailsTable.professorId] = professorId
                    it[fromEmail] = senderEmail
                    it[answerId] = null
                    it[createdAt] = Instant.now()
                }
            } catch (ex: Throwable) {
                if (isUniqueConstraintViolation(ex)) {
                    val concurrent = ProcessedInboundEmailsTable.select {
                        ProcessedInboundEmailsTable.messageId eq effectiveMessageId
                    }.singleOrNull()
                    return@dbQuery InboundPrepResult.AlreadyProcessed(
                        questionId = concurrent?.get(ProcessedInboundEmailsTable.questionId) ?: questionId,
                        answerId = concurrent?.get(ProcessedInboundEmailsTable.answerId)
                    )
                }
                throw ex
            }

            InboundPrepResult.ReadyToPost(questionId = questionId, professorId = professorId)
        }

        if (prep is InboundPrepResult.AlreadyProcessed) {
            return InboundEmailWebhookResponse(
                accepted = true,
                alreadyProcessed = true,
                questionId = prep.questionId.toString(),
                answerId = prep.answerId?.toString(),
                message = "이미 처리된 이메일 답변입니다."
            )
        }

        val ready = prep as InboundPrepResult.ReadyToPost
        val createdAnswer = try {
            questionService.createAnswer(
                questionId = ready.questionId,
                professorId = ready.professorId,
                request = CreateAnswerRequest(content = cleanReplyBody)
            )
        } catch (ex: Throwable) {
            // Release the deduplication claim so transient failures or caller-fixable errors do not wedge retries.
            runCatching {
                dbQuery {
                    ProcessedInboundEmailsTable.deleteWhere { messageId eq effectiveMessageId }
                }
            }
            throw ex
        }

        val createdAnswerUuid = runCatching { UUID.fromString(createdAnswer.id) }.getOrNull()
        if (createdAnswerUuid != null) {
            runCatching {
                dbQuery {
                    ProcessedInboundEmailsTable.update({ ProcessedInboundEmailsTable.messageId eq effectiveMessageId }) {
                        it[answerId] = createdAnswerUuid
                    }
                }
            }
        }

        logger.info(
            "Registered answer {} on question {} via email reply from professor {}",
            createdAnswer.id,
            ready.questionId,
            ready.professorId
        )

        return InboundEmailWebhookResponse(
            accepted = true,
            alreadyProcessed = false,
            questionId = ready.questionId.toString(),
            answerId = createdAnswer.id,
            message = "이메일 답변이 포럼에 정상 등록되었습니다."
        )
    }

    internal fun extractReplyToken(rawToHeader: String): String? {
        val candidates = extractBareEmails(rawToHeader)
        val tokenRegex = Regex("^([a-z0-9._-]+)\\+([a-f0-9]{16,64})@([a-z0-9.-]+)$")
        for (addr in candidates) {
            val match = tokenRegex.matchEntire(addr) ?: continue
            val prefix = match.groupValues[1]
            val token = match.groupValues[2]
            val domain = match.groupValues[3]
            if (expectedReplyDomain != null && domain != expectedReplyDomain) continue
            if (prefix != expectedReplyPrefix) continue
            return token
        }
        return null
    }

    private fun isSenderAuthValid(request: InboundEmailWebhookRequest): Boolean {
        var spf = request.spfResult?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        var dkim = request.dkimResult?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        var dmarc = request.dmarcResult?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

        val rawAuth = request.authenticationResults?.lowercase().orEmpty()
        if (rawAuth.isNotBlank()) {
            Regex("\\b(spf|dkim|dmarc)=([a-z]+)").findAll(rawAuth).forEach { m ->
                val mechanism = m.groupValues[1]
                val verdict = m.groupValues[2]
                when (mechanism) {
                    "spf" -> if (spf == null || verdict == "pass") spf = verdict
                    "dkim" -> if (dkim == null || verdict == "pass") dkim = verdict
                    "dmarc" -> if (dmarc == null || verdict == "pass") dmarc = verdict
                }
            }
        }

        if (dmarc == "fail") return false
        if (dkim == "fail" && spf != "pass") return false
        return dkim == "pass" || dmarc == "pass" || (spf == "pass" && dkim != "fail" && dmarc != "fail")
    }

    private fun buildEffectiveMessageId(
        rawMessageId: String?,
        token: String,
        senderEmail: String,
        replyBody: String
    ): String {
        val normalized = rawMessageId?.trim()?.takeIf { it.isNotBlank() }
        if (normalized != null) {
            return normalized.take(255)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$token|$senderEmail|$replyBody".toByteArray(StandardCharsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun isUniqueConstraintViolation(ex: Throwable): Boolean {
        var current: Throwable? = ex
        while (current != null) {
            if (current is ExposedSQLException && current.sqlState == "23505") return true
            if (current is SQLException && current.sqlState == "23505") return true
            current = current.cause
        }
        return false
    }

    private sealed interface InboundPrepResult {
        data class AlreadyProcessed(val questionId: UUID, val answerId: UUID?) : InboundPrepResult
        data class ReadyToPost(val questionId: UUID, val professorId: UUID) : InboundPrepResult
    }

    companion object {
        private val quoteDividerRegex = Regex(
            "^(?:-{2,}\\s*(?:Original Message|원본 메시지|Forwarded message)\\s*-{2,}|_{10,}|={10,})$",
            RegexOption.IGNORE_CASE
        )
        private val singleLineAttributionRegex = Regex(
            "^(?:On .+ wrote:|\\d{4}년\\s*\\d{1,2}월\\s*\\d{1,2}일.*작성\\s*:?|보낸\\s*사람\\s*:|From\\s*:.*)$",
            RegexOption.IGNORE_CASE
        )
        private val wrappedAttributionEndRegex = Regex("(?:님이\\s*작성|wrote)\\s*:?\\s*$", RegexOption.IGNORE_CASE)
        private val attributionLeadRegex = Regex("^(?:On\\s+.+|\\d{4}년\\s*\\d{1,2}월|\\d{4}[-./]\\d{1,2}[-./]\\d{1,2})")

        internal fun extractBareEmails(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            val withoutQuotedDisplayNames = raw.replace(Regex("\"(?:\\\\.|[^\"])*\""), " ")
            return withoutQuotedDisplayNames
                .split(",")
                .mapNotNull { segment ->
                    val bracketed = Regex("<([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+)>").find(segment)?.groupValues?.get(1)
                    val candidate = bracketed ?: Regex("([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+)").find(segment)?.groupValues?.get(1)
                    candidate?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                }
                .distinct()
        }

        internal fun extractCleanReplyText(textBody: String?, htmlBody: String?): String {
            val sourceText = when {
                !textBody.isNullOrBlank() -> textBody
                !htmlBody.isNullOrBlank() -> htmlToPlainText(htmlBody)
                else -> return ""
            }

            val lines = sourceText.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            val kept = mutableListOf<String>()

            for (rawLine in lines) {
                val trimmed = rawLine.trim()
                if (trimmed.contains(EMAIL_REPLY_ABOVE_MARKER)) break
                if (quoteDividerRegex.matches(trimmed)) break
                if (singleLineAttributionRegex.matches(trimmed)) break

                // Handle Gmail/Apple Mail quote attribution wrapped across 2 lines (e.g. date+name on N, "님이 작성:" on N+1).
                if (wrappedAttributionEndRegex.containsMatchIn(trimmed) && kept.isNotEmpty()) {
                    popTrailingWrappedAttributionLead(kept)
                    break
                }
                kept.add(rawLine.trimEnd())
            }

            while (kept.isNotEmpty() && kept.last().isBlank()) {
                kept.removeAt(kept.lastIndex)
            }
            while (kept.isNotEmpty() && kept.last().trimStart().startsWith(">")) {
                kept.removeAt(kept.lastIndex)
                while (kept.isNotEmpty() && kept.last().isBlank()) {
                    kept.removeAt(kept.lastIndex)
                }
            }

            return kept.joinToString("\n").trim()
        }

        private fun popTrailingWrappedAttributionLead(kept: MutableList<String>) {
            var endIdx = kept.lastIndex
            while (endIdx >= 0 && kept[endIdx].isBlank()) {
                endIdx--
            }
            if (endIdx < 0) return

            val candidateStart = maxOf(0, endIdx - 2)
            for (idx in candidateStart..endIdx) {
                if (attributionLeadRegex.containsMatchIn(kept[idx].trimStart())) {
                    while (kept.size > idx) {
                        kept.removeAt(kept.lastIndex)
                    }
                    return
                }
            }
        }

        private fun htmlToPlainText(html: String): String {
            return html
                .replace(Regex("(?i)<style[^>]*>[\\s\\S]*?</style>"), "")
                .replace(Regex("(?i)<script[^>]*>[\\s\\S]*?</script>"), "")
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?i)</(?:p|div|li|tr|blockquote|h[1-6])>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
        }
    }
}
