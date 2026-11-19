package com.gonghak.routes

import com.gonghak.config.DatabaseFactory
import com.gonghak.config.DatabaseFactory.dbQuery
import com.gonghak.dto.ApiResponse
import com.gonghak.dto.DirectiveHighlightDto
import com.gonghak.dto.GenerateFormulaRequest
import com.gonghak.dto.GenerateFormulaResponse
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
    val formulaRateLimiter = SlidingWindowRateLimiter(maxRequests = 30, windowMillis = 60_000L)

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
            if (
                contentToRefine.length > 20_000 ||
                (request.text?.length ?: 0) > 20_000 ||
                (request.rawContent?.length ?: 0) > 20_000 ||
                (titleToRefine?.length ?: 0) > 300 ||
                (request.title?.length ?: 0) > 300 ||
                (request.rawTitle?.length ?: 0) > 300 ||
                (request.previousAnswer?.length ?: 0) > 20_000 ||
                (request.courseName?.length ?: 0) > 200 ||
                (request.professorName?.length ?: 0) > 100 ||
                (request.studentName?.length ?: 0) > 100 ||
                (request.studentDepartment?.length ?: 0) > 100 ||
                (request.studentIdentifier?.length ?: 0) > 100 ||
                (request.effectiveStudentName?.length ?: 0) > 100 ||
                (request.effectiveStudentDepartment?.length ?: 0) > 100 ||
                (request.effectiveStudentIdentifier?.length ?: 0) > 100 ||
                (request.studentInfo?.size ?: 0) > 10 ||
                request.studentInfo?.entries?.any { (k, v) -> k.length > 100 || v.length > 100 } == true
            ) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse<String>(success = false, message = "AI 문장 윤색 입력 길이가 허용 한도를 초과했습니다.")
                )
                return@post
            }

            val isAnonymousRequest = request.isAnonymous
            val deptAliases = dbQuery {
                (listOfNotNull(user.department, user.displayTag)
                    .filter { it.isNotBlank() && it.length <= 100 }
                    .flatMap { DatabaseFactory.expandDepartmentAliasNames(it) })
                    .distinct()
            }
            val result = aiRefineService.refineQuestion(
                rawContent = contentToRefine.ifBlank { titleToRefine ?: "" },
                rawTitle = titleToRefine,
                courseName = request.courseName,
                category = request.category,
                professorName = request.effectiveProfessorName,
                studentName = user.name.takeIf { it.isNotBlank() } ?: if (isAnonymousRequest) null else request.effectiveStudentName,
                studentDepartment = user.department.takeIf { it.isNotBlank() } ?: if (isAnonymousRequest) null else request.effectiveStudentDepartment,
                studentIdentifier = user.identifier.takeIf { it.isNotBlank() } ?: if (isAnonymousRequest) null else request.effectiveStudentIdentifier,
                studentEmail = user.email,
                studentDepartmentAliases = deptAliases,
                isAnonymous = isAnonymousRequest,
                isFollowUp = request.isFollowUp,
                previousAnswer = request.previousAnswer,
                studentUsername = user.username
            )

            val changesList = buildList {
                if (result.processedDirectivesCount > 0) {
                    add("본문 내 [[지시사항]] ${result.processedDirectivesCount}건 AI 처리 및 대체")
                }
                add("원문 문장을 직접 인용하지 않고 정중한 학술 문장으로 전면 재구성")
                add("질문 제목을 명확하고 품격 있는 학업 문의 양식으로 윤색")
                add("경어체 및 대학 질의응답 표준 예절 적용")
            }

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
                        changes = changesList,
                        directiveHighlights = result.directiveHighlights.map {
                            DirectiveHighlightDto(
                                index = it.index,
                                directive = it.directive,
                                replacement = it.replacement,
                                start = it.start,
                                end = it.end
                            )
                        }
                    )
                )
            )
        }

        post("/formula") {
            val user = call.requireAuthenticatedUser(userService) ?: return@post
            if (!formulaRateLimiter.allow("${user.id}|${call.clientRateLimitKey()}")) {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiResponse<String>(success = false, message = "AI 수식 생성 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.")
                )
                return@post
            }

            val request = call.receive<GenerateFormulaRequest>()
            if (request.instruction.length > 1000 || request.displayMode.length > 20) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse<String>(success = false, message = "수식 설명 입력 길이가 허용 한도를 초과했습니다.")
                )
                return@post
            }
            val instruction = request.instruction.trim()
            if (instruction.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse<String>(success = false, message = "원하는 수식을 설명해 주세요.")
                )
                return@post
            }
            if (instruction.length > 500) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse<String>(success = false, message = "수식 설명은 500자 이내로 입력해 주세요.")
                )
                return@post
            }

            val mode = if (request.displayMode.equals("block", ignoreCase = true)) "block" else "inline"
            val result = aiRefineService.generateLatexFormula(instruction, mode)

            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    success = true,
                    data = GenerateFormulaResponse(
                        instruction = result.instruction,
                        latex = result.latex,
                        wrapped = result.wrapped,
                        displayMode = result.displayMode,
                        usedAi = result.usedAi
                    )
                )
            )
        }
    }
}
