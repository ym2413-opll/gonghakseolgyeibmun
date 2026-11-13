package com.gonghak.routes

import com.gonghak.dto.*
import com.gonghak.models.UserRole
import com.gonghak.service.NotificationService
import com.gonghak.service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

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

    suspend fun applyUnifiedUpdate(user: UserDto, request: UpdateUnifiedSettingRequest): UnifiedSettingDto {
        val userId = UUID.fromString(user.id)
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

        val isProfessorOrAdmin = user.role == UserRole.PROFESSOR || user.role == UserRole.ADMIN || user.isAdmin
        if (isProfessorOrAdmin) {
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
        }

        return buildUnifiedSettings(userId)
    }

    route("/api/settings") {
        get {
            val user = call.requireAuthenticatedUser(userService) ?: return@get
            call.respond(HttpStatusCode.OK, buildUnifiedSettings(UUID.fromString(user.id)))
        }

        post {
            val user = call.requireAuthenticatedUser(userService) ?: return@post
            val request = call.receive<UpdateUnifiedSettingRequest>()
            try {
                val updated = applyUnifiedUpdate(user, request)
                call.respond(HttpStatusCode.OK, updated)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            }
        }

        put {
            val user = call.requireAuthenticatedUser(userService) ?: return@put
            val request = call.receive<UpdateUnifiedSettingRequest>()
            try {
                val updated = applyUnifiedUpdate(user, request)
                call.respond(HttpStatusCode.OK, updated)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            }
        }
    }

    route("/api/user/settings") {
        get {
            val user = call.requireAuthenticatedUser(userService) ?: return@get
            val setting = userService.getUserSetting(UUID.fromString(user.id))
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = setting))
        }

        put {
            val user = call.requireAuthenticatedUser(userService) ?: return@put
            val request = call.receive<UpdateUserSettingRequest>()
            val updated = userService.updateUserSetting(UUID.fromString(user.id), request)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = updated))
        }
    }

    route("/api/professor/notification-settings") {
        get {
            val prof = call.requireProfessorOrAdmin(userService) ?: return@get
            val setting = notificationService.getProfessorNotificationSetting(UUID.fromString(prof.id))
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = setting))
        }

        put {
            val prof = call.requireProfessorOrAdmin(userService) ?: return@put
            val request = call.receive<UpdateProfessorNotificationSettingRequest>()
            val updated = notificationService.updateProfessorNotificationSetting(UUID.fromString(prof.id), request)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = updated))
        }
    }
}
