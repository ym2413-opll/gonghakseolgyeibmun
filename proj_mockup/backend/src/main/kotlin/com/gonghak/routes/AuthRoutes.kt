package com.gonghak.routes

import com.gonghak.config.EnvConfig
import com.gonghak.dto.ApiResponse
import com.gonghak.dto.DemoLoginRequest
import com.gonghak.dto.LoginRequest
import com.gonghak.dto.RegisterRequest
import com.gonghak.dto.RegistrationPolicyDto
import com.gonghak.service.PasswordSecurity
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
    val nextIdIpLimiter = SlidingWindowRateLimiter(maxRequests = 60, windowMillis = 60_000L)

    route("/api/auth") {
        // Public registration policy (exposes non-sensitive requirements such as invite code need)
        get("/registration-policy") {
            val required = !EnvConfig.get("PROFESSOR_INVITE_CODE").isNullOrBlank()
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = RegistrationPolicyDto(professorInviteRequired = required)))
        }

        // Preview next auto-assigned / random 10-digit student ID: (YYYY)(1..8 + 0 + 1..6)(SSS)
        get("/next-student-id") {
            val ipKey = call.clientRateLimitKey()
            if (!nextIdIpLimiter.allow(ipKey)) {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiResponse<String>(success = false, message = "학번 조회 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.")
                )
                return@get
            }
            val year = call.request.queryParameters["year"]?.toIntOrNull() ?: 2026
            val isForeign = call.request.queryParameters["isForeign"]?.equals("true", ignoreCase = true) == true
            val random = call.request.queryParameters["random"]?.equals("true", ignoreCase = true) == true
            try {
                val res = userService.getNextStudentId(year, isForeign, random)
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = res))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            }
        }

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
            val accountKey = PasswordSecurity.sanitizeInput(request.username).lowercase()
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

        // List demo accounts for mockup dropdown switcher
        get("/users") {
            if (!isDemoModeEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = "프로덕션 환경에서는 계정 목록 조회가 비활성화되어 있습니다."))
                return@get
            }
            val users = userService.getAllDemoUsers().map { it.copy(identifier = "") }
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = users))
        }

        // Switch active mockup account directly without password
        post("/demo-login") {
            if (!isDemoModeEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = "목업 계정 전환이 비활성화되어 있습니다."))
                return@post
            }
            val request = call.receive<DemoLoginRequest>()
            val demoUuid = parseDemoUserIdOrNull(request.userId)
            if (demoUuid == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = "유효하지 않은 목업 계정입니다."))
                return@post
            }
            try {
                val authRes = userService.demoLogin(demoUuid)
                call.setAuthCookie(authRes.token)
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = authRes, message = "계정이 전환되었습니다."))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, ApiResponse<String>(success = false, message = e.message))
            }
        }

        // Current authenticated user info
        get("/me") {
            val user = call.requireAuthenticatedUser(userService) ?: return@get
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = user))
        }
    }
}
