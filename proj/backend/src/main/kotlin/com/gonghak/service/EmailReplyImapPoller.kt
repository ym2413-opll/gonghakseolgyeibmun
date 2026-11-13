package com.gonghak.service

import com.gonghak.dto.InboundEmailWebhookRequest
import io.ktor.server.config.ApplicationConfig
import jakarta.mail.BodyPart
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeUtility
import jakarta.mail.search.FlagTerm
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.Properties

private const val MAX_IMAP_MESSAGES_PER_POLL = 25
private const val MAX_BODY_CHARS = 100_000
private const val MAX_IGNORED_UID_CACHE = 4096

data class EmailReplyImapConfig(
    val enabled: Boolean,
    val host: String?,
    val port: Int,
    val user: String?,
    val pass: String?,
    val folder: String,
    val pollIntervalSeconds: Long
) {
    val isReadyToPoll: Boolean
        get() = enabled && !host.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank()

    val inferredReplyDomain: String?
        get() = if (enabled && user != null && user.contains("@")) {
            normalizeReplyDomain(user.substringAfter("@"))
        } else {
            null
        }

    val inferredReplyPrefix: String?
        get() = if (enabled && user != null && user.contains("@")) {
            normalizeReplyPrefix(user.substringBefore("@").substringBefore("+"))
        } else {
            null
        }

    companion object {
        fun from(config: ApplicationConfig? = null): EmailReplyImapConfig {
            fun readPropOrEnv(configKey: String, envKey: String): String? {
                val fromConfig = runCatching { config?.propertyOrNull(configKey)?.getString() }.getOrNull()
                return (fromConfig ?: com.gonghak.config.EnvConfig.get(envKey))?.trim()?.takeIf { it.isNotBlank() }
            }

            val rawEnabled = readPropOrEnv("emailImap.enabled", "IMAP_ENABLED")?.lowercase()
            val explicitHost = readPropOrEnv("emailImap.host", "IMAP_HOST")
            val smtpHost = readPropOrEnv("emailImap.smtpHost", "SMTP_HOST")
            val user = readPropOrEnv("emailImap.user", "IMAP_USER")
                ?: readPropOrEnv("emailImap.smtpUser", "SMTP_USER")
            val pass = readPropOrEnv("emailImap.pass", "IMAP_PASS")
                ?: readPropOrEnv("emailImap.smtpPass", "SMTP_PASS")

            val enabled = when (rawEnabled) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> !explicitHost.isNullOrBlank()
            }

            val resolvedHost = explicitHost ?: inferImapHost(smtpHost, user)
            val port = readPropOrEnv("emailImap.port", "IMAP_PORT")?.toIntOrNull() ?: 993
            val folder = readPropOrEnv("emailImap.folder", "IMAP_FOLDER") ?: "INBOX"
            val pollInterval = (readPropOrEnv("emailImap.pollIntervalSeconds", "IMAP_POLL_INTERVAL_SECONDS")
                ?.toLongOrNull() ?: 20L).coerceAtLeast(5L)

            return EmailReplyImapConfig(
                enabled = enabled,
                host = resolvedHost,
                port = port,
                user = user,
                pass = pass,
                folder = folder,
                pollIntervalSeconds = pollInterval
            )
        }

        internal fun inferImapHost(smtpHost: String?, emailUser: String?): String? {
            val cleanSmtp = smtpHost?.trim()?.lowercase().orEmpty()
            val domain = emailUser?.substringAfter("@", "")?.trim()?.lowercase().orEmpty()
            return when {
                cleanSmtp == "smtp.gmail.com" || domain == "gmail.com" || domain == "googlemail.com" -> "imap.gmail.com"
                cleanSmtp == "smtp.naver.com" || domain == "naver.com" -> "imap.naver.com"
                cleanSmtp == "smtp.daum.net" || domain == "daum.net" || domain == "hanmail.net" -> "imap.daum.net"
                cleanSmtp == "smtp.kakao.com" || domain == "kakao.com" -> "imap.kakao.com"
                cleanSmtp == "smtp.office365.com" || domain == "outlook.com" || domain == "hotmail.com" -> "outlook.office365.com"
                cleanSmtp.startsWith("smtp.") && cleanSmtp.length > 5 -> "imap.${cleanSmtp.removePrefix("smtp.")}"
                else -> null
            }
        }
    }
}

