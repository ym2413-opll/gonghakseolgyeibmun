package com.gonghak.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Outbound per-email reply tokens binding a cryptographic random local-part token
 * (`reply+<token>@<domain>`) to a specific question and professor recipient.
 */
object EmailReplyTokensTable : Table("email_reply_tokens") {
    val id = uuid("id")
    val token = varchar("token", 64).uniqueIndex()
    val questionId = uuid("question_id").index()
    val professorId = uuid("professor_id").index()
    val recipientEmail = varchar("recipient_email", 150)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
    val expiresAt = timestamp("expires_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Deduplication ledger for inbound webhook deliveries from the Cloudflare Email Worker
 * so SMTP/Worker retries never create duplicate answers.
 */
object ProcessedInboundEmailsTable : Table("processed_inbound_emails") {
    val id = uuid("id")
    val messageId = varchar("message_id", 255).uniqueIndex()
    val token = varchar("token", 64)
    val questionId = uuid("question_id").index()
    val professorId = uuid("professor_id")
    val fromEmail = varchar("from_email", 150)
    val answerId = uuid("answer_id").nullable()
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)
}
