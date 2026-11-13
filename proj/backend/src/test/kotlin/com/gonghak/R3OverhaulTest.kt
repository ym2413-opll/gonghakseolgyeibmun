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

        // 9. Test Admin Answer & Question Permanent Deletion
        val ansRes = client.post("/api/questions/${createdQ.id}/answers") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "삭제 테스트용 답변입니다."))
        }
        assertEquals(HttpStatusCode.Created, ansRes.status)
        val createdAns = ansRes.body<ApiResponse<AnswerDto>>().data!!

        // Non-admin must be rejected (403)
        val nonAdminDelAns = client.delete("/api/admin/answers/${createdAns.id}") {
            header(HttpHeaders.Authorization, "Bearer ${otherStuAuth.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, nonAdminDelAns.status)

        val nonAdminDelQ = client.delete("/api/admin/questions/${createdQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${otherStuAuth.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, nonAdminDelQ.status)

        // Admin deletes answer -> question returns to OPEN with 0 answers
        val adminDelAns = client.delete("/api/admin/answers/${createdAns.id}") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }
        assertEquals(HttpStatusCode.OK, adminDelAns.status)

        val afterAnsDelDetail = client.get("/api/questions/${createdQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals(0, afterAnsDelDetail.answers.size)
        assertFalse(afterAnsDelDetail.isAnsweredByAssignedProfessor)

        val listAfterAnsDel = client.get("/api/questions") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }.body<ApiResponse<List<QuestionListItemDto>>>().data!!
        assertEquals(
            com.gonghak.models.QuestionStatus.OPEN,
            listAfterAnsDel.first { it.id == createdQ.id }.status
        )

        // Admin permanently deletes question
        val adminDelQ = client.delete("/api/admin/questions/${createdQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }
        assertEquals(HttpStatusCode.OK, adminDelQ.status)

        val afterDelDetail = client.get("/api/questions/${createdQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }
        assertEquals(HttpStatusCode.NotFound, afterDelDetail.status)
    }

    @Test
    fun testStudentRegistrationWithStudentIdOnlyAndIdentifierLogin() = testApplication {
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 1. Register student without separate username (uses 학번 identifier)
        val regRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "",
                    password = "password1234",
                    name = "최학번",
                    department = "소프트웨어학과",
                    identifier = "20265544"
                )
            )
        }
        assertEquals(HttpStatusCode.Created, regRes.status)
        val auth = regRes.body<ApiResponse<AuthResponse>>().data!!
        assertEquals("20265544", auth.user.username)
        assertEquals("20265544", auth.user.identifier)

        // 2. Login using Student ID
        val loginByIdRes = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "20265544", password = "password1234"))
        }
        assertEquals(HttpStatusCode.OK, loginByIdRes.status)

        // 3. Student registered with separate legacy username can ALSO log in via 학번 identifier
        val legacyRegRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "legacy_stu_login",
                    password = "password1234",
                    name = "김레거시",
                    department = "소프트웨어학과",
                    identifier = "20265545"
                )
            )
        }
        assertEquals(HttpStatusCode.Created, legacyRegRes.status)

        val loginByLegacyIdentifierRes = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "20265545", password = "password1234"))
        }
        assertEquals(HttpStatusCode.OK, loginByLegacyIdentifierRes.status)

        // 4. Legacy account can ALSO log in via its original username (case-insensitive)
        val loginByLegacyUsernameRes = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "LEGACY_STU_LOGIN", password = "password1234"))
        }
        assertEquals(HttpStatusCode.OK, loginByLegacyUsernameRes.status)
    }

    @Test
    fun testLegacyIdAndStudentNumberLoginEdgeCases() = testApplication {
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 1. Legacy ID + 학번 student sharing identifier with a seeded demo student ("20261101")
        val regDemoCollideRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "LegacyDemoCollide",
                    password = "password1234",
                    name = "김데모충돌",
                    department = "소프트웨어학과",
                    identifier = "20261101"
                )
            )
        }
        assertEquals(HttpStatusCode.Created, regDemoCollideRes.status)
        val regDemoCollide = regDemoCollideRes.body<ApiResponse<AuthResponse>>().data!!

        // Login via legacy username (case-insensitive)
        val byUsername = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "legacydemocollide", password = "password1234"))
        }
        assertEquals(HttpStatusCode.OK, byUsername.status)
        assertEquals(regDemoCollide.user.id, byUsername.body<ApiResponse<AuthResponse>>().data?.user?.id)

        // Login via 학번 (must ignore demo user 11111111-...-1101 and authenticate the real account)
        val byStudentId = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "20261101", password = "password1234"))
        }
        assertEquals(HttpStatusCode.OK, byStudentId.status)
        assertEquals(regDemoCollide.user.id, byStudentId.body<ApiResponse<AuthResponse>>().data?.user?.id)

        // 2. Legacy student whose alphanumeric identifier contains uppercase letters (frontend lowercases input)
        val regAlphaIdRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "legacy_alpha_stu",
                    password = "password1234",
                    name = "박영문학번",
                    department = "컴퓨터공학과",
                    identifier = "KW2026A01"
                )
            )
        }
        assertEquals(HttpStatusCode.Created, regAlphaIdRes.status)
        val regAlpha = regAlphaIdRes.body<ApiResponse<AuthResponse>>().data!!

        val byLowercaseAlphaSid = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "kw2026a01", password = "password1234"))
        }
        assertEquals(HttpStatusCode.OK, byLowercaseAlphaSid.status)
        assertEquals(regAlpha.user.id, byLowercaseAlphaSid.body<ApiResponse<AuthResponse>>().data?.user?.id)

        // 3. Cross-user overlap: User1 has legacy username + 학번 "20266188", while User2 has username "20266188"
        val user1 = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "overlap_legacy_id",
                    password = "pass_user1",
                    name = "구계정학생",
                    department = "소프트웨어학과",
                    identifier = "20266188"
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!

        val user2 = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "20266188",
                    password = "pass_user2",
                    name = "동명아이디",
                    department = "소프트웨어학과",
                    identifier = "20266199"
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!

        val loginOverlapUser1BySid = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "20266188", password = "pass_user1"))
        }
        assertEquals(HttpStatusCode.OK, loginOverlapUser1BySid.status)
        assertEquals(user1.user.id, loginOverlapUser1BySid.body<ApiResponse<AuthResponse>>().data?.user?.id)

        val loginOverlapUser2ByUsername = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "20266188", password = "pass_user2"))
        }
        assertEquals(HttpStatusCode.OK, loginOverlapUser2ByUsername.status)
        assertEquals(user2.user.id, loginOverlapUser2ByUsername.body<ApiResponse<AuthResponse>>().data?.user?.id)

        val loginOverlapUser1ByUsername = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "overlap_legacy_id", password = "pass_user1"))
        }
        assertEquals(HttpStatusCode.OK, loginOverlapUser1ByUsername.status)
        assertEquals(user1.user.id, loginOverlapUser1ByUsername.body<ApiResponse<AuthResponse>>().data?.user?.id)
    }
}