class EmailReplyImapPoller(
    private val emailReplyService: EmailReplyService,
    val config: EmailReplyImapConfig
) {
    private val logger = LoggerFactory.getLogger(EmailReplyImapPoller::class.java)
    private var lastUidValidity: Long = -1L
    private val ignoredNonReplyUids: MutableSet<Long> = Collections.synchronizedSet(LinkedHashSet())

    val isEnabled: Boolean
        get() = config.isReadyToPoll

    fun pollOnce(): Int {
        if (!config.isReadyToPoll) return 0

        val useSsl = config.port != 143
        val protocol = if (useSsl) "imaps" else "imap"
        val props = Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.host", config.host!!)
            put("mail.$protocol.port", config.port.toString())
            put("mail.$protocol.connectiontimeout", "10000")
            put("mail.$protocol.timeout", "15000")
            put("mail.$protocol.writetimeout", "10000")
            if (useSsl) {
                put("mail.imaps.ssl.enable", "true")
            } else {
                put("mail.imap.starttls.enable", "true")
            }
        }

        val session = Session.getInstance(props)
        val store = session.getStore(protocol)
        var processedCount = 0

        try {
            store.connect(config.host, config.port, config.user, config.pass)
            val folder = store.getFolder(config.folder)
            folder.open(Folder.READ_WRITE)
            try {
                val uidFolder = folder as? UIDFolder
                val uidValidity = uidFolder?.uidValidity ?: -1L
                if (uidValidity != lastUidValidity) {
                    ignoredNonReplyUids.clear()
                    lastUidValidity = uidValidity
                }

                val unseen = folder.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
                if (unseen.isEmpty()) return 0

                // Fetch envelopes and recipient headers in bulk first so non-reply emails in INBOX are skipped untouched.
                val fetchProfile = jakarta.mail.FetchProfile().apply {
                    add(jakarta.mail.FetchProfile.Item.FLAGS)
                    add(jakarta.mail.FetchProfile.Item.ENVELOPE)
                    if (uidFolder != null) add(UIDFolder.FetchProfileItem.UID)
                    add("To")
                    add("Delivered-To")
                    add("X-Original-To")
                    add("Cc")
                }
                folder.fetch(unseen, fetchProfile)

                var inspectedCandidates = 0
                for (msg in unseen) {
                    if (inspectedCandidates >= MAX_IMAP_MESSAGES_PER_POLL) break

                    val uid = runCatching { uidFolder?.getUID(msg) }.getOrNull()
                    if (uid != null && ignoredNonReplyUids.contains(uid)) {
                        continue
                    }

                    val combinedRecipients = collectRecipientHeaders(msg)
                    if (emailReplyService.extractReplyToken(combinedRecipients) == null) {
                        if (uid != null) rememberIgnoredUid(uid)
                        continue
                    }

                    inspectedCandidates++
                    val webhookReq = try {
                        extractWebhookRequest(msg, combinedRecipients)
                    } catch (ex: Throwable) {
                        logger.warn("Failed to parse IMAP email message: {}", ex.message)
                        continue
                    }

                    try {
                        val result = runBlocking {
                            emailReplyService.processInboundReply(webhookReq)
                        }
                        msg.setFlag(Flags.Flag.SEEN, true)
                        if (!result.alreadyProcessed) {
                            processedCount++
                        }
                    } catch (ex: EmailReplyRejectedException) {
                        logger.warn(
                            "Permanently rejected inbound IMAP reply ({}): {}",
                            ex.status.value,
                            ex.message
                        )
                        msg.setFlag(Flags.Flag.SEEN, true)
                    } catch (ex: IllegalArgumentException) {
                        logger.warn("Permanently rejected inbound IMAP reply (400): {}", ex.message)
                        msg.setFlag(Flags.Flag.SEEN, true)
                    } catch (ex: IllegalStateException) {
                        logger.warn("Permanently rejected inbound IMAP reply (403): {}", ex.message)
                        msg.setFlag(Flags.Flag.SEEN, true)
                    } catch (ex: Throwable) {
                        // Leave message UNSEEN for retry on transient database/service errors.
                        logger.error("Transient error while processing inbound IMAP reply: {}", ex.message, ex)
                    }
                }
            } finally {
                runCatching { folder.close(false) }
            }
        } finally {
            runCatching { store.close() }
        }

        return processedCount
    }

    private fun rememberIgnoredUid(uid: Long) {
        synchronized(ignoredNonReplyUids) {
            if (ignoredNonReplyUids.size >= MAX_IGNORED_UID_CACHE) {
                val iterator = ignoredNonReplyUids.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            ignoredNonReplyUids.add(uid)
        }
    }

    companion object {
        internal fun collectRecipientHeaders(msg: Message): String {
            val headerValues = mutableListOf<String>()
            for (headerName in listOf("Delivered-To", "X-Original-To", "To", "Cc")) {
                msg.getHeader(headerName)?.forEach { raw ->
                    val decoded = runCatching { MimeUtility.decodeText(raw) }.getOrDefault(raw)
                    if (decoded.isNotBlank()) headerValues.add(decoded)
                }
            }
            runCatching { msg.allRecipients }.getOrNull()?.forEach { addr ->
                val raw = addr.toString()
                if (raw.isNotBlank()) headerValues.add(raw)
            }
            return headerValues.joinToString(", ")
        }

        internal fun extractWebhookRequest(
            msg: Message,
            preCollectedRecipients: String? = null
        ): InboundEmailWebhookRequest {
            val messageId = msg.getHeader("Message-ID")?.firstOrNull()?.trim()
            val inReplyTo = msg.getHeader("In-Reply-To")?.firstOrNull()?.trim()
            val rawFrom = msg.from?.joinToString(", ") { addr ->
                when (addr) {
                    is InternetAddress -> addr.address ?: addr.toUnicodeString()
                    else -> addr.toString()
                }
            } ?: msg.getHeader("From")?.firstOrNull().orEmpty()
            val to = preCollectedRecipients ?: collectRecipientHeaders(msg)

            val authResultsList = msg.getHeader("Authentication-Results")?.toList()
                ?.takeIf { it.isNotEmpty() }
                ?: msg.getHeader("ARC-Authentication-Results")?.toList()
                ?: emptyList()
            val authResults = authResultsList
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString("; ")
                .takeIf { it.isNotBlank() }

            val extractedBodies = ExtractedEmailBodies()
            collectBodiesFromPart(msg, extractedBodies)

            return InboundEmailWebhookRequest(
                messageId = messageId,
                from = rawFrom,
                to = to,
                subject = msg.subject,
                textBody = extractedBodies.textParts.joinToString("\n").take(MAX_BODY_CHARS),
                htmlBody = extractedBodies.htmlParts.joinToString("\n").take(MAX_BODY_CHARS),
                authenticationResults = authResults,
                inReplyTo = inReplyTo
            )
        }

        private class ExtractedEmailBodies(
            val textParts: MutableList<String> = mutableListOf(),
            val htmlParts: MutableList<String> = mutableListOf()
        )

        private fun collectBodiesFromPart(part: Part, out: ExtractedEmailBodies, depth: Int = 0) {
            if (depth > 10) return
            val disposition = runCatching { part.disposition }.getOrNull()?.lowercase()
            if (disposition == Part.ATTACHMENT) return

            val content = runCatching { part.content }.getOrNull() ?: return
            when {
                part.isMimeType("text/plain") && content is String -> {
                    if (content.isNotBlank()) out.textParts.add(content)
                }
                part.isMimeType("text/html") && content is String -> {
                    if (content.isNotBlank()) out.htmlParts.add(content)
                }
                part.isMimeType("multipart/*") && content is Multipart -> {
                    for (i in 0 until content.count) {
                        val bodyPart: BodyPart = content.getBodyPart(i)
                        collectBodiesFromPart(bodyPart, out, depth + 1)
                    }
                }
                content is Part -> {
                    collectBodiesFromPart(content, out, depth + 1)
                }
            }
        }
    }
}
