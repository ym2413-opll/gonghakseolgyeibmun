package com.gonghak.routes

import com.gonghak.dto.ApiResponse
import com.gonghak.service.NotificationService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.notificationRoutes(notificationService: NotificationService) {
    route("/api/notifications") {
        get {
            val userId = call.getCurrentUserId()
            val notifications = notificationService.getNotifications(userId)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = notifications))
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
}
