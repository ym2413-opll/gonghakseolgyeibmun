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
    val loginIpLimiter = SlidingWindowRateLimiter(maxRequests = 30, windowMillis = 60_000L)
    val loginAccountLimiter = SlidingWindowRateLimiter(maxRequests = 12, windowMillis = 60_000L)
    val registerIpLimiter = SlidingWindowRateLimiter(maxRequests = 15, windowMillis = 60_000L)

    route("/api/auth") {
        // Register new student or professor account
        post("/register") {
            val ipKey = call.clientRateLimitKey()
            if (!registerIpLimiter.allow(ipKey)) {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiResponse<String>(success = false, message = "회원가입 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.")
                )
                return@post
            }
            val request = call.receive<RegisterRequest>()
            try {
                val authRes = userService.registerUser(request)
                call.setAuthCookie(authRes.token)
                call.respond(HttpStatusCode.Created, ApiResponse(success = true, data = authRes, message = "회원가입이 완료되었습니다."))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = e.message))
            }
        }

        // Login with username and password
        post("/login") {
            val ipKey = call.clientRateLimitKey()
            if (!loginIpLimiter.allow(ipKey)) {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiResponse<String>(success = false, message = "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.")
                )
                return@post
            }
            val request = call.receive<LoginRequest>()
            val accountKey = request.username.trim().lowercase()
            if (accountKey.isNotEmpty() && !loginAccountLimiter.allow(accountKey)) {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiResponse<String>(success = false, message = "해당 계정의 로그인 시도가 너무 많습니다. 1분 후 다시 시도해 주세요.")
                )
                return@post
            }
            try {
                val authRes = userService.loginUser(request)
                call.setAuthCookie(authRes.token)
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = authRes, message = "로그인되었습니다."))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.Unauthorized, ApiResponse<String>(success = false, message = e.message))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = e.message))
            }
        }

        // Logout and revoke current session tokens + clear HttpOnly cookie
        post("/logout") {
            val claims = call.getVerifiedTokenClaims()
            if (claims != null) {
                userService.revokeUserTokens(claims.userId)
            }
            call.clearAuthCookie()
            call.respond(HttpStatusCode.OK, ApiResponse<String>(success = true, message = "로그아웃되었습니다."))
        }

        // List demo accounts (only when explicitly enabled via ENABLE_DEMO_USERS=true)
        get("/users") {
            if (!isDemoModeEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = "프로덕션 환경에서는 계정 목록 조회가 비활성화되어 있습니다."))
                return@get
            }
            val users = userService.getAllDemoUsers().map { it.copy(identifier = "") }
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = users))
        }

        // Current authenticated user info
        get("/me") {
            val user = call.requireAuthenticatedUser(userService) ?: return@get
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = user))
        }
    }
}
