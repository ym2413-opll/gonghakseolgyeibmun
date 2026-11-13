package com.gonghak

import com.gonghak.dto.*
import com.gonghak.models.QuestionCategory
import com.gonghak.models.UserRole
import com.gonghak.routes.AUTH_COOKIE_NAME
import com.gonghak.service.UserService
import com.gonghak.worker.ImageCompressionProcessor
import com.gonghak.worker.SmtpTransport
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32
import kotlin.test.*

class SecurityRegressionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun minimalPng1x1(): ByteArray {
        val baos = ByteArrayOutputStream()
        val img = java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
        javax.imageio.ImageIO.write(img, "png", baos)
        return baos.toByteArray()
    }

    private fun craftedGiantHeaderPng(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        // PNG signature
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // IHDR length = 13
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x0D))
        val ihdr = ByteBuffer.allocate(17)
        ihdr.put("IHDR".toByteArray(Charsets.US_ASCII))
        ihdr.putInt(width)
        ihdr.putInt(height)
        ihdr.put(8) // bit depth
        ihdr.put(2) // RGB
        ihdr.put(0)
        ihdr.put(0)
        ihdr.put(0)
        val ihdrBytes = ihdr.array()
        out.write(ihdrBytes)
        val crc = CRC32().apply { update(ihdrBytes) }.value.toInt()
        out.write(ByteBuffer.allocate(4).putInt(crc).array())
        return out.toByteArray()
    }

    @Test
    fun testUnauthenticatedAndDemoSpoofingBlocked() = testApplication {
        val client = createClient { install(ContentNegotiation) { json(json) } }

        val protectedGets = listOf(
            "/api/auth/me",
            "/api/questions",
            "/api/questions/similar?courseId=course-001&category=LECTURE&query=test",
            "/api/notifications",
            "/api/notifications/emails",
            "/api/settings",
            "/api/user/settings",
            "/api/professor/settings",
            "/api/professor/notification-settings",
            "/api/courses/course-001/categories"
        )
        for (path in protectedGets) {
            val res = client.get(path)
            assertEquals(HttpStatusCode.Unauthorized, res.status, "GET $path must require authentication")
            val spoofRes = client.get(path) {
                header("X-User-Id", "user-stu-001")
            }
            assertEquals(HttpStatusCode.Unauthorized, spoofRes.status, "GET $path must reject X-User-Id spoofing")
        }

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/auth/users").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/ai/refine") {
                contentType(ContentType.Application.Json)
                setBody(RefineQuestionRequest(rawContent = "테스트"))
            }.status
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/questions") {
                contentType(ContentType.Application.Json)
                setBody(CreateQuestionRequest(courseId = "course-001", category = QuestionCategory.LECTURE, content = "x"))
            }.status
        )
    }

    @Test
    fun testTokenRevocationOnWithdrawAndLogoutAndRoleGuards() = testApplication {
        val client = createClient { install(ContentNegotiation) { json(json) } }

        val suffix = (1000..9999).random()
        val stuReg = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "secstu_$suffix",
                    password = "password1234",
                    name = "보안학생",
                    department = "소프트웨어학과",
                    identifier = "2026$suffix"
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!

        // Student cannot access professor-only endpoints
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/api/courses/course-001/categories") {
                header(HttpHeaders.Authorization, "Bearer ${stuReg.token}")
            }.status
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/api/professor/settings") {
                header(HttpHeaders.Authorization, "Bearer ${stuReg.token}")
            }.status
        )

        // Student cannot post with nonexistent courseId
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/api/questions") {
                header(HttpHeaders.Authorization, "Bearer ${stuReg.token}")
                contentType(ContentType.Application.Json)
                setBody(
                    CreateQuestionRequest(
                        courseId = UUID.randomUUID().toString(),
                        category = QuestionCategory.LECTURE,
                        content = "존재하지 않는 강의 ID 테스트"
                    )
                )
            }.status
        )

        // Login sets HttpOnly cookie and returns token
        val loginRes = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "secstu_$suffix", password = "password1234"))
        }
        assertEquals(HttpStatusCode.OK, loginRes.status)
        val setCookieHeader = loginRes.headers.getAll(HttpHeaders.SetCookie)?.joinToString("\n").orEmpty()
        assertTrue(setCookieHeader.contains(AUTH_COOKIE_NAME))
        assertTrue(setCookieHeader.contains("HttpOnly", ignoreCase = true))
        val loginToken = loginRes.body<ApiResponse<AuthResponse>>().data!!.token

        // Logout invalidates token server-side
        val logoutRes = client.post("/api/auth/logout") {
            header(HttpHeaders.Authorization, "Bearer $loginToken")
        }
        assertEquals(HttpStatusCode.OK, logoutRes.status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/auth/me") {
                header(HttpHeaders.Authorization, "Bearer $loginToken")
            }.status
        )

        // Fresh login, then Admin WITHDRAW -> token must immediately return 401
        val freshLogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "secstu_$suffix", password = "password1234"))
        }.body<ApiResponse<AuthResponse>>().data!!

        val adminReg = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.PROFESSOR,
                    username = "secadmin_$suffix",
                    password = "password1234",
                    name = "보안관리자",
                    department = "소프트웨어학과",
                    email = "secadmin_$suffix@kw.ac.kr"
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!

        val userService = UserService()
        userService.grantOrRevokeAdminByUsername("secadmin_$suffix", grant = true)

        assertEquals(
            HttpStatusCode.OK,
            client.post("/api/admin/users/${stuReg.user.id}/sanction") {
                header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
                contentType(ContentType.Application.Json)
                setBody(UserSanctionRequest(action = "WITHDRAW"))
            }.status
        )

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/auth/me") {
                header(HttpHeaders.Authorization, "Bearer ${freshLogin.token}")
            }.status
        )

        // Revoking admin increments tokenVersion so old admin token is immediately invalidated too
        userService.grantOrRevokeAdminByUsername("secadmin_$suffix", grant = false)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/admin/users") {
                header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            }.status
        )
    }

    @Test
    fun testAttachmentIdorAndHiddenQuestionBlockingAndCookieDownload() = testApplication {
        val client = createClient { install(ContentNegotiation) { json(json) } }
        val suffix = (1000..9999).random()

        val ownerAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "attowner_$suffix",
                    password = "password1234",
                    name = "업로더",
                    department = "소프트웨어학과",
                    identifier = "2027$suffix"
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!

        val attackerAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "attother_$suffix",
                    password = "password1234",
                    name = "타인",
                    department = "소프트웨어학과",
                    identifier = "2028$suffix"
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!

        val adminAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.PROFESSOR,
                    username = "attadmin_$suffix",
                    password = "password1234",
                    name = "첨부관리자",
                    department = "소프트웨어학과",
                    email = "attadmin_$suffix@kw.ac.kr"
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!

        UserService().grantOrRevokeAdminByUsername("attadmin_$suffix", grant = true)

        // Owner uploads small PNG
        val uploadRes = client.submitFormWithBinaryData(
            url = "/api/attachments/upload",
            formData = formData {
                append("file", minimalPng1x1(), Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"secret_note.png\"")
                })
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${ownerAuth.token}")
        }
        assertEquals(HttpStatusCode.Created, uploadRes.status)
        val attId = uploadRes.body<FileUploadResponse>().attachment!!.id

        // Unlinked attachment cannot be previewed, downloaded, deleted, or stolen by attacker
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/api/attachments/$attId") {
                header(HttpHeaders.Authorization, "Bearer ${attackerAuth.token}")
            }.status
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.delete("/api/attachments/$attId") {
                header(HttpHeaders.Authorization, "Bearer ${attackerAuth.token}")
            }.status
        )
        val hijackQRes = client.post("/api/questions") {
            header(HttpHeaders.Authorization, "Bearer ${attackerAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateQuestionRequest(
                    courseId = "course-001",
                    category = QuestionCategory.LECTURE,
                    content = "타인의 첨부파일 탈취 시도",
                    attachmentIds = listOf(attId)
                )
            )
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertTrue(hijackQRes.attachments.isEmpty(), "Attacker must not be able to link owner's attachment")

        // Owner downloads via HttpOnly cookie alone (simulating <img> / <a download>)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/attachments/$attId") {
                cookie(AUTH_COOKIE_NAME, ownerAuth.token)
            }.status
        )

        // Owner links attachment to their own question, then Admin hides question -> attacker gets 403 on attachment
        val ownerQ = client.post("/api/questions") {
            header(HttpHeaders.Authorization, "Bearer ${ownerAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateQuestionRequest(
                    courseId = "course-001",
                    category = QuestionCategory.LECTURE,
                    content = "정상 첨부파일 질문",
                    attachmentIds = listOf(attId)
                )
            )
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals(1, ownerQ.attachments.size)

        val hideRes = client.patch("/api/admin/questions/${ownerQ.id}/hide") {
            header(HttpHeaders.Authorization, "Bearer ${adminAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(ToggleHideRequest(isHidden = true))
        }
        assertEquals(HttpStatusCode.OK, hideRes.status)

        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/api/attachments/$attId/download") {
                header(HttpHeaders.Authorization, "Bearer ${attackerAuth.token}")
            }.status
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/attachments/$attId/download") {
                header(HttpHeaders.Authorization, "Bearer ${adminAuth.token}")
            }.status
        )
    }

    @Test
    fun testLoginRateLimitingAndSmtpDotStuffingAndImageDimensionGuard() = testApplication {
        val client = createClient { install(ContentNegotiation) { json(json) } }

        // Login rate limiting (account limiter triggers after 12 attempts in 60s -> 429)
        var got429 = false
        repeat(15) {
            val res = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(username = "ratelimit_user", password = "wrongpassword"))
            }
            if (res.status == HttpStatusCode.TooManyRequests) {
                got429 = true
            }
        }
        assertTrue(got429, "Excessive login attempts should be rate-limited with 429 Too Many Requests")

        // SMTP dot-stuffing & CRLF normalization
        val stuffed = SmtpTransport.dotStuffBody("Hello\n.\n.hidden\r\nWorld")
        assertEquals("Hello\r\n..\r\n..hidden\r\nWorld", stuffed)

        // Image bomb dimension check (12000x12000 exceeds MAX_SOURCE_DIMENSION / MAX_SOURCE_PIXELS)
        val giantPng = craftedGiantHeaderPng(12000, 12000)
        assertThrows<SecurityException> {
            ImageCompressionProcessor.validateImageDimensions(giantPng)
        }
    }

    @Test
    fun testReverseProxyRegisterCorsAndSecurityHeadersOnRejectedOrigin() = testApplication {
        val client = createClient { install(ContentNegotiation) { json(json) } }

        // 1. Disallowed cross-site POST must return 403 without crashing on post-commit security headers
        val blockedRes = client.post("/api/auth/register") {
            header(HttpHeaders.Origin, "https://evil.example.com")
            header("Sec-Fetch-Site", "cross-site")
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    username = "2026999901",
                    password = "Password!1",
                    role = UserRole.STUDENT,
                    name = "차단테스트",
                    department = "전자공학과",
                    identifier = "2026999901"
                )
            )
        }
        assertEquals(HttpStatusCode.Forbidden, blockedRes.status)
        assertEquals("nosniff", blockedRes.headers["X-Content-Type-Options"])

        // 2. Same-origin POST behind reverse proxy (with X-Forwarded-Proto/Host) must succeed
        val forwardedRes = client.post("/api/auth/register") {
            header(HttpHeaders.Origin, "https://cistus.example.edu")
            header("X-Forwarded-Proto", "https")
            header("X-Forwarded-Host", "cistus.example.edu")
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    username = "2026999902",
                    password = "Password!1",
                    role = UserRole.STUDENT,
                    name = "프록시학생",
                    department = "전자공학과",
                    identifier = "2026999902"
                )
            )
        }
        assertEquals(HttpStatusCode.Created, forwardedRes.status)
        assertEquals("nosniff", forwardedRes.headers["X-Content-Type-Options"])

        // 3. Same-origin POST behind basic proxy_pass (no X-Forwarded-*, browser Sec-Fetch-Site=same-origin) must succeed
        val sameOriginRes = client.post("/api/auth/register") {
            header(HttpHeaders.Origin, "https://cistus.example.edu")
            header("Sec-Fetch-Site", "same-origin")
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    username = "2026999903",
                    password = "Password!1",
                    role = UserRole.STUDENT,
                    name = "기본프록시학생",
                    department = "전자공학과",
                    identifier = "2026999903"
                )
            )
        }
        assertEquals(HttpStatusCode.Created, sameOriginRes.status)
    }
}
