package com.gonghak

import com.gonghak.dto.*
import com.gonghak.models.QuestionCategory
import com.gonghak.models.UserRole
import com.gonghak.service.PasswordSecurity
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class R3OverhaulTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun testPasswordHashingAndSecurity() {
        val raw = "MySecurePass!2026"
        val hash1 = PasswordSecurity.hashPassword(raw)
        val hash2 = PasswordSecurity.hashPassword(raw)

        // Different random salts must produce different hashes
        assertNotEquals(hash1, hash2)
        assertTrue(hash1.startsWith("pbkdf2_sha256$65536$"))

        // Verification must succeed for correct password and fail for wrong password
        assertTrue(PasswordSecurity.verifyPassword(raw, hash1))
        assertTrue(PasswordSecurity.verifyPassword(raw, hash2))
        assertFalse(PasswordSecurity.verifyPassword("WrongPass", hash1))
    }

    @Test
    fun testRegistrationLoginAndDuplicatePrevention() = testApplication {
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 1. Register Student
        val stuRegReq = RegisterRequest(
            role = UserRole.STUDENT,
            username = "stu_r3_01",
            password = "password1234",
            name = "이민수",
            department = "소프트웨어학과",
            identifier = "20269901"
        )
        val stuRegRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(stuRegReq)
        }
        assertEquals(HttpStatusCode.Created, stuRegRes.status)
        val stuAuth = stuRegRes.body<ApiResponse<AuthResponse>>().data
        assertNotNull(stuAuth)
        assertTrue(stuAuth.token.isNotBlank())
        assertEquals("이민수", stuAuth.user.name)
        assertEquals(UserRole.STUDENT, stuAuth.user.role)

        // 2. Duplicate Student ID (학번) should fail
        val dupStuIdRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(stuRegReq.copy(username = "stu_r3_02"))
        }
        assertEquals(HttpStatusCode.BadRequest, dupStuIdRes.status)

        // 3. Duplicate Username should fail
        val dupUsernameRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(stuRegReq.copy(identifier = "20269902"))
        }
        assertEquals(HttpStatusCode.BadRequest, dupUsernameRes.status)

        // 4. Register Professor with email & courses
        val profRegReq = RegisterRequest(
            role = UserRole.PROFESSOR,
            username = "prof_r3_01",
            password = "profpass1234",
            name = "최교수",
            department = "소프트웨어학과",
            email = "choi.prof@kw.ac.kr",
            courseIds = listOf("33333333-3333-3333-3333-333333333301")
        )
        val profRegRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(profRegReq)
        }
        assertEquals(HttpStatusCode.Created, profRegRes.status)
        val profAuth = profRegRes.body<ApiResponse<AuthResponse>>().data
        assertNotNull(profAuth)
        assertEquals("choi.prof@kw.ac.kr", profAuth.user.email)

        // 5. Duplicate Professor Email should fail
        val dupProfEmailRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(profRegReq.copy(username = "prof_r3_02"))
        }
        assertEquals(HttpStatusCode.BadRequest, dupProfEmailRes.status)

        // 6. Login test
        val loginRes = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "stu_r3_01", password = "password1234"))
        }
        assertEquals(HttpStatusCode.OK, loginRes.status)
        val loginData = loginRes.body<ApiResponse<AuthResponse>>().data
        assertNotNull(loginData)
        assertEquals(stuAuth.user.id, loginData.user.id)

        // 7. Verify Bearer token works on /api/auth/me
        val meRes = client.get("/api/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${loginData.token}")
        }
        assertEquals(HttpStatusCode.OK, meRes.status)
        assertEquals("이민수", meRes.body<ApiResponse<UserDto>>().data?.name)
    }

    @Test
    fun testAdminModeAndProfessorEmailNotification() = testApplication {
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 1. Register an account that will be granted Admin via console command
        val adminReg = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.PROFESSOR,
                    username = "admin_master",
                    password = "admin1234",
                    name = "총괄관리자",
                    department = "소프트웨어학과",
                    email = "admin.master@kw.ac.kr"
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!

        assertFalse(adminReg.user.isAdmin)

        // 2. HTTP / Browser-based admin grant endpoint MUST NOT exist (404 Not Found)
        val httpGrantRes = client.post("/api/admin/console-grant") {
            contentType(ContentType.Application.Json)
            setBody(ConsoleAdminRequest(username = "admin_master", grant = true))
        }
        assertEquals(HttpStatusCode.NotFound, httpGrantRes.status)

        // 2-1. Grant Admin ONLY via server-side backend console service (AdminConsoleManager)
        val grantedUser = com.gonghak.service.UserService().grantOrRevokeAdminByUsername("admin_master", grant = true)
        assertTrue(grantedUser.isAdmin)

        // 2-2. Spoofing X-User-Id without signed Bearer token MUST be rejected on admin routes (401 Unauthorized)
        val spoofRes = client.get("/api/admin/users") {
            header("X-User-Id", adminReg.user.id)
        }
        assertEquals(HttpStatusCode.Unauthorized, spoofRes.status)

        // 2-3. Granting admin via HTTP sanction endpoint MUST be rejected (400 Bad Request)
        val httpSanctionGrantRes = client.post("/api/admin/users/${adminReg.user.id}/sanction") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(UserSanctionRequest(action = "GRANT_ADMIN"))
        }
        assertEquals(HttpStatusCode.BadRequest, httpSanctionGrantRes.status)

        // 3. Dynamically add a new Department and Course as Admin
        val deptRes = client.post("/api/admin/departments") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateDepartmentRequest(name = "AI로봇융합학과", college = "인공지능융합대학"))
        }
        assertEquals(HttpStatusCode.Created, deptRes.status)

        val courseRes = client.post("/api/admin/courses") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateCourseRequest(
                    name = "차세대포럼시스템설계",
                    code = "AI-2026-99",
                    department = "AI로봇융합학과",
                    credit = 3,
                    professorId = adminReg.user.id
                )
            )
        }
        assertEquals(HttpStatusCode.Created, courseRes.status)
        val createdCourse = courseRes.body<ApiResponse<CourseDto>>().data!!

        // 4. Register a Student in that course and post an Anonymous Question
        val stuAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "anon_stu",
                    password = "stupass123",
                    name = "김익명",
                    department = "AI로봇융합학과",
                    identifier = "20268888",
                    courseIds = listOf(createdCourse.id)
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!

        val qRes = client.post("/api/questions") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateQuestionRequest(
                    courseId = createdCourse.id,
                    profId = adminReg.user.id,
                    category = QuestionCategory.LECTURE,
                    content = "익명으로 강의 관련 질문을 올립니다. 포럼 개편 구조가 궁금합니다.",
                    isAnonymous = true
                )
            )
        }
        assertEquals(HttpStatusCode.Created, qRes.status)
        val createdQ = qRes.body<ApiResponse<QuestionDetailDto>>().data!!

        // 5. Verify Professor Email Notification was dispatched to admin.master@kw.ac.kr
        val emailLogsRes = client.get("/api/notifications/emails") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }
        assertEquals(HttpStatusCode.OK, emailLogsRes.status)
        val emailLogs = emailLogsRes.body<ApiResponse<List<EmailLogDto>>>().data!!
        assertTrue(emailLogs.any { it.recipientEmail == "admin.master@kw.ac.kr" && it.questionId == createdQ.id })

        // 6. Verify Anonymous Author is revealed to Admin, but masked to another normal user
        val otherStuAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "other_stu",
                    password = "stupass123",
                    name = "박타인",
                    department = "AI로봇융합학과",
                    identifier = "20267777",
                    courseIds = listOf(createdCourse.id)
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!

        val detailForOther = client.get("/api/questions/${createdQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${otherStuAuth.token}")
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertNull(detailForOther.realStudentName)
        assertEquals("익명 수강생", detailForOther.studentName)

        val detailForAdmin = client.get("/api/questions/${createdQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals("김익명", detailForAdmin.realStudentName)
        assertEquals("20268888", detailForAdmin.realStudentIdentifier)

        // 7. Test Hiding Question & Answer by Admin
        val hideQRes = client.patch("/api/admin/questions/${createdQ.id}/hide") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(ToggleHideRequest(isHidden = true))
        }
        assertEquals(HttpStatusCode.OK, hideQRes.status)

        val hiddenDetailForOther = client.get("/api/questions/${createdQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${otherStuAuth.token}")
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertTrue(hiddenDetailForOther.isHidden)
        assertEquals("관리자에 의해 가려진 게시물입니다.", hiddenDetailForOther.content)

        // 8. Test Question Ban (Timed Restriction) & Account Withdrawal
        val banRes = client.post("/api/admin/users/${stuAuth.user.id}/sanction") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(UserSanctionRequest(action = "BAN_QUESTION", durationDays = 3))
        }
        assertEquals(HttpStatusCode.OK, banRes.status)
        assertNotNull(banRes.body<ApiResponse<UserDto>>().data?.questionBanUntil)

        // Student should now be blocked from posting a question (403 Forbidden)
        val blockedQRes = client.post("/api/questions") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateQuestionRequest(
                    courseId = createdCourse.id,
                    category = QuestionCategory.LECTURE,
                    content = "제한 상태에서 질문 시도"
                )
            )
        }
        assertEquals(HttpStatusCode.Forbidden, blockedQRes.status)

        // Withdraw user
        val withdrawRes = client.post("/api/admin/users/${stuAuth.user.id}/sanction") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(UserSanctionRequest(action = "WITHDRAW"))
        }
        assertEquals(HttpStatusCode.OK, withdrawRes.status)
        assertTrue(withdrawRes.body<ApiResponse<UserDto>>().data!!.isWithdrawn)

        // Withdrawn user cannot log in
        val withdrawnLoginRes = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "anon_stu", password = "stupass123"))
        }
        assertEquals(HttpStatusCode.Forbidden, withdrawnLoginRes.status)
    }
}
