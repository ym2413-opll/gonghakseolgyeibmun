package com.gonghak.routes

import com.gonghak.dto.ApiResponse
import com.gonghak.dto.UpdateCategorySettingRequest
import com.gonghak.service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.courseRoutes(userService: UserService) {
    route("/api/departments") {
        get {
            val departments = userService.getDepartments()
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = departments))
        }
    }

    route("/api/courses") {
        get {
            val isAll = call.request.queryParameters["all"]?.toBoolean() ?: false
            val query = call.request.queryParameters["query"]
            val college = call.request.queryParameters["college"]
            val dept = call.request.queryParameters["department"]

            if (isAll || query != null || college != null || dept != null) {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                val result = userService.searchCourses(query, college, dept, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = result))
            } else {
                val userId = call.getCurrentUserId()
                val courses = userService.getCoursesForUser(userId)
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = courses))
            }
        }

        get("/summary") {
            val summary = userService.getCoursesSummary()
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = summary))
        }

        get("/search") {
            val query = call.request.queryParameters["query"]
            val college = call.request.queryParameters["college"]
            val dept = call.request.queryParameters["department"]
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            val result = userService.searchCourses(query, college, dept, page, limit)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = result))
        }

        get("/{courseId}") {
            val courseIdParam = call.parameters["courseId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse<Unit>(success = false, message = "강의 ID가 필요합니다.")
            )
            val courseId = parseCourseId(courseIdParam)
            val course = userService.getCourseById(courseId)
            if (course != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = course))
            } else {
                call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(success = false, message = "강의를 찾을 수 없습니다."))
            }
        }

        get("/{courseId}/categories") {
            val profId = call.getCurrentUserId()
            val courseIdParam = call.parameters["courseId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse<Unit>(success = false, message = "강의 ID가 필요합니다.")
            )
            val courseId = parseCourseId(courseIdParam)
            val categories = userService.getProfessorCategories(profId, courseId)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = categories))
        }

        put("/{courseId}/categories") {
            val profId = call.getCurrentUserId()
            val courseIdParam = call.parameters["courseId"] ?: return@put call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse<Unit>(success = false, message = "강의 ID가 필요합니다.")
            )
            val courseId = parseCourseId(courseIdParam)
            val request = call.receive<UpdateCategorySettingRequest>()
            val updated = userService.updateCategorySetting(profId, courseId, request)
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = updated))
        }
    }
}
