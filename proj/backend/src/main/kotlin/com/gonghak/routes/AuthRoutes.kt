package com.gonghak.routes

import com.gonghak.dto.ApiResponse
import com.gonghak.service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(userService: UserService) {
    route("/api/auth") {
        // List demo accounts for exhibition/demo role switcher (can be disabled in production via ENABLE_DEMO_USERS=false)
        get("/users") {
            val enableDemoUsers = System.getenv("ENABLE_DEMO_USERS")?.lowercase() != "false"
            if (!enableDemoUsers) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = "프로덕션 환경에서는 계정 목록 조회가 비활성화되어 있습니다."))
                return@get
            }
            val users = userService.getAllDemoUsers().map { it.copy(identifier = "") }
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = users))
        }

        // Current user info
        get("/me") {
            val currentUserId = call.getCurrentUserId()
            val user = userService.getUserById(currentUserId)
            if (user != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = user))
            } else {
                call.respond(HttpStatusCode.NotFound, ApiResponse<String>(success = false, message = "사용자를 찾을 수 없습니다."))
            }
        }
    }
}
