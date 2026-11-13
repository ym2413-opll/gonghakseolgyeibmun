package com.gonghak

import com.gonghak.config.DatabaseFactory
import com.gonghak.routes.*
import com.gonghak.service.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    // 1. Initialize Database
    DatabaseFactory.init(environment.config)

    // 2. Background Worker Coordinator & Services
    val workerManager = BackgroundWorkerManager()
    val similarityService = SimilarityService()
    val emailService = EmailService(workerManager)
    val notificationService = NotificationService(emailService, workerManager)
    val geminiApiKey = environment.config.propertyOrNull("gemini.apiKey")?.getString()
    val aiRefineService = AiRefineService(geminiApiKey, workerManager)
    val userService = UserService()
    val attachmentService = AttachmentService(workerManager = workerManager)
    val questionService = QuestionService(similarityService, notificationService, aiRefineService, workerManager)

    // Start background schedulers (DND notification release, orphan attachment cleanup)
    workerManager.startSchedulers(notificationService, attachmentService)

    // Graceful shutdown hook for worker thread pools, schedulers, and HTTP clients
    environment.monitor.subscribe(ApplicationStopped) {
        workerManager.shutdown()
        aiRefineService.close()
    }

    // Start interactive server console listener for admin grant commands (`admin <id>`)
    AdminConsoleManager.startConsoleListener(userService)

    // 3. Plugins
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-User-Id")
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
    }

    install(CallLogging) {
        level = Level.INFO
    }

    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Referrer-Policy", "strict-origin-when-cross-origin")
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                com.gonghak.dto.ErrorResponse(success = false, message = cause.message ?: "잘못된 요청입니다.")
            )
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(
                HttpStatusCode.Forbidden,
                com.gonghak.dto.ErrorResponse(success = false, message = cause.message ?: "권한이 없습니다.")
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception: ${cause.message}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                com.gonghak.dto.ErrorResponse(success = false, message = "서버 내부 오류가 발생했습니다.")
            )
        }
    }

    // 4. Routing
    routing {
        get("/") {
            call.respondText(
                """
                |=== 교수질문센터 학업 포럼 백엔드 API 서버 (Ktor + Exposed, r3) ===
                |API 엔드포인트:
                | - POST /api/auth/register
                | - POST /api/auth/login
                | - GET  /api/auth/me
                | - GET  /api/departments
                | - GET  /api/courses
                | - GET  /api/admin/users
                | - POST /api/admin/users/{id}/sanction
                | - POST /api/admin/departments
                | - POST /api/admin/courses
                | - PATCH /api/admin/questions/{id}/hide
                | - PATCH /api/admin/answers/{id}/hide
                | - GET  /api/questions
                | - POST /api/questions
                | - GET  /api/questions/{id}
                | - POST /api/questions/{id}/answers
                | - GET  /api/notifications
                | - GET  /api/notifications/emails
                """.trimMargin(),
                ContentType.Text.Plain.withCharset(Charsets.UTF_8)
            )
        }

        authRoutes(userService)
        userSettingRoutes(userService, notificationService)
        courseRoutes(userService)
        aiRoutes(aiRefineService)
        questionRoutes(questionService)
        notificationRoutes(notificationService, emailService, userService)
        attachmentRoutes(attachmentService)
        adminRoutes(userService, questionService, emailService)
    }
}
