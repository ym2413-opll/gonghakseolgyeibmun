package com.gonghak.routes

import com.gonghak.dto.InboundEmailWebhookRequest
import com.gonghak.dto.InboundEmailWebhookResponse
import com.gonghak.service.EmailReplyRejectedException
import com.gonghak.service.EmailReplyService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val WEBHOOK_SECRET_HEADER = "X-Email-Webhook-Secret"

fun Route.webhookRoutes(emailReplyService: EmailReplyService) {
    route("/api/webhooks") {
        post("/email-reply") {
            if (!emailReplyService.isWebhookConfigured()) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    InboundEmailWebhookResponse(
                        accepted = false,
                        message = "서버에 이메일 웹훅 시크릿(EMAIL_WEBHOOK_SECRET)이 설정되지 않았습니다."
                    )
                )
                return@post
            }

            val providedSecret = call.request.headers[WEBHOOK_SECRET_HEADER]
            if (!emailReplyService.verifyWebhookSecret(providedSecret)) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    InboundEmailWebhookResponse(
                        accepted = false,
                        message = "유효하지 않은 이메일 웹훅 인증 키입니다."
                    )
                )
                return@post
            }

            val payload = runCatching { call.receive<InboundEmailWebhookRequest>() }.getOrElse {
                call.respond(
                    HttpStatusCode.BadRequest,
                    InboundEmailWebhookResponse(
                        accepted = false,
                        message = "잘못된 웹훅 JSON 요청 형식입니다."
                    )
                )
                return@post
            }

            try {
                val result = emailReplyService.processInboundReply(payload)
                call.respond(HttpStatusCode.OK, result)
            } catch (ex: EmailReplyRejectedException) {
                call.respond(
                    ex.status,
                    InboundEmailWebhookResponse(accepted = false, message = ex.message)
                )
            } catch (ex: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    InboundEmailWebhookResponse(accepted = false, message = ex.message ?: "잘못된 요청입니다.")
                )
            } catch (ex: IllegalStateException) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    InboundEmailWebhookResponse(accepted = false, message = ex.message ?: "답변 등록 권한이 없습니다.")
                )
            }
        }
    }
}
