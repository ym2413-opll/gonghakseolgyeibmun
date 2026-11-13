package com.gonghak.routes

import com.gonghak.dto.*
import com.gonghak.models.UserRole
import com.gonghak.service.EmailService
import com.gonghak.service.QuestionService
import com.gonghak.service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.adminRoutes(
    userService: UserService,
    questionService: QuestionService,
    emailService: EmailService
) {
    suspend fun ApplicationCall.requireAdmin(): UUID? {
        val activeUser = requireAuthenticatedUser(userService, allowDemoHeader = false) ?: return null
        if (!activeUser.isAdmin && activeUser.role != UserRole.ADMIN) {
            respond(
                HttpStatusCode.Forbidden,
                ApiResponse<String>(success = false, message = "관리자 권한이 필요한 기능입니다.")
            )
            return null
        }
        return UUID.fromString(activeUser.id)
    }

    route("/api/admin") {
        // NOTE: Admin privileges can ONLY be granted/revoked via the backend server terminal console (AdminConsoleManager).
        // Any HTTP/browser-based admin grant endpoint is intentionally omitted for security.

        // List all registered users for admin management
        get("/users") {
            call.requireAdmin() ?: return@get
            val users = userService.getAllUsersForAdmin()
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = users))
        }

        // Sanction user: WITHDRAW, RESTORE, BAN_QUESTION, UNBAN_QUESTION, BAN_ANSWER, UNBAN_ANSWER
        post("/users/{id}/sanction") {
            call.requireAdmin() ?: return@post
            val targetIdStr = call.parameters["id"]
            val targetUuid = runCatching { UUID.fromString(targetIdStr) }.getOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse<String>(success = false, message = "유효하지 않은 사용자 ID입니다.")
                )

            val request = call.receive<UserSanctionRequest>()
            try {
                val updated = userService.sanctionUser(targetUuid, request)
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = updated, message = "계정 상태가 변경되었습니다."))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            }
        }

        // Dynamic Department addition
        post("/departments") {
            call.requireAdmin() ?: return@post
            val request = call.receive<CreateDepartmentRequest>()
            try {
                val created = userService.createDepartment(request)
                call.respond(HttpStatusCode.Created, ApiResponse(success = true, data = created, message = "학과가 추가되었습니다."))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            }
        }

        // Dynamic Course addition
        post("/courses") {
            call.requireAdmin() ?: return@post
            val request = call.receive<CreateCourseRequest>()
            try {
                val created = userService.createCourse(request)
                call.respond(HttpStatusCode.Created, ApiResponse(success = true, data = created, message = "과목이 추가되었습니다."))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            }
        }

        // Hide / Unhide Question
        suspend fun handleQuestionHide(call: ApplicationCall) {
            val adminId = call.requireAdmin() ?: return
            val questionId = parseQuestionId(call.parameters["id"])
            val request = call.receive<ToggleHideRequest>()
            try {
                val updated = questionService.setQuestionHidden(questionId, request.isHidden, adminId)
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse(
                        success = true,
                        data = updated,
                        message = if (request.isHidden) "질문이 가리기 처리되었습니다." else "질문 가리기가 해제되었습니다."
                    )
                )
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, ApiResponse<String>(success = false, message = e.message))
            }
        }

        patch("/questions/{id}/hide") { handleQuestionHide(call) }
        post("/questions/{id}/hide") { handleQuestionHide(call) }

        // Hide / Unhide Answer
        suspend fun handleAnswerHide(call: ApplicationCall) {
            call.requireAdmin() ?: return
            val answerIdStr = call.parameters["id"]
            val answerId = runCatching { UUID.fromString(answerIdStr) }.getOrNull()
                ?: return call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse<String>(success = false, message = "유효하지 않은 답변 ID입니다.")
                )
            val request = call.receive<ToggleHideRequest>()
            try {
                val updated = questionService.setAnswerHidden(answerId, request.isHidden)
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse(
                        success = true,
                        data = updated,
                        message = if (request.isHidden) "답변이 가리기 처리되었습니다." else "답변 가리기가 해제되었습니다."
                    )
                )
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, ApiResponse<String>(success = false, message = e.message))
            }
        }

        patch("/answers/{id}/hide") { handleAnswerHide(call) }
        post("/answers/{id}/hide") { handleAnswerHide(call) }

        // Delete question or answer permanently (Admin only)
        delete("/questions/{id}") {
            call.requireAdmin() ?: return@delete
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(success = false, message = "유효한 질문 ID가 아닙니다.")
                )
            questionService.deleteQuestion(id)
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    success = true,
                    message = "질문이 영구 삭제되었습니다.",
                    data = kotlinx.serialization.json.JsonObject(
                        mapOf(
                            "deleted" to kotlinx.serialization.json.JsonPrimitive(true),
                            "questionId" to kotlinx.serialization.json.JsonPrimitive(id.toString())
                        )
                    )
                )
            )
        }

        delete("/answers/{id}") {
            call.requireAdmin() ?: return@delete
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(success = false, message = "유효한 답변 ID가 아닙니다.")
                )
            val questionId = questionService.deleteAnswer(id)
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    success = true,
                    message = "답변이 삭제되었습니다.",
                    data = kotlinx.serialization.json.JsonObject(
                        mapOf(
                            "deleted" to kotlinx.serialization.json.JsonPrimitive(true),
                            "answerId" to kotlinx.serialization.json.JsonPrimitive(id.toString()),
                            "questionId" to kotlinx.serialization.json.JsonPrimitive(questionId.toString())
                        )
                    )
                )
            )
        }

        // View all email logs
        get("/emails") {
            call.requireAdmin() ?: return@get
            val logs = emailService.getEmailLogs(isAdmin = true)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = logs))
        }
    }
}
