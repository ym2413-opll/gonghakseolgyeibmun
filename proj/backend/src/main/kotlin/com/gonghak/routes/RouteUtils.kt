package com.gonghak.routes

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

fun parseUserId(input: String?): UUID {
    if (input.isNullOrBlank()) return DEFAULT_STUDENT_ID
    val trimmed = input.trim()
    DEMO_USER_ID_MAP[trimmed.lowercase()]?.let { return it }
    return try {
        UUID.fromString(trimmed)
    } catch (_: Exception) {
        DEFAULT_STUDENT_ID
    }
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

fun ApplicationCall.getCurrentUserId(): UUID {
    // 1. Check X-User-Id header
    val headerUserId = request.headers["X-User-Id"]
    if (!headerUserId.isNullOrBlank()) {
        return parseUserId(headerUserId)
    }

    // 2. Check Authorization header (Bearer <UUID>)
    val authHeader = request.headers["Authorization"]
    if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ")) {
        val token = authHeader.removePrefix("Bearer ").trim()
        return parseUserId(token)
    }

    // 3. Check query parameter
    val queryUserId = request.queryParameters["userId"]
    if (!queryUserId.isNullOrBlank()) {
        return parseUserId(queryUserId)
    }

    // Default demo student if none specified: 26_소프트_서영민
    return DEFAULT_STUDENT_ID
}
