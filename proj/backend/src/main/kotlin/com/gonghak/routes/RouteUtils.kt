package com.gonghak.routes

import com.gonghak.dto.ApiResponse
import com.gonghak.dto.UserDto
import com.gonghak.models.UserRole
import com.gonghak.service.PasswordSecurity
import com.gonghak.service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val AUTH_COOKIE_NAME = "cistus_auth_token"

val DEMO_USER_ID_MAP = mapOf(
    "user-stu-001" to UUID.fromString("11111111-1111-1111-1111-111111111101"),
    "user-stu-002" to UUID.fromString("11111111-1111-1111-1111-111111111102"),
    "user-stu-003" to UUID.fromString("11111111-1111-1111-1111-111111111103"),
    "user-prof-001" to UUID.fromString("22222222-2222-2222-2222-222222222201"),
    "user-prof-002" to UUID.fromString("22222222-2222-2222-2222-222222222202"),
    "user-prof-003" to UUID.fromString("22222222-2222-2222-2222-222222222203")
)

private val DEMO_USER_UUIDS: Set<UUID> = DEMO_USER_ID_MAP.values.toSet()

val DEMO_COURSE_ID_MAP = mapOf(
    "course-001" to UUID.fromString("33333333-3333-3333-3333-333333333301"),
    "course-002" to UUID.fromString("33333333-3333-3333-3333-333333333302"),
    "course-003" to UUID.fromString("33333333-3333-3333-3333-333333333302")
)

val DEMO_QUESTION_ID_MAP = mapOf(
    "q-001" to UUID.fromString("44444444-4444-4444-4444-444444444401"),
    "q-002" to UUID.fromString("44444444-4444-4444-4444-444444444402")
)

val DEFAULT_STUDENT_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111101")
val DEFAULT_COURSE_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333301")

/**
 * Demo user header/list mode is DISABLED by default for security and must be explicitly
 * enabled with ENABLE_DEMO_USERS=true.
 */
fun isDemoModeEnabled(): Boolean =
    System.getenv("ENABLE_DEMO_USERS")?.trim()?.equals("true", ignoreCase = true) == true

fun parseDemoUserIdOrNull(input: String?): UUID? {
    if (input.isNullOrBlank()) return null
    val trimmed = input.trim()
    DEMO_USER_ID_MAP[trimmed.lowercase()]?.let { return it }
    val parsed = runCatching { UUID.fromString(trimmed) }.getOrNull() ?: return null
    return if (parsed in DEMO_USER_UUIDS) parsed else null
}

fun parseUserIdOrNull(input: String?): UUID? {
    if (input.isNullOrBlank()) return null
    val trimmed = input.trim()
    PasswordSecurity.verifyAuthToken(trimmed)?.let { return it }
    parseDemoUserIdOrNull(trimmed)?.let { return it }
    return runCatching { UUID.fromString(trimmed) }.getOrNull()
}

fun parseCourseIdOrNull(input: String?): UUID? {
    if (input.isNullOrBlank()) return null
    val trimmed = input.trim()
    DEMO_COURSE_ID_MAP[trimmed.lowercase()]?.let { return it }
    return runCatching { UUID.fromString(trimmed) }.getOrNull()
}

fun parseCourseIdStrict(input: String?): UUID {
    if (input.isNullOrBlank()) {
        throw IllegalArgumentException("강의 ID가 필요합니다.")
    }
    return parseCourseIdOrNull(input)
        ?: throw IllegalArgumentException("유효하지 않은 강의 ID 형식입니다: $input")
}

fun parseCourseId(input: String?): UUID = parseCourseIdStrict(input)

fun parseQuestionId(input: String?): UUID {
    if (input.isNullOrBlank()) {
        throw IllegalArgumentException("질문 ID가 필요합니다.")
    }
    val trimmed = input.trim()
    DEMO_QUESTION_ID_MAP[trimmed.lowercase()]?.let { return it }
    return try {
        UUID.fromString(trimmed)
    } catch (_: Exception) {
        throw IllegalArgumentException("유효하지 않은 질문 ID 형식입니다: $input")
    }
}

/**
 * Extracts and cryptographically verifies an HMAC-signed token from either the
 * Authorization: Bearer header or the HttpOnly session cookie.
 */
