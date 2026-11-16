package com.gonghak

import com.gonghak.config.DatabaseFactory
import com.gonghak.config.EnvConfig
import com.gonghak.routes.*
import com.gonghak.service.*
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerializationException
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
    val uploadDirPath = System.getProperty("gonghak.uploadDir")
        ?: environment.config.propertyOrNull("storage.uploadDir")?.getString()
        ?: EnvConfig.get("UPLOAD_DIR")
        ?: "uploads/attachments"
    val attachmentService = AttachmentService(
        uploadDirPath = uploadDirPath,
        workerManager = workerManager
    )
    val questionService = QuestionService(
        similarityService = similarityService,
        notificationService = notificationService,
        aiRefineService = aiRefineService,
        workerManager = workerManager,
        attachmentService = attachmentService
    )

    // Start background schedulers (DND notification release, orphan attachment cleanup)
    workerManager.startSchedulers(notificationService, attachmentService)

    // Graceful shutdown hook for worker thread pools, schedulers, DB pool, and HTTP clients
    environment.monitor.subscribe(ApplicationStopped) {
        AdminConsoleManager.stopConsoleListener()
        workerManager.shutdown()
        aiRefineService.close()
        DatabaseFactory.close(preserveInMemory = true)
    }

    // Start interactive server console + loopback admin socket listener (`admin <id>`)
    AdminConsoleManager.startConsoleListener(userService)

    // 3. Plugins
    // Security headers must be added before any plugin (such as CORS) can commit a response.
    intercept(ApplicationCallPipeline.Setup) {
        if (!call.response.isCommitted) {
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("X-Frame-Options", "DENY")
            call.response.header("Referrer-Policy", "strict-origin-when-cross-origin")
            call.response.header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
            call.response.header("X-Permitted-Cross-Domain-Policies", "none")
            if (!call.request.path().startsWith("/api/attachments")) {
                call.response.header(
                    "Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' blob: data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
                )
            }
            if (call.isSecureTransport()) {
                call.response.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
            }
        }
    }

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    // Normalize origin strictly from trusted proxy peers and never when Sec-Fetch-Site indicates cross-site.
    intercept(ApplicationCallPipeline.Plugins) {
        normalizeReverseProxyOrigin(call)
    }

    install(CORS) {
        val rawOrigins = listOfNotNull(
            EnvConfig.get("CORS_ALLOWED_ORIGINS"),
            EnvConfig.get("ALLOWED_ORIGINS"),
            EnvConfig.get("PUBLIC_ORIGIN"),
            EnvConfig.get("FRONTEND_ORIGIN"),
            EnvConfig.get("FRONTEND_URL")
        ).joinToString(",")
        val isProd = EnvConfig.get("PRODUCTION_MODE")?.equals("true", ignoreCase = true) == true
        val configuredOrigins = rawOrigins.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (!isProd && configuredOrigins.any { it == "*" }) {
            anyHost()
        } else {
            val originsToAllow = if (configuredOrigins.any { it != "*" }) {
                configuredOrigins.filter { it != "*" }
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

    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            if (!call.response.isCommitted) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    com.gonghak.dto.ErrorResponse(success = false, message = "요청 본문 또는 파라미터 형식이 올바르지 않습니다.")
                )
            }
        }
        exception<JsonConvertException> { call, _ ->
            if (!call.response.isCommitted) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    com.gonghak.dto.ErrorResponse(success = false, message = "JSON 요청 형식이 올바르지 않습니다.")
                )
            }
        }
        exception<SerializationException> { call, _ ->
            if (!call.response.isCommitted) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    com.gonghak.dto.ErrorResponse(success = false, message = "JSON 데이터 역직렬화에 실패했습니다.")
                )
            }
        }
        exception<IllegalArgumentException> { call, cause ->
            if (!call.response.isCommitted) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    com.gonghak.dto.ErrorResponse(success = false, message = cause.message ?: "잘못된 요청입니다.")
                )
            }
        }
        exception<IllegalStateException> { call, cause ->
            if (!call.response.isCommitted) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    com.gonghak.dto.ErrorResponse(success = false, message = cause.message ?: "권한이 없습니다.")
                )
            }
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception: ${cause.message}", cause)
            if (!call.response.isCommitted) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    com.gonghak.dto.ErrorResponse(success = false, message = "서버 내부 오류가 발생했습니다.")
                )
            }
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

        val appConfig = this@module.environment.config
        val imapConfig = EmailReplyImapConfig.from(appConfig)
        val replyDomain = appConfig.propertyOrNull("emailReply.domain")?.getString()?.takeIf { it.isNotBlank() }
            ?: com.gonghak.config.EnvConfig.get("EMAIL_REPLY_DOMAIN")
            ?: imapConfig.inferredReplyDomain
        val replyPrefix = appConfig.propertyOrNull("emailReply.prefix")?.getString()?.takeIf { it.isNotBlank() }
            ?: com.gonghak.config.EnvConfig.get("EMAIL_REPLY_PREFIX")
            ?: imapConfig.inferredReplyPrefix
        val webhookSecret = appConfig.propertyOrNull("emailReply.webhookSecret")?.getString()
            ?: com.gonghak.config.EnvConfig.get("EMAIL_WEBHOOK_SECRET")
        val enforceSenderAuth = appConfig.propertyOrNull("emailReply.enforceSenderAuth")?.getString()
            ?.trim()?.lowercase()?.let { it != "false" && it != "0" }
            ?: com.gonghak.config.EnvConfig.get("EMAIL_ENFORCE_SENDER_AUTH")?.trim()?.lowercase()?.let { it != "false" && it != "0" }
            ?: true

        val configuredSmtpHost = com.gonghak.config.EnvConfig.get("SMTP_HOST")
            ?: if (!com.gonghak.config.EnvConfig.get("SMTP_USER").isNullOrBlank() &&
                !com.gonghak.config.EnvConfig.get("SMTP_PASS").isNullOrBlank()
            ) {
                "auto(${com.gonghak.config.EnvConfig.get("SMTP_USER")?.substringAfter("@", "")})"
            } else {
                null
            }
        if (configuredSmtpHost == null) {
            this@module.environment.log.warn("[Email] SMTP_HOST / SMTP_USER / SMTP_PASS 환경변수가 설정되지 않아 알림 메일이 실제 발송되지 않고 DB 로그(SENT)로만 기록됩니다.")
        } else {
            this@module.environment.log.info("[Email] Outbound SMTP active via $configuredSmtpHost")
        }

        emailService.configureReplyRouting(replyDomain, replyPrefix)
        val emailReplyService = EmailReplyService(
            questionService = questionService,
            replyDomainOverride = replyDomain,
            replyPrefixOverride = replyPrefix,
            webhookSecretOverride = webhookSecret,
            enforceSenderAuthOverride = enforceSenderAuth
        )
        val imapPoller = EmailReplyImapPoller(emailReplyService, imapConfig)
        if (imapPoller.isEnabled) {
            workerManager.startImapPollingScheduler(imapPoller)
        }

        authRoutes(userService)
        userSettingRoutes(userService, notificationService)
        courseRoutes(userService)
        aiRoutes(aiRefineService, userService)
        questionRoutes(questionService, userService)
        notificationRoutes(notificationService, emailService, userService)
        attachmentRoutes(attachmentService, userService)
        adminRoutes(userService, questionService, emailService)
        webhookRoutes(emailReplyService)
    }
}

