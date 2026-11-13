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
    val first = args.firstOrNull()
    if (first == "--admin" || first == "--admin-ctl") {
        val code = AdminConsoleManager.runCli(args.drop(1).toTypedArray())
        kotlin.system.exitProcess(code)
    }
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
    val questionService = QuestionService(
        similarityService = similarityService,
        notificationService = notificationService,
        aiRefineService = aiRefineService,
        workerManager = workerManager,
        attachmentService = attachmentService
    )

    // Start background schedulers (DND notification release, orphan attachment cleanup)
    workerManager.startSchedulers(notificationService, attachmentService)

    // Graceful shutdown hook for worker thread pools, schedulers, and HTTP clients
    environment.monitor.subscribe(ApplicationStopped) {
        AdminConsoleManager.stopConsoleListener()
        workerManager.shutdown()
        aiRefineService.close()
    }

    // Start interactive server console + loopback admin socket listener (`admin <id>`)
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
        val rawOrigins = System.getenv("CORS_ALLOWED_ORIGINS")?.trim().orEmpty()
        val isProd = System.getenv("PRODUCTION_MODE")?.trim()?.equals("true", ignoreCase = true) == true
        val configuredOrigins = rawOrigins.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (configuredOrigins.any { it == "*" }) {
            anyHost()
        } else {
            val originsToAllow = if (configuredOrigins.isNotEmpty()) {
                configuredOrigins
            } else if (!isProd) {
                listOf(
                    "http://localhost:5173",
                    "http://127.0.0.1:5173",
                    "http://localhost:3000",
                    "http://127.0.0.1:3000",
                    "http://localhost:4173",
                    "http://127.0.0.1:4173",
                    "http://localhost:8080",
                    "http://127.0.0.1:8080"
                )
            } else {
                emptyList()
            }
            originsToAllow.forEach { originUrl ->
                runCatching { java.net.URI(originUrl) }.getOrNull()?.let { uri ->
                    val scheme = uri.scheme ?: "http"
                    val host = uri.host ?: return@let
                    val hostPort = if (uri.port > 0) "$host:${uri.port}" else host
                    allowHost(hostPort, schemes = listOf(scheme))
                }
            }
            if (originsToAllow.isNotEmpty()) {
                allowCredentials = true
            }
        }
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        if (isDemoModeEnabled()) {
            allowHeader("X-User-Id")
        }
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
                | - DELETE /api/admin/questions/{id}
                | - DELETE /api/admin/answers/{id}
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
        aiRoutes(aiRefineService, userService)
        questionRoutes(questionService, userService)
        notificationRoutes(notificationService, emailService, userService)
        attachmentRoutes(attachmentService, userService)
        adminRoutes(userService, questionService, emailService)
    }
}
