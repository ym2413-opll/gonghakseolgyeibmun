package com.gonghak.routes

import com.gonghak.dto.ApiResponse
import com.gonghak.dto.RefineQuestionRequest
import com.gonghak.dto.RefineQuestionResponse
import com.gonghak.service.AiRefineService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.aiRoutes(aiRefineService: AiRefineService) {
    route("/api/ai") {
        post("/refine") {
            val request = call.receive<RefineQuestionRequest>()
            val contentToRefine = request.effectiveContent
            if (contentToRefine.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse<String>(success = false, message = "질문 원문을 입력해 주세요.")
                )
                return@post
            }

            val refined = aiRefineService.refineQuestion(
                rawContent = contentToRefine,
                courseName = request.courseName,
                category = request.category
            )

            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    success = true,
                    data = RefineQuestionResponse(
                        original = contentToRefine,
                        refined = refined,
                        rawContent = contentToRefine,
                        refinedContent = refined
                    )
                )
            )
        }
    }
}
