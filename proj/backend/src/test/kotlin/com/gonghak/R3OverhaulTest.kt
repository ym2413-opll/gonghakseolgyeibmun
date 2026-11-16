package com.gonghak

import com.gonghak.dto.*
import com.gonghak.models.CourseProfessorsTable
import com.gonghak.models.QuestionCategory
import com.gonghak.models.UserRole
import com.gonghak.models.UsersTable
import com.gonghak.service.PasswordSecurity
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
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
            identifier = "2026101901"
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
            setBody(stuRegReq.copy(identifier = "2026101902"))
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
                    identifier = "AUTO",
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
                    identifier = "AUTO",
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
        assertEquals(stuAuth.user.identifier, detailForAdmin.realStudentIdentifier)

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
                    identifier = "2026101444"
                )
            )
        }
        assertEquals(HttpStatusCode.Created, regRes.status)
        val auth = regRes.body<ApiResponse<AuthResponse>>().data!!
        assertEquals("2026101444", auth.user.username)
        assertEquals("2026101444", auth.user.identifier)

        // 2. Login using Student ID
        val loginByIdRes = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "2026101444", password = "password1234"))
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
                    identifier = "2026101445"
                )
            )
        }
        assertEquals(HttpStatusCode.Created, legacyRegRes.status)

        val loginByLegacyIdentifierRes = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "2026101445", password = "password1234"))
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

        // 1. Legacy ID + 학번 student sharing identifier with a seeded demo student ("2026101101")
        val regDemoCollideRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "LegacyDemoCollide",
                    password = "password1234",
                    name = "김데모충돌",
                    department = "소프트웨어학과",
                    identifier = "2026101101"
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

        // Login via 학번 (must ignore demo user and authenticate the real account)
        val byStudentId = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "2026101101", password = "password1234"))
        }
        assertEquals(HttpStatusCode.OK, byStudentId.status)
        assertEquals(regDemoCollide.user.id, byStudentId.body<ApiResponse<AuthResponse>>().data?.user?.id)

        // 2. Non-10-digit alphanumeric identifier MUST be rejected under R4; valid 10-digit identifier with mixed-case username works case-insensitively
        val invalidAlphaRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "invalid_alpha_stu",
                    password = "password1234",
                    name = "잘못된학번",
                    department = "컴퓨터공학과",
                    identifier = "KW2026A01"
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, invalidAlphaRes.status)

        val regAlphaRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "KW2026A01",
                    password = "password1234",
                    name = "박대소문자아이디",
                    department = "컴퓨터공학과",
                    identifier = "2026102001"
                )
            )
        }
        assertEquals(HttpStatusCode.Created, regAlphaRes.status)
        val regAlpha = regAlphaRes.body<ApiResponse<AuthResponse>>().data!!

        val byLowercaseUsername = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "kw2026a01", password = "password1234"))
        }
        assertEquals(HttpStatusCode.OK, byLowercaseUsername.status)
        assertEquals(regAlpha.user.id, byLowercaseUsername.body<ApiResponse<AuthResponse>>().data?.user?.id)

        // 3. Cross-user overlap: User1 has legacy username + 학번 "2026101188", while User2 has username "2026101188"
        val user1 = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "overlap_legacy_id",
                    password = "pass_user1",
                    name = "구계정학생",
                    department = "소프트웨어학과",
                    identifier = "2026101188"
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!

        val user2 = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "2026101188",
                    password = "pass_user2",
                    name = "동명아이디",
                    department = "소프트웨어학과",
                    identifier = "2026101199"
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!

        val loginOverlapUser1BySid = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "2026101188", password = "pass_user1"))
        }
        assertEquals(HttpStatusCode.OK, loginOverlapUser1BySid.status)
        assertEquals(user1.user.id, loginOverlapUser1BySid.body<ApiResponse<AuthResponse>>().data?.user?.id)

        val loginOverlapUser2ByUsername = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = "2026101188", password = "pass_user2"))
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

    @Test
    fun testCourseScopeCreationAndUserCourseSettings() = testApplication {
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 1. Register admin user and grant admin privilege via server service
        val adminReg = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.PROFESSOR,
                    username = "scope_admin_user",
                    password = "adminpass123",
                    name = "과목관리자",
                    department = "소프트웨어학과",
                    email = "scope_admin@kw.ac.kr"
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!
        com.gonghak.service.UserService().grantOrRevokeAdminByUsername(adminReg.user.id, true)

        // 2. Admin creates courses without professorId: DEPARTMENT, COLLEGE, and ALL scopes
        val deptCourse = client.post("/api/admin/courses") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateCourseRequest(
                    name = "학과전공세미나",
                    code = "DEPT-101",
                    semester = "2026-2",
                    scopeType = "DEPARTMENT",
                    department = "소프트웨어학과"
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<CourseDto>>().data!!
        assertEquals("소프트웨어학과", deptCourse.department)
        assertEquals("인공지능융합대학", deptCourse.college)
        assertTrue(deptCourse.professors.isEmpty())

        val collegeCourse = client.post("/api/admin/courses") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateCourseRequest(
                    name = "단과대기초설계",
                    code = "COL-101",
                    semester = "2026-2",
                    scopeType = "COLLEGE",
                    college = "공과대학"
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<CourseDto>>().data!!
        assertEquals("공과대학", collegeCourse.college)
        assertEquals("공과대학 전체", collegeCourse.department)
        assertTrue(collegeCourse.professors.isEmpty())

        val allCourse = client.post("/api/admin/courses") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateCourseRequest(
                    name = "전교교양특강",
                    code = "ALL-101",
                    semester = "2026-2",
                    scopeType = "ALL"
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<CourseDto>>().data!!
        assertEquals("전체", allCourse.college)
        assertEquals("전체", allCourse.department)
        assertTrue(allCourse.professors.isEmpty())

        // Verify COLLEGE and ALL labels were NOT added as fake entries in /api/departments
        val depts = client.get("/api/departments").body<ApiResponse<List<DepartmentDto>>>().data!!
        assertFalse(depts.any { it.name == "전체" || it.name == "공과대학 전체" })

        // 3. Student registers with specific courseIds (including an unassigned-professor course)
        val stuAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "2026101911",
                    password = "stupass123",
                    name = "수강선택학생",
                    department = "소프트웨어학과",
                    identifier = "2026101911",
                    courseIds = listOf(collegeCourse.id)
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<AuthResponse>>().data!!
        assertEquals(listOf(collegeCourse.id), stuAuth.user.courses.map { it.id })

        // 4. Student cannot clear all courses (requires >= 1), and can update enrolled courses
        val emptyStuUpdate = client.put("/api/courses/my") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(UpdateUserCoursesRequest(courseIds = emptyList()))
        }
        assertEquals(HttpStatusCode.BadRequest, emptyStuUpdate.status)

        val stuUpdateRes = client.put("/api/courses/my") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(UpdateUserCoursesRequest(courseIds = listOf(deptCourse.id, allCourse.id)))
        }
        assertEquals(HttpStatusCode.OK, stuUpdateRes.status)
        val updatedStuCourses = stuUpdateRes.body<ApiResponse<List<CourseDto>>>().data!!.map { it.id }.toSet()
        assertEquals(setOf(deptCourse.id, allCourse.id), updatedStuCourses)

        // 5. Professor updates assigned courses (>= 1 required)
        val emptyProfUpdate = client.put("/api/courses/my") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(UpdateUserCoursesRequest(courseIds = emptyList()))
        }
        assertEquals(HttpStatusCode.BadRequest, emptyProfUpdate.status)

        val profUpdateRes = client.put("/api/courses/my") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            header(HttpHeaders.Origin, "https://cistus.example.com")
            header("Sec-Fetch-Site", "same-origin")
            contentType(ContentType.Application.Json)
            setBody(UpdateUserCoursesRequest(courseIds = listOf(allCourse.id)))
        }
        assertEquals(HttpStatusCode.OK, profUpdateRes.status)
        val profCourses = profUpdateRes.body<ApiResponse<List<CourseDto>>>().data!!
        assertEquals(listOf(allCourse.id), profCourses.map { it.id })
        assertTrue(profCourses.first().professors.any { it.id == adminReg.user.id })

        // 6. Verify session remains valid via /api/auth/me and /api/courses after course updates
        //    and that student + catalog courses reflect the newly assigned professor
        val stuMeAfter = client.get("/api/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
        }
        assertEquals(HttpStatusCode.OK, stuMeAfter.status)
        val stuMeUser = stuMeAfter.body<ApiResponse<UserDto>>().data!!
        assertEquals(stuAuth.user.id, stuMeUser.id)
        assertEquals(setOf(deptCourse.id, allCourse.id), stuMeUser.courses.map { it.id }.toSet())
        val stuAllCourse = stuMeUser.courses.first { it.id == allCourse.id }
        assertEquals(adminReg.user.id, stuAllCourse.assignedProfessorId)
        assertEquals(adminReg.user.name, stuAllCourse.assignedProfessorName)
        assertTrue(stuAllCourse.professors.any { it.id == adminReg.user.id && it.name == adminReg.user.name })

        val profMeAfter = client.get("/api/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }
        assertEquals(HttpStatusCode.OK, profMeAfter.status)
        val profMeUser = profMeAfter.body<ApiResponse<UserDto>>().data!!
        assertEquals(adminReg.user.id, profMeUser.id)
        assertEquals(listOf(allCourse.id), profMeUser.courses.map { it.id })
        assertEquals(adminReg.user.name, profMeUser.courses.first().assignedProfessorName)

        val catalogAfter = client.get("/api/courses?all=true&limit=2000")
            .body<ApiResponse<CourseListResponse>>().data!!
        val catalogAllCourse = catalogAfter.courses.first { it.id == allCourse.id }
        assertEquals(adminReg.user.id, catalogAllCourse.assignedProfessorId)
        assertEquals(adminReg.user.name, catalogAllCourse.assignedProfessorName)

        // 7. Role guard: professor cannot post a question, student cannot post an answer
        val profQuestionAttempt = client.post("/api/questions") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateQuestionRequest(
                    courseId = allCourse.id,
                    category = QuestionCategory.LECTURE,
                    content = "교수 계정으로 질문 작성을 시도합니다."
                )
            )
        }
        assertEquals(HttpStatusCode.Forbidden, profQuestionAttempt.status)

        val validStuQuestionRes = client.post("/api/questions") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateQuestionRequest(
                    courseId = allCourse.id,
                    category = QuestionCategory.LECTURE,
                    content = "학생이 작성한 정상 질문입니다. 교수님께 여쭙습니다."
                )
            )
        }
        assertEquals(HttpStatusCode.Created, validStuQuestionRes.status)
        val validStuQ = validStuQuestionRes.body<ApiResponse<QuestionDetailDto>>().data!!

        val stuAnswerAttempt = client.post("/api/questions/${validStuQ.id}/answers") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "학생 계정으로 답변 작성을 시도합니다."))
        }
        assertEquals(HttpStatusCode.Forbidden, stuAnswerAttempt.status)

        // 8. Follow-up questions (꼬리질문) after professor answer
        val followUpBeforeAnswer = client.post("/api/questions/${validStuQ.id}/followups") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateFollowUpRequest(content = "교수님 답변 전 추가 질문"))
        }
        assertEquals(HttpStatusCode.Forbidden, followUpBeforeAnswer.status)

        val profFirstAnswerRes = client.post("/api/questions/${validStuQ.id}/answers") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "1차 답변입니다."))
        }
        assertEquals(HttpStatusCode.Created, profFirstAnswerRes.status)

        val stuAnswerNotifs = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
        }.body<ApiResponse<List<NotificationDto>>>().data!!
        assertTrue(
            stuAnswerNotifs.any { it.type == "ANSWER_NEW" && it.questionId == validStuQ.id && !it.isRead },
            "Student author must receive unread ANSWER_NEW notification when professor posts an answer"
        )

        val profFollowUpAttempt = client.post("/api/questions/${validStuQ.id}/followups") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateFollowUpRequest(content = "교수가 추가 질문 시도"))
        }
        assertEquals(HttpStatusCode.Forbidden, profFollowUpAttempt.status)

        val stuFollowUpRes = client.post("/api/questions/${validStuQ.id}/followups") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateFollowUpRequest(content = "1차 답변 잘 읽었습니다. 추가 질문이 있습니다."))
        }
        assertEquals(HttpStatusCode.Created, stuFollowUpRes.status)
        val followUpDto = stuFollowUpRes.body<ApiResponse<AnswerDto>>().data!!
        assertEquals("STUDENT", followUpDto.authorRole)
        assertEquals("질문자 추가 질문", followUpDto.authorTitle)

        val detailAfterFollowUp = client.get("/api/questions/${validStuQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals(com.gonghak.models.QuestionStatus.OPEN, detailAfterFollowUp.status)
        assertTrue(detailAfterFollowUp.canAnswer)
        assertEquals(2, detailAfterFollowUp.answers.size)

        val profSecondAnswerRes = client.post("/api/questions/${validStuQ.id}/answers") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "추가 질문에 대한 2차 답변입니다."))
        }
        assertEquals(HttpStatusCode.Created, profSecondAnswerRes.status)

        val detailAfterSecondAnswer = client.get("/api/questions/${validStuQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals(com.gonghak.models.QuestionStatus.ANSWERED, detailAfterSecondAnswer.status)
        assertTrue(detailAfterSecondAnswer.canFollowUp)
        assertEquals(3, detailAfterSecondAnswer.answers.size)

        // 9. AI refinement supports follow-up questions with thread context
        val followUpRefineRes = client.post("/api/ai/refine") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(
                RefineQuestionRequest(
                    rawContent = "방금 설명해주신 2차 답변에서 예제 부분이 잘 안 와닿는데 예시 하나만 더 들려주세요",
                    title = validStuQ.title,
                    courseName = allCourse.name,
                    professorName = adminReg.user.name,
                    category = QuestionCategory.LECTURE,
                    isFollowUp = true,
                    previousAnswer = "추가 질문에 대한 2차 답변입니다."
                )
            )
        }
        assertEquals(HttpStatusCode.OK, followUpRefineRes.status)
        val followUpRefined = followUpRefineRes.body<ApiResponse<RefineQuestionResponse>>().data!!
        assertTrue(followUpRefined.refined.isNotBlank())

        // 10. Second follow-up persists originalContent (pre-AI draft) for the student,
        // while professor accounts never receive student pre-AI drafts on main or follow-up questions
        val rawDraft2 = "교수님 방금 설명하신 부분 예제 하나만 더 알려주세요"
        val stuFollowUp2Res = client.post("/api/questions/${validStuQ.id}/followups") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateFollowUpRequest(
                    content = followUpRefined.refined,
                    originalContent = rawDraft2,
                    hasAiRefined = true
                )
            )
        }
        assertEquals(HttpStatusCode.Created, stuFollowUp2Res.status)
        val followUp2Dto = stuFollowUp2Res.body<ApiResponse<AnswerDto>>().data!!
        assertEquals(rawDraft2, followUp2Dto.originalContent)

        val stuDetailWithAllFollowUps = client.get("/api/questions/${validStuQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals(4, stuDetailWithAllFollowUps.answers.size)
        val stuLoadedFollowUp2 = stuDetailWithAllFollowUps.answers.last()
        assertEquals(rawDraft2, stuLoadedFollowUp2.originalContent)
        assertEquals(followUpRefined.refined, stuLoadedFollowUp2.content)

        val profDetailWithAllFollowUps = client.get("/api/questions/${validStuQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals(4, profDetailWithAllFollowUps.answers.size)
        assertEquals("", profDetailWithAllFollowUps.originalContent)
        assertEquals("", profDetailWithAllFollowUps.rawContent)
        assertEquals("", profDetailWithAllFollowUps.answers.last().originalContent)

        val profListWithAllFollowUps = client.get("/api/questions") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }.body<ApiResponse<List<QuestionListItemDto>>>().data!!
        val profListItem = profListWithAllFollowUps.find { it.id == validStuQ.id }
        assertNotNull(profListItem)
        assertEquals("", profListItem.originalContent)
        assertEquals("", profListItem.answers.last().originalContent)

        // 11. Author can delete their own question; students no longer see it while professor sees a redacted tombstone
        val otherDelStuAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "other_del_stu",
                    password = "stupass123",
                    name = "김타인",
                    department = "컴퓨터정보공학부",
                    identifier = "AUTO",
                    courseIds = listOf(allCourse.id)
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!

        val otherStuDeleteAttempt = client.delete("/api/questions/${validStuQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${otherDelStuAuth.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, otherStuDeleteAttempt.status)

        val profDeleteAttempt = client.delete("/api/questions/${validStuQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, profDeleteAttempt.status)

        val authorDeleteRes = client.delete("/api/questions/${validStuQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
        }
        assertEquals(HttpStatusCode.OK, authorDeleteRes.status)

        val stuListAfterDelete = client.get("/api/questions") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
        }.body<ApiResponse<List<QuestionListItemDto>>>().data!!
        assertFalse(stuListAfterDelete.any { it.id == validStuQ.id })

        val stuDetailAfterDelete = client.get("/api/questions/${validStuQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
        }
        assertEquals(HttpStatusCode.NotFound, stuDetailAfterDelete.status)

        val profListAfterDelete = client.get("/api/questions") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }.body<ApiResponse<List<QuestionListItemDto>>>().data!!
        val deletedListEntry = profListAfterDelete.find { it.id == validStuQ.id }
        assertNotNull(deletedListEntry)
        assertTrue(deletedListEntry.isDeleted)
        assertNotNull(deletedListEntry.deletedAt)
        assertTrue((deletedListEntry.title ?: "").startsWith("[작성자 삭제]"))
        assertEquals("질문 작성자에 의해 삭제된 게시물입니다.", deletedListEntry.content)

        val profDetailAfterDelete = client.get("/api/questions/${validStuQ.id}") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }
        assertEquals(HttpStatusCode.OK, profDetailAfterDelete.status)
        val deletedDetail = profDetailAfterDelete.body<ApiResponse<QuestionDetailDto>>().data!!
        assertTrue(deletedDetail.isDeleted)
        assertFalse(deletedDetail.canAnswer)
        assertFalse(deletedDetail.canFollowUp)
        assertEquals("", deletedDetail.originalContent)

        val answerOnDeletedAttempt = client.post("/api/questions/${validStuQ.id}/answers") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "삭제 후 답변 시도"))
        }
        assertEquals(HttpStatusCode.Forbidden, answerOnDeletedAttempt.status)

        val followUpOnDeletedAttempt = client.post("/api/questions/${validStuQ.id}/followups") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateFollowUpRequest(content = "삭제 후 추가 질문 시도"))
        }
        assertEquals(HttpStatusCode.Forbidden, followUpOnDeletedAttempt.status)

        val profNotifications = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${adminReg.token}")
        }.body<ApiResponse<List<NotificationDto>>>().data!!
        assertTrue(profNotifications.any { it.type == "QUESTION_DELETED" && it.referenceId == validStuQ.id })
    }

    @Test
    fun testR4OverhaulFeatures() = testApplication {
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 0. Register Admin and create a dedicated course for R4 tests
        val adminProfAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.PROFESSOR,
                    username = "r4_admin_user",
                    password = "adminpass123",
                    name = "R4관리자",
                    department = "소프트웨어학과"
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!
        com.gonghak.service.UserService().grantOrRevokeAdminByUsername("r4_admin_user", grant = true)

        val r4Course = client.post("/api/admin/courses") {
            header(HttpHeaders.Authorization, "Bearer ${adminProfAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateCourseRequest(
                    name = "R4스레드설계세미나",
                    code = "R4-101",
                    semester = "2026-2",
                    scopeType = "ALL"
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<CourseDto>>().data!!

        // 1. Professor registration without email -> succeeds and disables email notifications by default
        val noEmailProfRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.PROFESSOR,
                    username = "prof_r4_noemail",
                    password = "profpass1234",
                    name = "이메일없음교수",
                    department = "소프트웨어학과",
                    email = null,
                    courseIds = listOf(r4Course.id)
                )
            )
        }
        assertEquals(HttpStatusCode.Created, noEmailProfRes.status)
        val noEmailProfAuth = noEmailProfRes.body<ApiResponse<AuthResponse>>().data!!
        assertNull(noEmailProfAuth.user.email)

        val profSettingsRes = client.get("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer ${noEmailProfAuth.token}")
        }.body<UnifiedSettingDto>()
        assertFalse(profSettingsRes.emailEnabled)
        assertNull(profSettingsRes.notificationEmail)

        // 2. Department code rules (college 1..9 + dept 01..99) & 10-digit student ID allocation
        val deptList = client.get("/api/departments")
            .also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<ApiResponse<List<DepartmentDto>>>().data!!
        val deptCodeRegex = Regex("^[1-9](?:0[1-9]|[1-9]\\d)$")
        assertTrue(deptList.isNotEmpty() && deptList.all { deptCodeRegex.matches(it.code) })
        assertTrue(deptList.any { it.code == "101" })
        assertTrue(deptList.any { it.code == "201" })
        assertTrue(deptList.any { it.code == "301" })
        assertTrue(deptList.any { it.code == "601" })
        assertEquals("201", deptList.first { it.name == "전자공학과" }.code)

        val nextDomesticPreview = client.get("/api/auth/next-student-id?year=2029&department=${java.net.URLEncoder.encode("전자공학과", "UTF-8")}&isForeign=false")
            .body<ApiResponse<NextStudentIdResponse>>().data!!
        assertEquals("2029201001", nextDomesticPreview.studentId)

        val nextForeignPreview = client.get("/api/auth/next-student-id?year=2029&department=${java.net.URLEncoder.encode("전자공학과", "UTF-8")}&isForeign=true")
            .body<ApiResponse<NextStudentIdResponse>>().data!!
        assertEquals("2029201501", nextForeignPreview.studentId)

        val domStuAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "",
                    password = "stupass1234",
                    name = "김일반",
                    department = "전자공학과",
                    entryYear = 2029,
                    isForeign = false,
                    identifier = "AUTO",
                    courseIds = listOf(r4Course.id)
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<AuthResponse>>().data!!
        assertEquals("2029201001", domStuAuth.user.identifier)

        val forStuAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "",
                    password = "stupass1234",
                    name = "이외국",
                    department = "전자공학과",
                    entryYear = 2029,
                    isForeign = true,
                    identifier = "AUTO",
                    courseIds = listOf(r4Course.id)
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<AuthResponse>>().data!!
        assertEquals("2029201501", forStuAuth.user.identifier)

        // Random generation skips already-used serials and respects domestic (001~500) / foreign (501~999) ranges
        val randomDomesticPreview = client.get("/api/auth/next-student-id?year=2029&department=${java.net.URLEncoder.encode("전자공학과", "UTF-8")}&isForeign=false&random=true")
            .also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<ApiResponse<NextStudentIdResponse>>().data!!
        assertTrue(randomDomesticPreview.studentId.startsWith("2029201"))
        assertTrue(randomDomesticPreview.studentId != "2029201001")
        assertTrue(randomDomesticPreview.studentId.takeLast(3).toInt() in 1..500)

        val randomForeignPreview = client.get("/api/auth/next-student-id?year=2029&department=${java.net.URLEncoder.encode("전자공학과", "UTF-8")}&isForeign=true&random=true")
            .also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<ApiResponse<NextStudentIdResponse>>().data!!
        assertTrue(randomForeignPreview.studentId.startsWith("2029201"))
        assertTrue(randomForeignPreview.studentId != "2029201501")
        assertTrue(randomForeignPreview.studentId.takeLast(3).toInt() in 501..999)

        // Format-only department code validation: valid (1..9)(01..99) format accepted even if different from selected dept's code
        val crossDeptFormatRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "cross_dept_format_stu",
                    password = "stupass1234",
                    name = "형식허용학생",
                    department = "전자공학과",
                    identifier = "2029101001"
                )
            )
        }
        assertEquals(HttpStatusCode.Created, crossDeptFormatRes.status)

        // Invalid college digit (0) or invalid dept index (00) rejected by format validation
        val invalidCollegeDigitRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "bad_college_digit_stu",
                    password = "stupass1234",
                    name = "잘못된단과대",
                    department = "전자공학과",
                    identifier = "2029001001"
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, invalidCollegeDigitRes.status)

        val invalidDeptIdxRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "bad_dept_idx_stu",
                    password = "stupass1234",
                    name = "잘못된학과번호",
                    department = "전자공학과",
                    identifier = "2029100001"
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, invalidDeptIdxRes.status)

        // 3. Admin mode + thread open/close + immutable sequence numbers
        val qRes = client.post("/api/questions") {
            header(HttpHeaders.Authorization, "Bearer ${domStuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateQuestionRequest(
                    courseId = r4Course.id,
                    profId = noEmailProfAuth.user.id,
                    category = QuestionCategory.ASSIGNMENT,
                    title = "R4 스레드/순번/관리자 테스트 질문",
                    rawContent = "스레드 열림 및 순번 테스트입니다. #1 [사진 1]"
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<QuestionDetailDto>>().data!!
        assertFalse(qRes.isThreadOpen)
        assertEquals(1, qRes.lastSequenceNumber)

        // While closed, peer student cannot answer
        val closedPeerAttempt = client.post("/api/questions/${qRes.id}/answers") {
            header(HttpHeaders.Authorization, "Bearer ${forStuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "닫힘 상태에서 다른 학생 답변 시도"))
        }
        assertEquals(HttpStatusCode.Forbidden, closedPeerAttempt.status)

        // Even while closed, admin can post in ANY thread (becomes #2)
        val adminAns = client.post("/api/questions/${qRes.id}/answers") {
            header(HttpHeaders.Authorization, "Bearer ${adminProfAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "관리자 안내 글입니다."))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<AnswerDto>>().data!!
        assertEquals(2, adminAns.sequenceNumber)
        assertTrue(adminAns.isAdminAuthor)
        assertEquals("관리자", adminAns.authorTitle)

        // Assigned professor toggles thread to Open (열림)
        val openedQ = client.patch("/api/questions/${qRes.id}/thread-open") {
            header(HttpHeaders.Authorization, "Bearer ${noEmailProfAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(ToggleThreadOpenRequest(isThreadOpen = true))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<ApiResponse<QuestionDetailDto>>().data!!
        assertTrue(openedQ.isThreadOpen)

        // Non-assigned user (peer student) CANNOT toggle thread open/closed
        val nonProfToggle = client.patch("/api/questions/${qRes.id}/thread-open") {
            header(HttpHeaders.Authorization, "Bearer ${forStuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(ToggleThreadOpenRequest(isThreadOpen = false))
        }
        assertEquals(HttpStatusCode.Forbidden, nonProfToggle.status)

        // Peer student answers twice (#3 and #4) while thread is open
        val peerAns3 = client.post("/api/questions/${qRes.id}/answers") {
            header(HttpHeaders.Authorization, "Bearer ${forStuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "다른 학생 답변 1 (#3)"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<AnswerDto>>().data!!
        assertEquals(3, peerAns3.sequenceNumber)
        assertEquals("학생 답변", peerAns3.authorTitle)

        val peerAns4 = client.post("/api/questions/${qRes.id}/answers") {
            header(HttpHeaders.Authorization, "Bearer ${forStuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "다른 학생 답변 2 (#4)"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<AnswerDto>>().data!!
        assertEquals(4, peerAns4.sequenceNumber)

        // Assigned professor hides #4 and deletes #3 -> #4 retains sequenceNumber == 4, next answer gets #5!
        val hidePeer4Res = client.patch("/api/questions/${qRes.id}/answers/${peerAns4.id}/hide") {
            header(HttpHeaders.Authorization, "Bearer ${noEmailProfAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(ToggleHideRequest(isHidden = true))
        }
        assertEquals(HttpStatusCode.OK, hidePeer4Res.status)

        val delPeer3Res = client.delete("/api/questions/${qRes.id}/answers/${peerAns3.id}") {
            header(HttpHeaders.Authorization, "Bearer ${noEmailProfAuth.token}")
        }
        assertEquals(HttpStatusCode.OK, delPeer3Res.status)

        val detailAfterDelete = client.get("/api/questions/${qRes.id}") {
            header(HttpHeaders.Authorization, "Bearer ${domStuAuth.token}")
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals(4, detailAfterDelete.lastSequenceNumber)
        assertEquals(listOf(2, 4), detailAfterDelete.answers.map { it.sequenceNumber })

        val profAns5 = client.post("/api/questions/${qRes.id}/answers") {
            header(HttpHeaders.Authorization, "Bearer ${noEmailProfAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "담당교수 공식 답변 (#5)"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<AnswerDto>>().data!!
        assertEquals(5, profAns5.sequenceNumber)

        // Assigned professor CANNOT hide non-student (admin) answer #2
        val hideAdminAttempt = client.patch("/api/questions/${qRes.id}/answers/${adminAns.id}/hide") {
            header(HttpHeaders.Authorization, "Bearer ${noEmailProfAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(ToggleHideRequest(isHidden = true))
        }
        assertEquals(HttpStatusCode.Forbidden, hideAdminAttempt.status)

        // 4. Help level (NONE, SOME, ALL) & admin-only exhibitionMode settings persistence
        val initialSettings = client.get("/api/user/settings") {
            header(HttpHeaders.Authorization, "Bearer ${domStuAuth.token}")
        }.body<ApiResponse<UserSettingDto>>().data!!
        assertFalse(initialSettings.exhibitionMode)

        val updatedHelp = client.put("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer ${domStuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(UpdateUnifiedSettingRequest(helpLevel = "ALL"))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<UnifiedSettingDto>()
        assertEquals("ALL", updatedHelp.helpLevel)
        assertFalse(updatedHelp.exhibitionMode)

        val stuExhibitionUnifiedAttempt = client.put("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer ${domStuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(UpdateUnifiedSettingRequest(exhibitionMode = true))
        }
        assertEquals(HttpStatusCode.Forbidden, stuExhibitionUnifiedAttempt.status)

        val stuExhibitionUserAttempt = client.put("/api/user/settings") {
            header(HttpHeaders.Authorization, "Bearer ${domStuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(UpdateUserSettingRequest(exhibitionMode = true))
        }
        assertEquals(HttpStatusCode.Forbidden, stuExhibitionUserAttempt.status)

        val adminUpdatedExhibition = client.put("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer ${adminProfAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(UpdateUnifiedSettingRequest(exhibitionMode = true))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<UnifiedSettingDto>()
        assertTrue(adminUpdatedExhibition.exhibitionMode)

        val fetchedHelp = client.get("/api/user/settings") {
            header(HttpHeaders.Authorization, "Bearer ${domStuAuth.token}")
        }.body<ApiResponse<UserSettingDto>>().data!!
        assertEquals("ALL", fetchedHelp.helpLevel)
        assertFalse(fetchedHelp.exhibitionMode)

        val fetchedAdminUnified = client.get("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer ${adminProfAuth.token}")
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<UnifiedSettingDto>()
        assertTrue(fetchedAdminUnified.exhibitionMode)
    }

    @Test
    fun testMultiPerspectiveNotifications() = testApplication {
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // Admin creates a course with a very long (>200 chars) name to verify VARCHAR(200) safe truncation
        val adminAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.PROFESSOR,
                    username = "notif_admin_prof",
                    password = "adminpass123",
                    name = "알림관리교수",
                    department = "소프트웨어학과"
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<AuthResponse>>().data!!
        com.gonghak.service.UserService().grantOrRevokeAdminByUsername("notif_admin_prof", grant = true)

        val longCourseName = "매우긴과목명_" + "공".repeat(90)
        val longCourse = client.post("/api/admin/courses") {
            header(HttpHeaders.Authorization, "Bearer ${adminAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateCourseRequest(
                    name = longCourseName,
                    code = "NTF-999",
                    semester = "2026-2",
                    scopeType = "ALL"
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<CourseDto>>().data!!

        val assignedProf = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.PROFESSOR,
                    username = "notif_assigned_prof",
                    password = "profpass1234",
                    name = "담당박교수",
                    department = "소프트웨어학과",
                    courseIds = listOf(longCourse.id)
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<AuthResponse>>().data!!

        val coProf = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.PROFESSOR,
                    username = "notif_co_prof",
                    password = "profpass1234",
                    name = "공동이교수",
                    department = "소프트웨어학과",
                    courseIds = listOf(longCourse.id)
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<AuthResponse>>().data!!

        val authorStu = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "notif_author_stu",
                    password = "stupass1234",
                    name = "질문학생",
                    department = "소프트웨어학과",
                    identifier = "AUTO",
                    courseIds = listOf(longCourse.id)
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<AuthResponse>>().data!!

        val peerStu = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "notif_peer_stu",
                    password = "stupass1234",
                    name = "비밀실명학우",
                    department = "소프트웨어학과",
                    identifier = "AUTO",
                    courseIds = listOf(longCourse.id)
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<AuthResponse>>().data!!

        // 1. Student posts a LECTURE question -> assigned prof + opted-in co-prof notified without length overflow
        val qCreated = client.post("/api/questions") {
            header(HttpHeaders.Authorization, "Bearer ${authorStu.token}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateQuestionRequest(
                    courseId = longCourse.id,
                    profId = assignedProf.user.id,
                    category = QuestionCategory.LECTURE,
                    title = "다중 시점 알림 테스트 질문",
                    rawContent = "열린 스레드에서 다양한 참여자의 알림 동작을 검증합니다."
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<QuestionDetailDto>>().data!!

        client.patch("/api/questions/${qCreated.id}/thread-open") {
            header(HttpHeaders.Authorization, "Bearer ${assignedProf.token}")
            contentType(ContentType.Application.Json)
            setBody(ToggleThreadOpenRequest(isThreadOpen = true))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        val assignedAfterCreate = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${assignedProf.token}")
        }.body<ApiResponse<List<NotificationDto>>>().data!!
        assertTrue(assignedAfterCreate.any { it.type == "QUESTION_NEW" && it.questionId == qCreated.id && (it.title?.length ?: 0) <= 200 })

        val coProfAfterCreate = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${coProf.token}")
        }.body<ApiResponse<List<NotificationDto>>>().data!!
        assertTrue(coProfAfterCreate.any { it.type == "OTHER_PROFESSOR_QUESTION" && it.questionId == qCreated.id && (it.title?.length ?: 0) <= 200 })

        // 2. Co-professor posts an answer -> student author AND assigned professor both receive ANSWER_NEW
        client.post("/api/questions/${qCreated.id}/answers") {
            header(HttpHeaders.Authorization, "Bearer ${coProf.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "공동교수님의 첫 번째 답변입니다."))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        val stuAfterCoProf = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${authorStu.token}")
        }.body<ApiResponse<List<NotificationDto>>>().data!!
        assertTrue(stuAfterCoProf.any { it.type == "ANSWER_NEW" && it.questionId == qCreated.id })

        val assignedAfterCoProf = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${assignedProf.token}")
        }.body<ApiResponse<List<NotificationDto>>>().data!!
        assertTrue(assignedAfterCoProf.any { it.type == "ANSWER_NEW" && it.questionId == qCreated.id })

        // 3. Peer student replies -> student author + both professors receive ANSWER_NEW, without exposing peer raw real name
        client.post("/api/questions/${qCreated.id}/answers") {
            header(HttpHeaders.Authorization, "Bearer ${peerStu.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "다른 학우의 보충 의견입니다."))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        val stuAfterPeer = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${authorStu.token}")
        }.body<ApiResponse<List<NotificationDto>>>().data!!
        assertTrue(stuAfterPeer.any { it.type == "ANSWER_NEW" && it.questionId == qCreated.id && !(it.message ?: "").contains("비밀실명학우") })

        val coProfAfterPeer = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${coProf.token}")
        }.body<ApiResponse<List<NotificationDto>>>().data!!
        assertTrue(coProfAfterPeer.any { it.type == "ANSWER_NEW" && it.questionId == qCreated.id })

        // 4. Student posts follow-up -> BOTH assigned prof and participating co-prof are notified
        client.post("/api/questions/${qCreated.id}/followups") {
            header(HttpHeaders.Authorization, "Bearer ${authorStu.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateFollowUpRequest(content = "추가 질문(꼬리질문)입니다."))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        val assignedAfterFollowUp = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${assignedProf.token}")
        }.body<ApiResponse<List<NotificationDto>>>().data!!
        assertTrue(assignedAfterFollowUp.any { it.type == "QUESTION_NEW" && it.questionId == qCreated.id && it.title?.contains("추가 질문") == true })

        val coProfAfterFollowUp = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${coProf.token}")
        }.body<ApiResponse<List<NotificationDto>>>().data!!
        assertTrue(coProfAfterFollowUp.any { it.type == "OTHER_PROFESSOR_QUESTION" && it.questionId == qCreated.id && it.title?.contains("추가 질문") == true })

        // 5. Student deletes question -> BOTH assigned prof and participating co-prof receive QUESTION_DELETED
        client.delete("/api/questions/${qCreated.id}") {
            header(HttpHeaders.Authorization, "Bearer ${authorStu.token}")
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        val assignedAfterDelete = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${assignedProf.token}")
        }.body<ApiResponse<List<NotificationDto>>>().data!!
        assertTrue(assignedAfterDelete.any { it.type == "QUESTION_DELETED" && it.questionId == qCreated.id })

        val coProfAfterDelete = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${coProf.token}")
        }.body<ApiResponse<List<NotificationDto>>>().data!!
        assertTrue(coProfAfterDelete.any { it.type == "QUESTION_DELETED" && it.questionId == qCreated.id })
    }

    @Test
    fun testUnregisteredPlaceholderAndHomonymProfessorIsolation() = testApplication {
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // Seed admin & two separate courses sharing a timetable placeholder homonym ("김동명")
        val adminAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.PROFESSOR,
                    username = "homonym_admin",
                    password = "adminpass1234",
                    name = "관리자",
                    department = "소프트웨어학과"
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<AuthResponse>>().data!!
        com.gonghak.service.UserService().grantOrRevokeAdminByUsername("homonym_admin", grant = true)

        val courseA = client.post("/api/admin/courses") {
            header(HttpHeaders.Authorization, "Bearer ${adminAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateCourseRequest(name = "동명이인과목A", code = "HOM-101", semester = "2026-2", department = "전자공학과", scopeType = "DEPARTMENT"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.body<ApiResponse<CourseDto>>().data!!

        val courseB = client.post("/api/admin/courses") {
            header(HttpHeaders.Authorization, "Bearer ${adminAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateCourseRequest(name = "동명이인과목B", code = "HOM-201", semester = "2026-2", department = "소프트웨어학과", scopeType = "DEPARTMENT"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.body<ApiResponse<CourseDto>>().data!!

        val placeholderId = UUID.randomUUID()
        transaction {
            UsersTable.insert {
                it[id] = placeholderId
                it[name] = "김동명"
                it[role] = UserRole.PROFESSOR.name
                it[identifier] = "P_HOM_1"
                it[department] = "공통"
                it[displayTag] = "김동명 교수"
                it[isDemo] = true
            }
            CourseProfessorsTable.insert {
                it[courseId] = UUID.fromString(courseA.id)
                it[professorId] = placeholderId
                it[section] = "01분반"
                it[schedule] = "월 1교시"
            }
            CourseProfessorsTable.insert {
                it[courseId] = UUID.fromString(courseB.id)
                it[professorId] = placeholderId
                it[section] = "01분반"
                it[schedule] = "화 2교시"
            }
        }

        // 1. Before real registration, both courses expose placeholder with isRegistered = false
        val courseABefore = client.get("/api/courses/${courseA.id}").body<ApiResponse<CourseDto>>().data!!
        assertEquals("김동명", courseABefore.assignedProfessorName)
        assertFalse(courseABefore.assignedProfessorRegistered)
        assertFalse(courseABefore.professors.single().isRegistered)

        // Student enrolls in courseA and posts a question while only placeholder exists
        val stuAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "homonym_stu_1",
                    password = "stupass1234",
                    name = "이학생",
                    department = "전자공학과",
                    identifier = "AUTO",
                    courseIds = listOf(courseA.id)
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.body<ApiResponse<AuthResponse>>().data!!

        val qOnPlaceholder = client.post("/api/questions") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateQuestionRequest(courseId = courseA.id, category = QuestionCategory.LECTURE, title = "가입 전 질문", rawContent = "질문 본문입니다."))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals(placeholderId.toString(), qOnPlaceholder.assignedProfessorId)

        // 2. Real Professor A ("김동명", 전자공학과) registers ONLY for courseA
        val profA = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.PROFESSOR,
                    username = "kim_dm_elec",
                    password = "profpass1234",
                    name = "김동명",
                    department = "전자공학과",
                    courseIds = listOf(courseA.id)
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.body<ApiResponse<AuthResponse>>().data!!

        // courseA is claimed by real prof A (and existing question migrates to A); courseB remains unregistered!
        val courseAAfterA = client.get("/api/courses/${courseA.id}").body<ApiResponse<CourseDto>>().data!!
        assertEquals(profA.user.id, courseAAfterA.assignedProfessorId)
        assertTrue(courseAAfterA.assignedProfessorRegistered)
        assertTrue(courseAAfterA.professors.single().isRegistered)

        val migratedQ = client.get("/api/questions/${qOnPlaceholder.id}") {
            header(HttpHeaders.Authorization, "Bearer ${profA.token}")
        }.also { assertEquals(HttpStatusCode.OK, it.status) }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals(profA.user.id, migratedQ.assignedProfessorId)

        val courseBAfterA = client.get("/api/courses/${courseB.id}").body<ApiResponse<CourseDto>>().data!!
        assertEquals(placeholderId.toString(), courseBAfterA.assignedProfessorId)
        assertFalse(courseBAfterA.assignedProfessorRegistered, "Homonym registration on courseA must NEVER hijack courseB")

        // 3. Second homonym Professor B ("김동명", 소프트웨어학과) registers ONLY for courseB
        val profB = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.PROFESSOR,
                    username = "kim_dm_soft",
                    password = "profpass1234",
                    name = "김동명",
                    department = "소프트웨어학과",
                    courseIds = listOf(courseB.id)
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.body<ApiResponse<AuthResponse>>().data!!
        assertNotEquals(profA.user.id, profB.user.id)

        val courseBAfterB = client.get("/api/courses/${courseB.id}").body<ApiResponse<CourseDto>>().data!!
        assertEquals(profB.user.id, courseBAfterB.assignedProfessorId)
        assertTrue(courseBAfterB.assignedProfessorRegistered)
    }
}
