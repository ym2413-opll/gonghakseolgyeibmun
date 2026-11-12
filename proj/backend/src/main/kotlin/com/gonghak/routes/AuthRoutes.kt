package com.gonghak.routes

import com.gonghak.dto.ApiResponse
import com.gonghak.dto.LoginRequest
import com.gonghak.dto.RegisterRequest
import com.gonghak.service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(userService: UserService) {
    route("/api/auth") {
        // Register new student or professor account
        post("/register") {
            val request = call.receive<RegisterRequest>()
            try {
                val authRes = userService.registerUser(request)
                call.respond(HttpStatusCode.Created, ApiResponse(success = true, data = authRes, message = "회원가입이 완료되었습니다."))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = e.message))
            }
        }

        // Login with username and password
        post("/login") {
            val request = call.receive<LoginRequest>()
            try {
                val authRes = userService.loginUser(request)
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = authRes, message = "로그인되었습니다."))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.Unauthorized, ApiResponse<String>(success = false, message = e.message))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = e.message))
            }
        }

        // List demo accounts (for legacy test compatibility)
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
