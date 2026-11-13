package com.gonghak.routes

import com.gonghak.dto.ApiResponse
import com.gonghak.dto.RefineQuestionRequest
import com.gonghak.dto.RefineQuestionResponse
import com.gonghak.service.AiRefineService
import com.gonghak.service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.aiRoutes(
    aiRefineService: AiRefineService,
    userService: UserService = UserService()
) {
    val aiRateLimiter = SlidingWindowRateLimiter(maxRequests = 20, windowMillis = 60_000L)

    route("/api/ai") {
        post("/refine") {
            val user = call.requireAuthenticatedUser(userService) ?: return@post
            if (!aiRateLimiter.allow("${user.id}|${call.clientRateLimitKey()}")) {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiResponse<String>(success = false, message = "AI 문장 윤색 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.")
                )
                return@post
            }

            val request = call.receive<RefineQuestionRequest>()
            val contentToRefine = request.effectiveContent
            val titleToRefine = request.effectiveTitle

            if (contentToRefine.isBlank() && titleToRefine.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse<String>(success = false, message = "질문 내용을 입력해 주세요.")
                )
                return@post
            }

            val result = aiRefineService.refineQuestion(
                rawContent = contentToRefine.ifBlank { titleToRefine ?: "" },
                rawTitle = titleToRefine,
                courseName = request.courseName,
                category = request.category,
                professorName = request.effectiveProfessorName,
                studentName = request.effectiveStudentName,
                studentDepartment = request.effectiveStudentDepartment,
                studentIdentifier = request.effectiveStudentIdentifier
            )

            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    success = true,
                    data = RefineQuestionResponse(
                        original = contentToRefine,
                        refined = result.content,
                        rawContent = contentToRefine,
                        refinedContent = result.content,
                        originalTitle = titleToRefine,
                        refinedTitle = result.title,
                        politenessScore = 98,
                        changes = listOf(
                            "원문 문장을 직접 인용하지 않고 정중한 학술 문장으로 전면 재구성",
                            "질문 제목을 명확하고 품격 있는 학업 문의 양식으로 윤색",
                            "경어체 및 대학 질의응답 표준 예절 적용"
                        )
                    )
                )
            )
        }
    }
}
