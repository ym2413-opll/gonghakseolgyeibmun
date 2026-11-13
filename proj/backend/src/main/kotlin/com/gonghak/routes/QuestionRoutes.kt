package com.gonghak.routes

import com.gonghak.dto.*
import com.gonghak.models.QuestionCategory
import com.gonghak.service.QuestionService
import com.gonghak.service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.questionRoutes(
    questionService: QuestionService,
    userService: UserService = UserService()
) {
    route("/api/questions") {
        // List questions
        get {
            val user = call.requireAuthenticatedUser(userService) ?: return@get
            val userId = UUID.fromString(user.id)
            val courseIdStr = call.request.queryParameters["courseId"]
            val categoryStr = call.request.queryParameters["category"]

            val courseIdFilter = courseIdStr?.takeIf { it.isNotBlank() }?.let {
                parseCourseIdOrNull(it) ?: throw IllegalArgumentException("유효하지 않은 강의 ID입니다.")
            }
            val categoryFilter = categoryStr?.let {
                try {
                    QuestionCategory.valueOf(it.uppercase())
                } catch (_: Exception) {
                    null
                }
            }

            val list = questionService.getQuestions(
                currentUserId = userId,
                courseIdFilter = courseIdFilter,
                categoryFilter = categoryFilter
            )

            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = list))
        }

        // Similar questions lookup (GET query parameters)
        get("/similar") {
            call.requireAuthenticatedUser(userService) ?: return@get
            val courseIdStr = call.request.queryParameters["courseId"]
            val categoryStr = call.request.queryParameters["category"]
            val queryText = call.request.queryParameters["query"]

            if (courseIdStr.isNullOrBlank() || categoryStr.isNullOrBlank() || queryText.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse<String>(success = false, message = "courseId, category, query 파라미터가 모두 필요합니다.")
                )
                return@get
            }

            val category = try {
                QuestionCategory.valueOf(categoryStr.uppercase())
            } catch (_: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse<String>(success = false, message = "유효하지 않은 카테고리입니다.")
                )
                return@get
            }

            val courseId = parseCourseIdStrict(courseIdStr)
            val excludeId = call.request.queryParameters["excludeId"]?.takeIf { it.isNotBlank() }?.let { parseQuestionId(it) }

            val results = questionService.getSimilarQuestions(
                courseId = courseId,
                category = category,
                queryText = queryText,
                excludeQuestionId = excludeId
            )

            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = results))
        }

        // Similar questions lookup (POST JSON body for frontend api.js)
        post("/similar") {
            call.requireAuthenticatedUser(userService) ?: return@post
            val request = call.receive<SimilarQuestionRequest>()
            if (request.category == QuestionCategory.GRADE) {
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = emptyList<SimilarQuestionDto>()))
                return@post
            }

            val courseId = request.courseId?.takeIf { it.isNotBlank() }?.let { parseCourseIdStrict(it) } ?: DEFAULT_COURSE_ID
            val excludeId = request.excludeId?.takeIf { it.isNotBlank() }?.let { parseQuestionId(it) }

            val results = questionService.getSimilarQuestions(
                courseId = courseId,
                category = request.category,
                queryText = request.effectiveQuery,
                excludeQuestionId = excludeId
            )

            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = results))
        }

        // Create new question
        post {
            val user = call.requireAuthenticatedUser(userService) ?: return@post
            val userId = UUID.fromString(user.id)
            val request = call.receive<CreateQuestionRequest>()

            try {
                val created = questionService.createQuestion(userId, request)
                call.respond(HttpStatusCode.Created, ApiResponse(success = true, data = created))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = e.message))
            }
        }

        // Question detail
        get("/{id}") {
            val user = call.requireAuthenticatedUser(userService) ?: return@get
            val userId = UUID.fromString(user.id)
            val questionId = parseQuestionId(call.parameters["id"])

            try {
                val detail = questionService.getQuestionDetail(questionId, userId)
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = detail))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, ApiResponse<String>(success = false, message = e.message))
            }
        }

        // Update question
        put("/{id}") {
            val user = call.requireAuthenticatedUser(userService) ?: return@put
            val userId = UUID.fromString(user.id)
            val questionId = parseQuestionId(call.parameters["id"])
            val request = call.receive<UpdateQuestionRequest>()

            try {
                val updated = questionService.updateQuestion(questionId, userId, request)
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = updated))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = e.message))
            }
        }

        delete("/{id}") {
            val user = call.requireAuthenticatedUser(userService) ?: return@delete
            val userId = UUID.fromString(user.id)
            val questionId = parseQuestionId(call.parameters["id"])

            try {
                questionService.deleteQuestionByAuthor(questionId, userId)
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = true, message = "질문이 삭제되었습니다."))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, ApiResponse<String>(success = false, message = e.message))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = e.message))
            }
        }

        // Post answer
        post("/{id}/answers") {
            val user = call.requireAuthenticatedUser(userService) ?: return@post
            val profId = UUID.fromString(user.id)
            val questionId = parseQuestionId(call.parameters["id"])
            val request = call.receive<CreateAnswerRequest>()

            if (request.content.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse<String>(success = false, message = "답변 내용을 입력해 주세요.")
                )
                return@post
            }

            try {
                val createdAnswer = questionService.createAnswer(questionId, profId, request)
                call.respond(HttpStatusCode.Created, ApiResponse(success = true, data = createdAnswer))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = e.message))
            }
        }

        // Post student follow-up question in thread
        post("/{id}/followups") {
            val user = call.requireAuthenticatedUser(userService) ?: return@post
            val studentId = UUID.fromString(user.id)
            val questionId = parseQuestionId(call.parameters["id"])
            val request = call.receive<CreateFollowUpRequest>()

            if (request.content.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse<String>(success = false, message = "추가 질문 내용을 입력해 주세요.")
                )
                return@post
            }

            try {
                val createdFollowUp = questionService.createFollowUp(questionId, studentId, request)
                call.respond(HttpStatusCode.Created, ApiResponse(success = true, data = createdFollowUp))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = e.message))
            }
        }
    }
}
