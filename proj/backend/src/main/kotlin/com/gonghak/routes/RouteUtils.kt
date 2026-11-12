package com.gonghak.routes

import com.gonghak.service.PasswordSecurity
import io.ktor.server.application.*
import java.util.UUID

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

fun isDemoModeEnabled(): Boolean =
    System.getenv("ENABLE_DEMO_USERS")?.lowercase() != "false"

fun parseDemoUserIdOrNull(input: String?): UUID? {
    if (input.isNullOrBlank()) return null
    val trimmed = input.trim()
    DEMO_USER_ID_MAP[trimmed.lowercase()]?.let { return it }
    val parsed = runCatching { UUID.fromString(trimmed) }.getOrNull() ?: return null
    return if (parsed in DEMO_USER_UUIDS) parsed else null
}

fun parseUserId(input: String?): UUID {
    if (input.isNullOrBlank()) return DEFAULT_STUDENT_ID
    val trimmed = input.trim()
    PasswordSecurity.verifyAuthToken(trimmed)?.let { return it }
    parseDemoUserIdOrNull(trimmed)?.let { return it }
    return DEFAULT_STUDENT_ID
}

fun parseCourseId(input: String?): UUID {
    if (input.isNullOrBlank()) return DEFAULT_COURSE_ID
    val trimmed = input.trim()
    DEMO_COURSE_ID_MAP[trimmed.lowercase()]?.let { return it }
    return try {
        UUID.fromString(trimmed)
    } catch (_: Exception) {
        DEFAULT_COURSE_ID
    }
}

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
 * Strictly extracts and verifies an HMAC-signed Bearer token.
 * Does NOT accept raw UUIDs, X-User-Id, or query parameters.
 */
fun ApplicationCall.getVerifiedTokenUserId(): UUID? {
    val authHeader = request.headers["Authorization"] ?: return null
    if (!authHeader.startsWith("Bearer ")) return null
    val token = authHeader.removePrefix("Bearer ").trim()
    return PasswordSecurity.verifyAuthToken(token)
}

fun ApplicationCall.getCurrentUserId(): UUID {
    // 1. Always prefer cryptographically signed Bearer token
    getVerifiedTokenUserId()?.let { return it }

    // 2. Demo fixture compatibility only (never allows spoofing registered/admin accounts)
    if (isDemoModeEnabled()) {
        parseDemoUserIdOrNull(request.headers["X-User-Id"])?.let { return it }
        parseDemoUserIdOrNull(request.queryParameters["userId"])?.let { return it }
    }

    return DEFAULT_STUDENT_ID
}
