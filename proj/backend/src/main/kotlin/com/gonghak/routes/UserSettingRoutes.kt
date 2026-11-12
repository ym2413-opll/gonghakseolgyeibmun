package com.gonghak.routes

import com.gonghak.dto.*
import com.gonghak.service.NotificationService
import com.gonghak.service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.userSettingRoutes(
    userService: UserService,
    notificationService: NotificationService
) {
    suspend fun buildUnifiedSettings(userId: java.util.UUID): UnifiedSettingDto {
        val userSetting = userService.getUserSetting(userId)
        val profSetting = notificationService.getProfessorNotificationSetting(userId)
        val categoriesMap = userService.getProfessorActiveCategoriesMap(userId)

        return UnifiedSettingDto(
            userId = userId.toString(),
            theme = if (userSetting.themeMode == "system") "light" else userSetting.themeMode,
            themeMode = userSetting.themeMode,
            fontSize = when (userSetting.fontSize) {
                "small" -> "sm"
                "large" -> "lg"
                "xlarge" -> "xl"
                else -> if (userSetting.fontSize in listOf("sm", "md", "lg", "xl")) userSetting.fontSize else "md"
            },
            dndEnabled = profSetting.quietHoursEnabled,
            dndStartTime = profSetting.quietHoursStart,
            dndEndTime = profSetting.quietHoursEnd,
            coProfNotification = profSetting.notifyOtherProfessorQuestions,
            emailEnabled = profSetting.emailEnabled,
            notificationEmail = profSetting.notificationEmail,
            activeCategories = categoriesMap
        )
    }

    suspend fun applyUnifiedUpdate(userId: java.util.UUID, request: UpdateUnifiedSettingRequest): UnifiedSettingDto {
        val mappedTheme = request.theme ?: request.themeMode
        val mappedFont = request.fontSize
        if (mappedTheme != null || mappedFont != null) {
            userService.updateUserSetting(
                userId,
                UpdateUserSettingRequest(
                    themeMode = mappedTheme,
                    fontSize = mappedFont
                )
            )
        }

        if (request.dndEnabled != null ||
            request.dndStartTime != null ||
            request.dndEndTime != null ||
            request.coProfNotification != null ||
            request.emailEnabled != null ||
            request.notificationEmail != null
        ) {
            notificationService.updateProfessorNotificationSetting(
                userId,
                UpdateProfessorNotificationSettingRequest(
                    quietHoursEnabled = request.dndEnabled,
                    quietHoursStart = request.dndStartTime,
                    quietHoursEnd = request.dndEndTime,
                    notifyOtherProfessorQuestions = request.coProfNotification,
                    emailEnabled = request.emailEnabled,
                    notificationEmail = request.notificationEmail
                )
            )
        }

        if (request.activeCategories != null) {
            userService.updateProfessorActiveCategoriesMap(userId, request.activeCategories)
        }

        return buildUnifiedSettings(userId)
    }

    route("/api/settings") {
        get {
            val userId = call.getCurrentUserId()
            call.respond(HttpStatusCode.OK, buildUnifiedSettings(userId))
        }

        post {
            val request = call.receive<UpdateUnifiedSettingRequest>()
            val tokenUserId = call.getVerifiedTokenUserId()
            val userId = tokenUserId
                ?: (if (isDemoModeEnabled()) request.userId?.let { parseDemoUserIdOrNull(it) } else null)
                ?: call.getCurrentUserId()
            try {
                val updated = applyUnifiedUpdate(userId, request)
                call.respond(HttpStatusCode.OK, updated)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            }
        }

        put {
            val request = call.receive<UpdateUnifiedSettingRequest>()
            val tokenUserId = call.getVerifiedTokenUserId()
            val userId = tokenUserId
                ?: (if (isDemoModeEnabled()) request.userId?.let { parseDemoUserIdOrNull(it) } else null)
                ?: call.getCurrentUserId()
            try {
                val updated = applyUnifiedUpdate(userId, request)
                call.respond(HttpStatusCode.OK, updated)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            }
        }
    }

    route("/api/user/settings") {
        get {
            val userId = call.getCurrentUserId()
            val setting = userService.getUserSetting(userId)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = setting))
        }

        put {
            val userId = call.getCurrentUserId()
            val request = call.receive<UpdateUserSettingRequest>()
            val updated = userService.updateUserSetting(userId, request)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = updated))
        }
    }

    route("/api/professor/notification-settings") {
        get {
            val profId = call.getCurrentUserId()
            val setting = notificationService.getProfessorNotificationSetting(profId)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = setting))
        }

        put {
            val profId = call.getCurrentUserId()
            val request = call.receive<UpdateProfessorNotificationSettingRequest>()
            val updated = notificationService.updateProfessorNotificationSetting(profId, request)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = updated))
        }
    }
}
