package com.gonghak.routes

import com.gonghak.dto.ApiResponse
import com.gonghak.dto.UpdateProfessorNotificationSettingRequest
import com.gonghak.service.EmailService
import com.gonghak.service.NotificationService
import com.gonghak.service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.notificationRoutes(
    notificationService: NotificationService,
    emailService: EmailService = EmailService(),
    userService: UserService = UserService()
) {
    route("/api/notifications") {
        get {
            val userId = call.getCurrentUserId()
            val notifications = notificationService.getNotifications(userId)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = notifications))
        }

        get("/emails") {
            val userId = call.getCurrentUserId()
            val user = userService.getUserById(userId)
            val isAdmin = userService.isUserAdmin(userId)
            if (!isAdmin && user?.role != com.gonghak.models.UserRole.PROFESSOR) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = "교수 또는 관리자만 이메일 알림 로그를 조회할 수 있습니다."))
                return@get
            }
            val logs = emailService.getEmailLogs(professorId = userId, isAdmin = isAdmin)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = logs))
        }

        post("/{id}/read") {
            val userId = call.getCurrentUserId()
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
            val userId = call.getCurrentUserId()
            val setting = notificationService.getProfessorNotificationSetting(userId)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = setting))
        }

        put {
            val userId = call.getCurrentUserId()
            val request = call.receive<UpdateProfessorNotificationSettingRequest>()
            val updated = notificationService.updateProfessorNotificationSetting(userId, request)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = updated))
        }
    }
}
