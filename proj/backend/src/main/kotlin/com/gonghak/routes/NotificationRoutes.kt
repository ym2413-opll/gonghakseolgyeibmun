package com.gonghak.routes

import com.gonghak.dto.ApiResponse
import com.gonghak.dto.SubscribePushRequest
import com.gonghak.dto.UnsubscribePushRequest
import com.gonghak.dto.UpdateProfessorNotificationSettingRequest
import com.gonghak.dto.VapidPublicKeyDto
import com.gonghak.models.UserRole
import com.gonghak.service.EmailService
import com.gonghak.service.NotificationService
import com.gonghak.service.UserService
import com.gonghak.service.WebPushService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.notificationRoutes(
    notificationService: NotificationService,
    emailService: EmailService = EmailService(),
    userService: UserService = UserService(),
    webPushService: WebPushService? = null
) {
    route("/api/notifications") {
        get {
            val user = call.requireAuthenticatedUser(userService) ?: return@get
            val userId = UUID.fromString(user.id)
            val notifications = notificationService.getNotifications(userId)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = notifications))
        }

        get("/emails") {
            val user = call.requireProfessorOrAdmin(userService) ?: return@get
            val userId = UUID.fromString(user.id)
            val isAdmin = user.isAdmin || user.role == UserRole.ADMIN
            val logs = emailService.getEmailLogs(professorId = userId, isAdmin = isAdmin)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = logs))
        }

        post("/{id}/read") {
            val user = call.requireAuthenticatedUser(userService) ?: return@post
            val userId = UUID.fromString(user.id)
            val idParam = call.parameters["id"]
            val notifId = try {
                UUID.fromString(idParam)
            } catch (_: Exception) {
                null
            }
            if (notifId == null) {
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = true))
                return@post
            }
            val success = notificationService.markAsRead(notifId, userId)
            if (success) {
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = true))
            } else {
                call.respond(HttpStatusCode.NotFound, ApiResponse<String>(success = false, message = "알림을 찾을 수 없습니다."))
            }
        }
    }

    route("/api/professor/settings") {
        get {
            val user = call.requireProfessorOrAdmin(userService) ?: return@get
            val userId = UUID.fromString(user.id)
            val setting = notificationService.getProfessorNotificationSetting(userId)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = setting))
        }

        put {
            val user = call.requireProfessorOrAdmin(userService) ?: return@put
            val userId = UUID.fromString(user.id)
            val request = call.receive<UpdateProfessorNotificationSettingRequest>()
            try {
                val updated = notificationService.updateProfessorNotificationSetting(userId, request)
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = updated))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            }
        }
    }

    val pushKeyLimiter = SlidingWindowRateLimiter(maxRequests = 60, windowMillis = 60_000L)
    val pushSubscribeLimiter = SlidingWindowRateLimiter(maxRequests = 20, windowMillis = 60_000L)

    route("/api/push") {
        get("/vapid-public-key") {
            val user = call.requireAuthenticatedUser(userService) ?: return@get
            if (!pushKeyLimiter.allow("${user.id}|${call.clientRateLimitKey()}")) {
                call.respond(HttpStatusCode.TooManyRequests, ApiResponse<String>(success = false, message = "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."))
                return@get
            }
            val service = webPushService
            if (service == null) {
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = VapidPublicKeyDto(publicKey = "", enabled = false)))
                return@get
            }
            val pubKey = service.getPublicKeyBase64Url()
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = VapidPublicKeyDto(publicKey = pubKey, enabled = true)))
        }

        post("/subscribe") {
            val user = call.requireAuthenticatedUser(userService) ?: return@post
            if (!pushSubscribeLimiter.allow("${user.id}|${call.clientRateLimitKey()}")) {
                call.respond(HttpStatusCode.TooManyRequests, ApiResponse<String>(success = false, message = "푸시 구독 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."))
                return@post
            }
            val service = webPushService
            if (service == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse<String>(success = false, message = "웹 푸시 서비스가 비활성화되어 있습니다."))
                return@post
            }
            val request = call.receive<SubscribePushRequest>()
            val userAgent = call.request.headers[HttpHeaders.UserAgent].orEmpty()
            try {
                service.subscribe(UUID.fromString(user.id), request, userAgent)
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = true, message = "백그라운드 알림 구독이 등록되었습니다."))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            }
        }

        post("/unsubscribe") {
            val user = call.requireAuthenticatedUser(userService) ?: return@post
            if (!pushSubscribeLimiter.allow("${user.id}|${call.clientRateLimitKey()}")) {
                call.respond(HttpStatusCode.TooManyRequests, ApiResponse<String>(success = false, message = "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."))
                return@post
            }
            val service = webPushService
            if (service == null) {
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = false))
                return@post
            }
            val request = call.receive<UnsubscribePushRequest>()
            val removed = service.unsubscribe(UUID.fromString(user.id), request.endpoint)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = removed))
        }
    }
}
