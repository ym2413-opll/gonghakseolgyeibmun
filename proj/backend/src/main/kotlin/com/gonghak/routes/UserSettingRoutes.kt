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
    // 1. Unified /api/settings endpoint for frontend integration
    route("/api/settings") {
        get {
            val userId = call.getCurrentUserId()
            val userSetting = userService.getUserSetting(userId)
            val profSetting = notificationService.getProfessorNotificationSetting(userId)
            val categoriesMap = userService.getProfessorActiveCategoriesMap(userId)

            val combined = UnifiedSettingDto(
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
                activeCategories = categoriesMap
            )
            call.respond(HttpStatusCode.OK, combined)
        }

        post {
            val request = call.receive<UpdateUnifiedSettingRequest>()
            val userId = request.userId?.let { parseUserId(it) } ?: call.getCurrentUserId()

            // Update user theme/font settings
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

            // Update professor settings if provided
            if (request.dndEnabled != null || request.dndStartTime != null || request.dndEndTime != null || request.coProfNotification != null) {
                notificationService.updateProfessorNotificationSetting(
                    userId,
                    UpdateProfessorNotificationSettingRequest(
                        quietHoursEnabled = request.dndEnabled,
                        quietHoursStart = request.dndStartTime,
                        quietHoursEnd = request.dndEndTime,
                        notifyOtherProfessorQuestions = request.coProfNotification
                    )
                )
            }

            // Update category settings if provided
            if (request.activeCategories != null) {
                userService.updateProfessorActiveCategoriesMap(userId, request.activeCategories)
            }

            val userSetting = userService.getUserSetting(userId)
            val profSetting = notificationService.getProfessorNotificationSetting(userId)
            val categoriesMap = userService.getProfessorActiveCategoriesMap(userId)

            val updated = UnifiedSettingDto(
                userId = userId.toString(),
                theme = if (userSetting.themeMode == "system") "light" else userSetting.themeMode,
                themeMode = userSetting.themeMode,
                fontSize = if (userSetting.fontSize in listOf("sm", "md", "lg", "xl")) userSetting.fontSize else "md",
                dndEnabled = profSetting.quietHoursEnabled,
                dndStartTime = profSetting.quietHoursStart,
                dndEndTime = profSetting.quietHoursEnd,
                coProfNotification = profSetting.notifyOtherProfessorQuestions,
                activeCategories = categoriesMap
            )
            call.respond(HttpStatusCode.OK, updated)
        }

        put {
            val request = call.receive<UpdateUnifiedSettingRequest>()
            val userId = request.userId?.let { parseUserId(it) } ?: call.getCurrentUserId()

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

            if (request.dndEnabled != null || request.dndStartTime != null || request.dndEndTime != null || request.coProfNotification != null) {
                notificationService.updateProfessorNotificationSetting(
                    userId,
                    UpdateProfessorNotificationSettingRequest(
                        quietHoursEnabled = request.dndEnabled,
                        quietHoursStart = request.dndStartTime,
                        quietHoursEnd = request.dndEndTime,
                        notifyOtherProfessorQuestions = request.coProfNotification
                    )
                )
            }

            if (request.activeCategories != null) {
                userService.updateProfessorActiveCategoriesMap(userId, request.activeCategories)
            }

            val userSetting = userService.getUserSetting(userId)
            val profSetting = notificationService.getProfessorNotificationSetting(userId)
            val categoriesMap = userService.getProfessorActiveCategoriesMap(userId)

            val updated = UnifiedSettingDto(
                userId = userId.toString(),
                theme = if (userSetting.themeMode == "system") "light" else userSetting.themeMode,
                themeMode = userSetting.themeMode,
                fontSize = if (userSetting.fontSize in listOf("sm", "md", "lg", "xl")) userSetting.fontSize else "md",
                dndEnabled = profSetting.quietHoursEnabled,
                dndStartTime = profSetting.quietHoursStart,
                dndEndTime = profSetting.quietHoursEnd,
                coProfNotification = profSetting.notifyOtherProfessorQuestions,
                activeCategories = categoriesMap
            )
            call.respond(HttpStatusCode.OK, updated)
        }
    }

    // 2. Original granular endpoints
    route("/api/user/settings") {
        get {
            val userId = call.getCurrentUserId()
            val settings = userService.getUserSetting(userId)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = settings))
        }

        put {
            val userId = call.getCurrentUserId()
            val request = call.receive<UpdateUserSettingRequest>()
            val updated = userService.updateUserSetting(userId, request)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = updated))
        }
    }

    route("/api/professor/settings") {
        get {
            val profId = call.getCurrentUserId()
            val settings = notificationService.getProfessorNotificationSetting(profId)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = settings))
        }

        put {
            val profId = call.getCurrentUserId()
            val request = call.receive<UpdateProfessorNotificationSettingRequest>()
            val updated = notificationService.updateProfessorNotificationSetting(profId, request)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = updated))
        }
    }
}