private fun defaultPortForScheme(scheme: String): Int =
    if (scheme.equals("https", ignoreCase = true)) 443 else 80

private fun normalizeReverseProxyOrigin(call: ApplicationCall) {
    val rawOrigin = call.request.headers[HttpHeaders.Origin]?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val originUri = runCatching { java.net.URI(rawOrigin) }.getOrNull() ?: return
    val originScheme = originUri.scheme?.lowercase() ?: return
    if (originScheme != "http" && originScheme != "https") return
    val originHost = originUri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return
    val originPort = if (originUri.port > 0) originUri.port else defaultPortForScheme(originScheme)

    val current = call.request.origin
    val currentPort = if (current.serverPort > 0) current.serverPort else defaultPortForScheme(current.scheme)
    if (current.scheme.equals(originScheme, ignoreCase = true) &&
        current.serverHost.equals(originHost, ignoreCase = true) &&
        currentPort == originPort
    ) {
        return
    }

    // Only honor proxy/origin normalization when the socket peer is a trusted local/configured proxy,
    // and never when the browser explicitly marks the request as cross-site/same-site.
    if (!isTrustedProxyRemoteAddress(call.request.local.remoteAddress)) {
        return
    }
    val secFetchSite = call.request.headers["Sec-Fetch-Site"]?.trim()
    if (secFetchSite != null && !secFetchSite.equals("same-origin", ignoreCase = true)) {
        return
    }
    val isBrowserSameOrigin = secFetchSite.equals("same-origin", ignoreCase = true)

    // Use the rightmost proxy-appended value so client-injected leftmost headers cannot win.
    val explicitForwardedProto = call.request.headers["X-Forwarded-Proto"]
        ?.split(",")
        ?.lastOrNull()
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

    val rawHostHeader = call.request.headers["X-Forwarded-Host"]
        ?.split(",")
        ?.lastOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: call.request.headers[HttpHeaders.Host]?.trim()?.takeIf { it.isNotEmpty() }

    val hostMatchedThroughProxy = rawHostHeader?.let { rawHost ->
        val parsed = runCatching { java.net.URI("http://$rawHost") }.getOrNull()
        val reqHost = parsed?.host?.lowercase() ?: rawHost.substringBefore(':').lowercase()
        val reqPort = parsed?.port?.takeIf { it > 0 }
        val isLoopbackProxyInternal = reqHost == "127.0.0.1" || reqHost == "localhost" || reqHost == "::1"
        val protoCompatible = explicitForwardedProto == null || explicitForwardedProto == originScheme
        val portCompatible = reqPort == originPort || (reqPort == null && explicitForwardedProto == originScheme)
        !isLoopbackProxyInternal &&
            reqHost == originHost &&
            protoCompatible &&
            portCompatible
    } ?: false

    if ((isBrowserSameOrigin && (explicitForwardedProto == null || explicitForwardedProto == originScheme)) || hostMatchedThroughProxy) {
        call.mutableOriginConnectionPoint.apply {
            scheme = originScheme
            serverHost = originHost
            serverPort = originPort
        }
    }
}
