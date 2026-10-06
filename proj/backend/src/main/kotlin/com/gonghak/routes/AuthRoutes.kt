package com.gonghak.routes

import com.gonghak.dto.ApiResponse
import com.gonghak.service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(userService: UserService) {
    route("/api/auth") {
        // List demo accounts for exhibition
        get("/users") {
            val users = userService.getAllDemoUsers()
            call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = users))
        }

        // Current user info
        get("/me") {
            val currentUserId = call.getCurrentUserId()
            val user = userService.getUserById(currentUserId)
            if (user != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = user))
            } else {
                call.respond(HttpStatusCode.NotFound, ApiResponse<String>(success = false, message = "사용자를 찾을 수 없습니다."))
            }
        }
    }
}
