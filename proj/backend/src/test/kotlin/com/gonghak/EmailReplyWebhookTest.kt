package com.gonghak

import com.gonghak.config.DatabaseFactory
import com.gonghak.dto.*
import com.gonghak.models.EmailReplyTokensTable
import com.gonghak.models.QuestionCategory
import com.gonghak.models.QuestionStatus
import com.gonghak.models.UserRole
import com.gonghak.service.BackgroundWorkerManager
import com.gonghak.service.EMAIL_REPLY_ABOVE_MARKER
import com.gonghak.service.EmailReplyService
import com.gonghak.service.EmailService
import com.gonghak.service.PasswordSecurity
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class EmailReplyWebhookTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val testWebhookSecret = "cf-email-worker-test-secret-0123456789"
    private val testReplyDomain = "reply.kw-qna.example"

    private val studentId = "11111111-1111-1111-1111-111111111101" // 서영민
    private val assignedProfId = "22222222-2222-2222-2222-222222222201" // 김교수
    private val courseId = "33333333-3333-3333-3333-333333333301" // 공학설계입문

    private fun bearerToken(userId: String): String =
        "Bearer ${PasswordSecurity.generateAuthToken(UUID.fromString(userId))}"

    @Test
    fun testOutboundReplyRoutingTokenAndMarkerGeneration() {
        DatabaseFactory.init()
        val workers = BackgroundWorkerManager()
        try {
            val emailService = EmailService(workers)
            emailService.configureReplyRouting(domain = testReplyDomain, prefix = "reply")

            val qId = UUID.randomUUID()
            val profId = UUID.fromString(assignedProfId)
            val job = transaction {
                emailService.enqueueQuestionEmail(
                    recipientEmail = "prof.notify@kw.ac.kr",
                    professorId = profId,
                    professorName = "김교수",
                    courseName = "공학설계입문",
                    questionId = qId,
                    questionTitle = "회신 토큰 생성 테스트",
                    questionContent = "질문 본문입니다.",
                    category = QuestionCategory.LECTURE,
                    authorDisplay = "수강생"
                )
            }

            assertNotNull(job)
            assertNotNull(job.replyTo)
            assertTrue(job.replyTo!!.matches(Regex("^reply\\+[a-f0-9]{32}@reply\\.kw-qna\\.example$")), "Unexpected replyTo: ${job.replyTo}")
            assertTrue(job.replyTo!!.substringBefore("@").length <= 64, "Local-part must obey RFC 5321 <= 64 chars")
            assertTrue(job.body.startsWith(EMAIL_REPLY_ABOVE_MARKER), "Email body must start with reply divider marker")

            val tokenHex = job.replyTo!!.substringAfter("reply+").substringBefore("@")
            val stored = transaction {
                EmailReplyTokensTable.select { EmailReplyTokensTable.token eq tokenHex }.singleOrNull()
            }
            assertNotNull(stored)
            assertEquals(qId, stored[EmailReplyTokensTable.questionId])
            assertEquals(profId, stored[EmailReplyTokensTable.professorId])
        } finally {
            workers.shutdown()
        }
    }

    @Test
    fun testQuoteStrippingHandlesKoreanGmailOutlookAndHtmlReplies() {
        val koreanGmailReply = """
            네, 이번 주 금요일 23:59까지 PDF로 제출해 주세요.
            팀당 대표 1명만 업로드하면 됩니다.

            2026년 11월 13일 (금) 오후 3:25, 광운대 학업 Q&A
            <reply+0123456789abcdef0123456789abcdef@reply.kw-qna.example>님이 작성:
            > $EMAIL_REPLY_ABOVE_MARKER
            > 안녕하십니까, 김교수 교수님.
            > 과제 마감 문의드립니다.
        """.trimIndent()

        val stripped = EmailReplyService.extractCleanReplyText(koreanGmailReply, null)
        assertEquals(
            "네, 이번 주 금요일 23:59까지 PDF로 제출해 주세요.\n팀당 대표 1명만 업로드하면 됩니다.",
            stripped
        )

        val htmlOnlyReply = """
            <div>HTML 메일에서도 답변 본문만 추출되는지 확인합니다.<br>추가 공지는 추후 안내하겠습니다.</div>
            <div>$EMAIL_REPLY_ABOVE_MARKER</div>
            <blockquote>안녕하십니까, 김교수 교수님.</blockquote>
        """.trimIndent()

        val strippedHtml = EmailReplyService.extractCleanReplyText(null, htmlOnlyReply)
        assertEquals(
            "HTML 메일에서도 답변 본문만 추출되는지 확인합니다.\n추가 공지는 추후 안내하겠습니다.",
            strippedHtml
        )
    }

    @Test
    fun testWebhookEndToEndReplyFollowUpAndSecurityRejections() = testApplication {
        environment {
            config = ApplicationConfig("application.conf").mergeWith(
                MapApplicationConfig(
                    "emailReply.domain" to testReplyDomain,
                    "emailReply.prefix" to "reply",
                    "emailReply.webhookSecret" to testWebhookSecret,
                    "emailReply.enforceSenderAuth" to "true"
                )
            )
        }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // Configure professor email notifications so creating a question emits a reply token automatically.
        val profEmail = "prof.reply.e2e@kw.ac.kr"
        val putSettingsRes = client.put("/api/professor/settings") {
            header(HttpHeaders.Authorization, bearerToken(assignedProfId))
            contentType(ContentType.Application.Json)
            setBody(
                UpdateProfessorNotificationSettingRequest(
                    emailEnabled = true,
                    notificationEmail = profEmail,
                    quietHoursEnabled = false
                )
            )
        }
        assertEquals(HttpStatusCode.OK, putSettingsRes.status)

        // Student creates a new question -> outbound email job records a reply token in EmailReplyTokensTable.
        val createQRes = client.post("/api/questions") {
            header(HttpHeaders.Authorization, bearerToken(studentId))
            contentType(ContentType.Application.Json)
            setBody(
                CreateQuestionRequest(
                    courseId = courseId,
                    category = QuestionCategory.ASSIGNMENT,
                    title = "이메일 회신 E2E 질문",
                    rawContent = "이메일로 바로 답장하면 포럼에 답변이 등록되는지 확인하고 싶습니다."
                )
            )
        }
        assertEquals(HttpStatusCode.Created, createQRes.status)
        val createdQuestion = createQRes.body<ApiResponse<QuestionDetailDto>>().data!!
        val qUuid = UUID.fromString(createdQuestion.id)

        val issuedToken = transaction {
            EmailReplyTokensTable
                .select { EmailReplyTokensTable.questionId eq qUuid }
                .orderBy(EmailReplyTokensTable.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
                .first()[EmailReplyTokensTable.token]
        }
        val replyToAddr = "reply+$issuedToken@$testReplyDomain"

        // 1. Missing/wrong webhook secret -> 401
        val badSecretRes = client.post("/api/webhooks/email-reply") {
            header("X-Email-Webhook-Secret", "wrong-secret")
            contentType(ContentType.Application.Json)
            setBody(
                InboundEmailWebhookRequest(
                    messageId = "<msg-bad-secret@kw.ac.kr>",
                    from = profEmail,
                    to = replyToAddr,
                    textBody = "답변 테스트",
                    spfResult = "pass",
                    dkimResult = "pass"
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, badSecretRes.status)

        // 2. Spoofed sender failing SPF/DKIM/DMARC -> 403
        val spoofAuthRes = client.post("/api/webhooks/email-reply") {
            header("X-Email-Webhook-Secret", testWebhookSecret)
            contentType(ContentType.Application.Json)
            setBody(
                InboundEmailWebhookRequest(
                    messageId = "<msg-spoof-auth@kw.ac.kr>",
                    from = profEmail,
                    to = replyToAddr,
                    textBody = "위조된 발신 메일",
                    spfResult = "fail",
                    dkimResult = "fail",
                    dmarcResult = "fail"
                )
            )
        }
        assertEquals(HttpStatusCode.Forbidden, spoofAuthRes.status)

        // 3. Mismatched sender email (not professor's registered/notification email) -> 403
        val wrongSenderRes = client.post("/api/webhooks/email-reply") {
            header("X-Email-Webhook-Secret", testWebhookSecret)
            contentType(ContentType.Application.Json)
            setBody(
                InboundEmailWebhookRequest(
                    messageId = "<msg-wrong-sender@gmail.com>",
                    from = "attacker@gmail.com",
                    to = replyToAddr,
                    textBody = "다른 사람이 보낸 답장",
                    spfResult = "pass",
                    dkimResult = "pass"
                )
            )
        }
        assertEquals(HttpStatusCode.Forbidden, wrongSenderRes.status)

        // 4. Tampered token -> 403
        val badTokenRes = client.post("/api/webhooks/email-reply") {
            header("X-Email-Webhook-Secret", testWebhookSecret)
            contentType(ContentType.Application.Json)
            setBody(
                InboundEmailWebhookRequest(
                    messageId = "<msg-bad-token@kw.ac.kr>",
                    from = profEmail,
                    to = "reply+00000000000000000000000000000000@$testReplyDomain",
                    textBody = "변조 토큰 테스트",
                    spfResult = "pass",
                    dkimResult = "pass"
                )
            )
        }
        assertEquals(HttpStatusCode.Forbidden, badTokenRes.status)

        // 5. Empty reply above quote marker -> 400 (and must NOT poison future valid submission)
        val emptyReplyRes = client.post("/api/webhooks/email-reply") {
            header("X-Email-Webhook-Secret", testWebhookSecret)
            contentType(ContentType.Application.Json)
            setBody(
                InboundEmailWebhookRequest(
                    messageId = "<msg-empty@kw.ac.kr>",
                    from = profEmail,
                    to = replyToAddr,
                    textBody = "$EMAIL_REPLY_ABOVE_MARKER\n원본 질문 내용만 있음",
                    spfResult = "pass",
                    dkimResult = "pass"
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, emptyReplyRes.status)

        // 6. Valid authenticated professor reply -> 200 OK & answer posted
        val validReplyRes = client.post("/api/webhooks/email-reply") {
            header("X-Email-Webhook-Secret", testWebhookSecret)
            contentType(ContentType.Application.Json)
            setBody(
                InboundEmailWebhookRequest(
                    messageId = "<msg-valid-1@kw.ac.kr>",
                    from = "\"김교수\" <$profEmail>",
                    to = replyToAddr,
                    subject = "Re: [새 질문 알림] 이메일 회신 E2E 질문",
                    textBody = "좋은 질문입니다.\n이메일 답장으로 정상 답변이 등록됩니다.\n\nOn Fri, Nov 13, 2026 at 3:30 PM <$replyToAddr> wrote:\n> $EMAIL_REPLY_ABOVE_MARKER\n> 질문 원문",
                    authenticationResults = "mx.cloudflare.net; spf=pass smtp.mailfrom=kw.ac.kr; dkim=pass header.d=kw.ac.kr; dmarc=pass"
                )
            )
        }
        assertEquals(HttpStatusCode.OK, validReplyRes.status)
        val validBody = validReplyRes.body<InboundEmailWebhookResponse>()
        assertTrue(validBody.accepted)
        assertFalse(validBody.alreadyProcessed)
        assertEquals(createdQuestion.id, validBody.questionId)
        assertNotNull(validBody.answerId)

        // Verify question updated to ANSWERED and quote text stripped
        val detailAfterReply = client.get("/api/questions/${createdQuestion.id}") {
            header(HttpHeaders.Authorization, bearerToken(studentId))
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals(QuestionStatus.ANSWERED, detailAfterReply.status)
        assertEquals(1, detailAfterReply.answers.size)
        assertEquals(
            "좋은 질문입니다.\n이메일 답장으로 정상 답변이 등록됩니다.",
            detailAfterReply.answers[0].content
        )

        // Verify student received notification for professor answer
        val notifs = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, bearerToken(studentId))
        }.body<ApiResponse<List<NotificationDto>>>().data.orEmpty()
        assertTrue(notifs.any { it.referenceId == createdQuestion.id && it.type == "ANSWER_NEW" })

        // 7. Duplicate Message-ID webhook delivery -> idempotent 200 without duplicate answer
        val dupRes = client.post("/api/webhooks/email-reply") {
            header("X-Email-Webhook-Secret", testWebhookSecret)
            contentType(ContentType.Application.Json)
            setBody(
                InboundEmailWebhookRequest(
                    messageId = "<msg-valid-1@kw.ac.kr>",
                    from = profEmail,
                    to = replyToAddr,
                    textBody = "중복 재전송",
                    dkimResult = "pass"
                )
            )
        }
        assertEquals(HttpStatusCode.OK, dupRes.status)
        val dupBody = dupRes.body<InboundEmailWebhookResponse>()
        assertTrue(dupBody.accepted)
        assertTrue(dupBody.alreadyProcessed)
        assertEquals(validBody.answerId, dupBody.answerId)

        val detailAfterDup = client.get("/api/questions/${createdQuestion.id}") {
            header(HttpHeaders.Authorization, bearerToken(studentId))
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals(1, detailAfterDup.answers.size)

        // 8. Student posts follow-up -> professor replies by email to new token -> second answer registered
        val followUpRes = client.post("/api/questions/${createdQuestion.id}/followups") {
            header(HttpHeaders.Authorization, bearerToken(studentId))
            contentType(ContentType.Application.Json)
            setBody(CreateFollowUpRequest(content = "추가로 참고 자료 링크도 받을 수 있을까요?"))
        }
        assertEquals(HttpStatusCode.Created, followUpRes.status)

        val followUpToken = transaction {
            EmailReplyTokensTable
                .select { EmailReplyTokensTable.questionId eq qUuid }
                .orderBy(EmailReplyTokensTable.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
                .first()[EmailReplyTokensTable.token]
        }

        val followUpReplyRes = client.post("/api/webhooks/email-reply") {
            header("X-Email-Webhook-Secret", testWebhookSecret)
            contentType(ContentType.Application.Json)
            setBody(
                InboundEmailWebhookRequest(
                    messageId = "<msg-valid-followup-2@kw.ac.kr>",
                    from = profEmail,
                    to = "reply+$followUpToken@$testReplyDomain",
                    textBody = "강의자료실 10주차 폴더를 참고해 주세요.\n\n$EMAIL_REPLY_ABOVE_MARKER\n추가 질문 내용",
                    dkimResult = "pass"
                )
            )
        }
        assertEquals(HttpStatusCode.OK, followUpReplyRes.status)
        val detailAfterFollowUp = client.get("/api/questions/${createdQuestion.id}") {
            header(HttpHeaders.Authorization, bearerToken(studentId))
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals(QuestionStatus.ANSWERED, detailAfterFollowUp.status)
        assertEquals(3, detailAfterFollowUp.answers.size)
        assertEquals("강의자료실 10주차 폴더를 참고해 주세요.", detailAfterFollowUp.answers.last().content)
    }

    @Test
    fun testWebhookFailsClosedWhenSecretUnconfigured() = testApplication {
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }
        val res = client.post("/api/webhooks/email-reply") {
            header("X-Email-Webhook-Secret", "any")
            contentType(ContentType.Application.Json)
            setBody(
                InboundEmailWebhookRequest(
                    from = "prof@kw.ac.kr",
                    to = "reply+0123456789abcdef0123456789abcdef@reply.example.com",
                    textBody = "테스트",
                    dkimResult = "pass"
                )
            )
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
    }
}
