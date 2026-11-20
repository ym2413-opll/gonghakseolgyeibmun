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

private suspend fun ApplicationCall.parseUuidParam(paramName: String, invalidMessage: String): UUID? {
    return runCatching { UUID.fromString(parameters[paramName]) }.getOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = invalidMessage))
        null
    }
}

private suspend inline fun <reified T> ApplicationCall.respondServiceCall(
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    successMessage: String? = null,
    block: () -> T
) {
    try {
        val data = block()
        respond(successStatus, ApiResponse(success = true, data = data, message = successMessage))
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
    }
}

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

        // Update admin display nickname (blank resets to default name/tag)
        put("/nickname") {
            val adminId = call.requireAdmin() ?: return@put
            val request = call.receive<UpdateAdminNicknameRequest>()
            try {
                val updated = userService.updateAdminNickname(adminId, request.nickname)
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse(
                        success = true,
                        data = updated,
                        message = if (!updated.nickname.isNullOrBlank()) {
                            "관리자 닉네임이 '${updated.nickname}'(으)로 저장되었습니다."
                        } else {
                            "관리자 닉네임이 기본값으로 초기화되었습니다."
                        }
                    )
                )
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = e.message))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = e.message))
            }
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

        // Dynamic Department addition & editing
        post("/departments") {
            call.requireAdmin() ?: return@post
            val request = call.receive<CreateDepartmentRequest>()
            call.respondServiceCall(HttpStatusCode.Created, "학과가 추가되었습니다.") {
                userService.createDepartment(request)
            }
        }

        put("/departments/{id}") {
            call.requireAdmin() ?: return@put
            val deptUuid = call.parseUuidParam("id", "유효하지 않은 학과 ID입니다.") ?: return@put
            val request = call.receive<UpdateDepartmentRequest>()
            call.respondServiceCall(HttpStatusCode.OK, "학과 정보가 수정되었습니다.") {
                userService.updateDepartment(deptUuid, request)
            }
        }

        post("/departments/{id}/aliases") {
            call.requireAdmin() ?: return@post
            val deptUuid = call.parseUuidParam("id", "유효하지 않은 학과 ID입니다.") ?: return@post
            val request = call.receive<DepartmentAliasRequest>()
            call.respondServiceCall(HttpStatusCode.Created, "학과 별칭이 추가되었습니다.") {
                userService.addDepartmentAlias(deptUuid, request.alias)
            }
        }

        put("/departments/{id}/aliases") {
            call.requireAdmin() ?: return@put
            val deptUuid = call.parseUuidParam("id", "유효하지 않은 학과 ID입니다.") ?: return@put
            val request = call.receive<RenameAliasRequest>()
            call.respondServiceCall(HttpStatusCode.OK, "학과 별칭이 변경되었습니다.") {
                userService.renameDepartmentAlias(deptUuid, request.oldAlias, request.newAlias)
            }
        }

        delete("/departments/{id}/aliases") {
            call.requireAdmin() ?: return@delete
            val deptUuid = call.parseUuidParam("id", "유효하지 않은 학과 ID입니다.") ?: return@delete
            val aliasParam = call.request.queryParameters["alias"].orEmpty()
            call.respondServiceCall(HttpStatusCode.OK, "학과 별칭이 삭제되었습니다.") {
                userService.removeDepartmentAlias(deptUuid, aliasParam)
            }
        }

        // Dynamic College addition, editing, & alias management
        post("/colleges") {
            call.requireAdmin() ?: return@post
            val request = call.receive<CreateCollegeRequest>()
            call.respondServiceCall(HttpStatusCode.Created, "단과대학이 추가되었습니다.") {
                userService.createCollege(request)
            }
        }

        put("/colleges/{id}") {
            call.requireAdmin() ?: return@put
            val collegeUuid = call.parseUuidParam("id", "유효하지 않은 단과대학 ID입니다.") ?: return@put
            val request = call.receive<UpdateCollegeRequest>()
            call.respondServiceCall(HttpStatusCode.OK, "단과대학 정보가 수정되었습니다.") {
                userService.updateCollege(collegeUuid, request)
            }
        }

        post("/colleges/{id}/aliases") {
            call.requireAdmin() ?: return@post
            val collegeUuid = call.parseUuidParam("id", "유효하지 않은 단과대학 ID입니다.") ?: return@post
            val request = call.receive<DepartmentAliasRequest>()
            call.respondServiceCall(HttpStatusCode.Created, "단과대학 별칭이 추가되었습니다.") {
                userService.addCollegeAlias(collegeUuid, request.alias)
            }
        }

        put("/colleges/{id}/aliases") {
            call.requireAdmin() ?: return@put
            val collegeUuid = call.parseUuidParam("id", "유효하지 않은 단과대학 ID입니다.") ?: return@put
            val request = call.receive<RenameAliasRequest>()
            call.respondServiceCall(HttpStatusCode.OK, "단과대학 별칭이 변경되었습니다.") {
                userService.renameCollegeAlias(collegeUuid, request.oldAlias, request.newAlias)
            }
        }

        delete("/colleges/{id}/aliases") {
            call.requireAdmin() ?: return@delete
            val collegeUuid = call.parseUuidParam("id", "유효하지 않은 단과대학 ID입니다.") ?: return@delete
            val aliasParam = call.request.queryParameters["alias"].orEmpty()
            call.respondServiceCall(HttpStatusCode.OK, "단과대학 별칭이 삭제되었습니다.") {
                userService.removeCollegeAlias(collegeUuid, aliasParam)
            }
        }

        // Dynamic Course addition, editing, & deletion
        post("/courses") {
            call.requireAdmin() ?: return@post
            val request = call.receive<CreateCourseRequest>()
            call.respondServiceCall(HttpStatusCode.Created, "과목이 추가되었습니다.") {
                userService.createCourse(request)
            }
        }

        put("/courses/{id}") {
            call.requireAdmin() ?: return@put
            val courseUuid = call.parseUuidParam("id", "유효하지 않은 과목 ID입니다.") ?: return@put
            val request = call.receive<UpdateCourseRequest>()
            call.respondServiceCall(HttpStatusCode.OK, "과목 정보가 수정되었습니다.") {
                userService.updateCourse(courseUuid, request)
            }
        }

        delete("/courses/{id}") {
            call.requireAdmin() ?: return@delete
            val courseUuid = call.parseUuidParam("id", "유효하지 않은 과목 ID입니다.") ?: return@delete
            try {
                val (deletedQuestions, attachmentNames) = userService.deleteCourse(courseUuid)
                questionService.purgeStoredFiles(attachmentNames)
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse(
                        success = true,
                        message = "과목이 삭제되었습니다.",
                        data = kotlinx.serialization.json.JsonObject(
                            mapOf(
                                "deleted" to kotlinx.serialization.json.JsonPrimitive(true),
                                "courseId" to kotlinx.serialization.json.JsonPrimitive(courseUuid.toString()),
                                "deletedQuestions" to kotlinx.serialization.json.JsonPrimitive(deletedQuestions)
                            )
                        )
                    )
                )
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
