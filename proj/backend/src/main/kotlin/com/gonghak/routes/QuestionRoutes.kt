package com.gonghak.routes

import com.gonghak.dto.*
import com.gonghak.models.QuestionCategory
import com.gonghak.service.QuestionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.questionRoutes(questionService: QuestionService) {
    route("/api/questions") {
        // List questions
        get {
            val userId = call.getCurrentUserId()
            val courseIdStr = call.request.queryParameters["courseId"]
            val categoryStr = call.request.queryParameters["category"]

            val courseIdFilter = courseIdStr?.let { parseCourseId(it) }
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

            val courseId = parseCourseId(courseIdStr)
            val excludeId = call.request.queryParameters["excludeId"]?.let { parseQuestionId(it) }

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
            val request = call.receive<SimilarQuestionRequest>()
            if (request.category == QuestionCategory.GRADE) {
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = emptyList<SimilarQuestionDto>()))
                return@post
            }

            val courseId = request.courseId?.let { parseCourseId(it) } ?: DEFAULT_COURSE_ID
            val excludeId = request.excludeId?.let { parseQuestionId(it) }

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
            val userId = call.getCurrentUserId()
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
            val userId = call.getCurrentUserId()
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
            val userId = call.getCurrentUserId()
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

        // Post answer
        post("/{id}/answers") {
            val profId = call.getCurrentUserId()
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
    }
}