fun ApplicationCall.getVerifiedTokenClaims(): PasswordSecurity.VerifiedAuthToken? {
    val authHeader = request.headers["Authorization"]
    val bearerToken = if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
        authHeader.substring(7).trim().takeIf { it.isNotEmpty() }
    } else {
        null
    }
    val rawToken = bearerToken ?: request.cookies[AUTH_COOKIE_NAME]?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    return PasswordSecurity.verifyAuthTokenClaims(rawToken)
}

fun ApplicationCall.getVerifiedTokenUserId(): UUID? = getVerifiedTokenClaims()?.userId

private fun ApplicationCall.isSecureTransport(): Boolean {
    val forwardedProto = request.headers["X-Forwarded-Proto"]?.trim()
    if (!forwardedProto.isNullOrEmpty()) {
        return forwardedProto.equals("https", ignoreCase = true)
    }
    return request.origin.scheme.equals("https", ignoreCase = true)
}

fun ApplicationCall.setAuthCookie(token: String) {
    response.cookies.append(
        Cookie(
            name = AUTH_COOKIE_NAME,
            value = token,
            httpOnly = true,
            secure = isSecureTransport(),
            path = "/api",
            maxAge = 7 * 24 * 3600,
            extensions = mapOf("SameSite" to "Lax")
        )
    )
}

fun ApplicationCall.clearAuthCookie() {
    response.cookies.append(
        Cookie(
            name = AUTH_COOKIE_NAME,
            value = "",
            httpOnly = true,
            secure = isSecureTransport(),
            path = "/api",
            maxAge = 0,
            extensions = mapOf("SameSite" to "Lax")
        )
    )
}

/**
 * Requires an active, non-withdrawn authenticated user via HMAC token (or explicit demo header
 * strictly when ENABLE_DEMO_USERS=true). Responds 401/403 and returns null if unauthorized.
 */
suspend fun ApplicationCall.requireAuthenticatedUser(
    userService: UserService,
    allowDemoHeader: Boolean = true
): UserDto? {
    val claims = getVerifiedTokenClaims()
    if (claims != null) {
        val activeUser = userService.getActiveAuthenticatedUser(claims.userId, claims.tokenVersion)
        if (activeUser == null) {
            respond(
                HttpStatusCode.Unauthorized,
                ApiResponse<String>(success = false, message = "인증이 만료되었거나 유효하지 않습니다. 다시 로그인해 주세요.")
            )
            return null
        }
        return activeUser
    }

    if (allowDemoHeader && isDemoModeEnabled()) {
        val demoUserId = parseDemoUserIdOrNull(request.headers["X-User-Id"])
            ?: parseDemoUserIdOrNull(request.queryParameters["userId"])
        if (demoUserId != null) {
            val demoUser = userService.getActiveAuthenticatedUser(demoUserId, 0)
            if (demoUser != null) return demoUser
        }
    }

    respond(
        HttpStatusCode.Unauthorized,
        ApiResponse<String>(success = false, message = "로그인이 필요한 기능입니다.")
    )
    return null
}

suspend fun ApplicationCall.requireProfessorOrAdmin(userService: UserService): UserDto? {
    val user = requireAuthenticatedUser(userService) ?: return null
    val isAllowed = user.role == UserRole.PROFESSOR || user.role == UserRole.ADMIN || user.isAdmin
    if (!isAllowed) {
        respond(
            HttpStatusCode.Forbidden,
            ApiResponse<String>(success = false, message = "교수자 또는 관리자 권한이 필요합니다.")
        )
        return null
    }
    return user
}

fun ApplicationCall.clientRateLimitKey(): String {
    val remote = request.local.remoteAddress
    val forwardedFor = if (remote == "127.0.0.1" || remote == "::1" || remote == "0:0:0:0:0:0:0:1") {
        request.headers["X-Real-IP"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        null
    }
    return forwardedFor ?: remote
}

class SlidingWindowRateLimiter(
    private val maxRequests: Int,
    private val windowMillis: Long
) {
    private val buckets = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun allow(key: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val queue = buckets.computeIfAbsent(key) { ArrayDeque() }
        synchronized(queue) {
            val cutoff = nowMillis - windowMillis
            while (queue.isNotEmpty() && queue.first() <= cutoff) {
                queue.removeFirst()
            }
            if (queue.size >= maxRequests) {
                return false
            }
            queue.addLast(nowMillis)
            if (buckets.size > 4096) {
                // Opportunistic bound to avoid unbounded memory growth
                buckets.entries.removeIf { entry ->
                    synchronized(entry.value) {
                        entry.value.isEmpty() || entry.value.last() <= cutoff
                    }
                }
            }
            return true
        }
    }
}
