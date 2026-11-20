package com.gonghak

import com.gonghak.dto.*
import com.gonghak.models.QuestionCategory
import com.gonghak.service.PasswordSecurity
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplicationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val studentId = "11111111-1111-1111-1111-111111111101" // 이서준
    private val assignedProfId = "22222222-2222-2222-2222-222222222201" // 김교수 (담당교수)
    private val otherProfId = "22222222-2222-2222-2222-222222222202" // 이교수 (같은 과목 다른 교수)
    private val courseId = "33333333-3333-3333-3333-333333333301" // 공학설계입문

    private fun tokenFor(userId: String): String =
        "Bearer ${PasswordSecurity.generateAuthToken(UUID.fromString(userId))}"

    @Test
    fun testAuthAndDemoUsers() = testApplication {

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // Mockup mode enables demo user listing and X-User-Id / demo-login switching by default
        val res = client.get("/api/auth/users")
        assertEquals(HttpStatusCode.OK, res.status)
        val usersBody = res.body<ApiResponse<List<UserDto>>>()
        assertEquals(8, usersBody.data?.size, "Mockup should expose only the 8 seeded demo accounts (not timetable catalog professors)")

        // X-User-Id header works in mockup demo mode
        val demoMeRes = client.get("/api/auth/me") {
            header("X-User-Id", studentId)
        }
        assertEquals(HttpStatusCode.OK, demoMeRes.status)
        assertEquals("이서준", demoMeRes.body<ApiResponse<UserDto>>().data?.name)

        // Demo login endpoint issues auth response for demo account
        val demoLoginRes = client.post("/api/auth/demo-login") {
            contentType(ContentType.Application.Json)
            setBody(DemoLoginRequest(userId = studentId))
        }
        assertEquals(HttpStatusCode.OK, demoLoginRes.status)
        assertEquals("이서준", demoLoginRes.body<ApiResponse<AuthResponse>>().data?.user?.name)

        // Check current me with signed Bearer token
        val meRes = client.get("/api/auth/me") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
        }
        assertEquals(HttpStatusCode.OK, meRes.status)
        val meBody = meRes.body<ApiResponse<UserDto>>()
        assertEquals("이서준", meBody.data?.name)
    }

    @Test
    fun testAiRefine() = testApplication {

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val refineReq = RefineQuestionRequest(
            title = "성적이 왜 이래요",
            rawContent = "아니 교수야 성적이 이게 말이 되냐 차라리 재수강하게 C로 내려줘라",
            courseName = "공학설계입문",
            category = QuestionCategory.GRADE
        )
        val res = client.post("/api/ai/refine") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
            contentType(ContentType.Application.Json)
            setBody(refineReq)
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.body<ApiResponse<RefineQuestionResponse>>()
        assertTrue(body.success)
        val refined = body.data?.refinedContent ?: ""
        assertTrue(refined.contains("안녕하십니까") && refined.contains("교수님"))
        // 원문 날것의 표현이 직접 포함되지 않아야 함
        assertFalse(refined.contains("아니 교수야"))
        // 제목도 정중하고 명확한 학업 문의 양식으로 윤색되어야 함
        val refinedTitle = body.data?.refinedTitle ?: ""
        assertTrue(refinedTitle.contains("성적 문의"))
    }

    @Test
    fun testAnonymousRuleOnGradeCategory() = testApplication {

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // Grade question with isAnonymous = true should FAIL with 400 Bad Request
        val failReq = CreateQuestionRequest(
            courseId = courseId,
            category = QuestionCategory.GRADE,
            title = "성적 이의신청 문의",
            rawContent = "중간고사 3번 채점 기준 문의드립니다.",
            refinedContent = "안녕하십니까 교수님 중간고사 3번 채점 기준 문의드립니다.",
            isAnonymous = true // Invalid!
        )
        val failRes = client.post("/api/questions") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
            contentType(ContentType.Application.Json)
            setBody(failReq)
        }
        assertEquals(HttpStatusCode.BadRequest, failRes.status)

        // Grade question with isAnonymous = false should SUCCEED
        val successReq = failReq.copy(isAnonymous = false)
        val successRes = client.post("/api/questions") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
            contentType(ContentType.Application.Json)
            setBody(successReq)
        }
        assertEquals(HttpStatusCode.Created, successRes.status)
        val detail = successRes.body<ApiResponse<QuestionDetailDto>>().data
        assertNotNull(detail)
        assertEquals(false, detail.isAnonymous)
    }

    @Test
    fun testAnonymityMasking() = testApplication {

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // Post anonymous lecture question
        val anonReq = CreateQuestionRequest(
            courseId = courseId,
            category = QuestionCategory.LECTURE,
            title = "익명 강의 질문 테스트",
            rawContent = "Ktor 세션 관련 질문입니다.",
            refinedContent = "안녕하십니까 교수님 Ktor 세션 관련 질문입니다.",
            isAnonymous = true
        )
        val createRes = client.post("/api/questions") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
            contentType(ContentType.Application.Json)
            setBody(anonReq)
        }
        assertEquals(HttpStatusCode.Created, createRes.status)
        val qId = createRes.body<ApiResponse<QuestionDetailDto>>().data!!.id

        // Viewed by author: should indicate it's mine
        val authorViewRes = client.get("/api/questions/$qId") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
        }
        val authorView = authorViewRes.body<ApiResponse<QuestionDetailDto>>().data!!
        assertTrue(authorView.isMyQuestion)
        assertTrue(authorView.authorDisplayName.contains("(나)"))

        // Viewed by professor: should be masked as "[Course] 수강생"
        val profViewRes = client.get("/api/questions/$qId") {
            header(HttpHeaders.Authorization, tokenFor(assignedProfId))
        }
        val profView = profViewRes.body<ApiResponse<QuestionDetailDto>>().data!!
        assertFalse(profView.isMyQuestion)
        assertEquals("공학설계입문 수강생", profView.authorDisplayName)
    }

    @Test
    fun testSameCourseOtherProfessorRules() = testApplication {

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 1. Assignment Category: other professor cannot answer
        val assignReq = CreateQuestionRequest(
            courseId = courseId,
            profId = assignedProfId,
            category = QuestionCategory.ASSIGNMENT,
            title = "과제 질문 - 다른 교수 답변 제한 테스트",
            rawContent = "과제 제출 형식 문의합니다.",
            refinedContent = "안녕하십니까 교수님 과제 제출 형식 문의합니다.",
            isAnonymous = false
        )
        val createAssignRes = client.post("/api/questions") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
            contentType(ContentType.Application.Json)
            setBody(assignReq)
        }
        assertEquals(HttpStatusCode.Created, createAssignRes.status)
        val assignQId = createAssignRes.body<ApiResponse<QuestionDetailDto>>().data!!.id

        // Other professor attempts to answer assignment -> Forbidden
        val otherAnsRes = client.post("/api/questions/$assignQId/answers") {
            header(HttpHeaders.Authorization, tokenFor(otherProfId))
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "제가 대신 답변드립니다."))
        }
        assertEquals(HttpStatusCode.Forbidden, otherAnsRes.status)

        // 2. Lecture Category: closed by default; other professor blocked until assigned professor opens thread (열림)
        val lectureReq = CreateQuestionRequest(
            courseId = courseId,
            profId = assignedProfId,
            category = QuestionCategory.LECTURE,
            title = "강의 질문 - 공동 과목 다른 교수 답변 테스트",
            rawContent = "공동 과목 강의 질문입니다.",
            refinedContent = "안녕하십니까 교수님 공동 과목 강의 질문입니다.",
            isAnonymous = false
        )
        val createLectureRes = client.post("/api/questions") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
            contentType(ContentType.Application.Json)
            setBody(lectureReq)
        }
        assertEquals(HttpStatusCode.Created, createLectureRes.status)
        val lectureDetail = createLectureRes.body<ApiResponse<QuestionDetailDto>>().data!!
        val lectureQId = lectureDetail.id
        assertFalse(lectureDetail.isThreadOpen)

        // Closed by default -> other professor cannot answer yet
        val closedOtherAnsRes = client.post("/api/questions/$lectureQId/answers") {
            header(HttpHeaders.Authorization, tokenFor(otherProfId))
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "닫힘 상태에서 공동 답변 시도"))
        }
        assertEquals(HttpStatusCode.Forbidden, closedOtherAnsRes.status)

        // Assigned professor toggles thread to Open (열림)
        val openRes = client.patch("/api/questions/$lectureQId/thread-open") {
            header(HttpHeaders.Authorization, tokenFor(assignedProfId))
            contentType(ContentType.Application.Json)
            setBody(ToggleThreadOpenRequest(isThreadOpen = true))
        }
        assertEquals(HttpStatusCode.OK, openRes.status)
        assertTrue(openRes.body<ApiResponse<QuestionDetailDto>>().data!!.isThreadOpen)

        // Other professor answers first -> Allowed once thread is open
        val otherAnsLecRes = client.post("/api/questions/$lectureQId/answers") {
            header(HttpHeaders.Authorization, tokenFor(otherProfId))
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "다른 분반 담당 이교수입니다. 질문에 대한 답변입니다."))
        }
        assertEquals(HttpStatusCode.Created, otherAnsLecRes.status)

        // Assigned professor now answers
        val assignedAnsRes = client.post("/api/questions/$lectureQId/answers") {
            header(HttpHeaders.Authorization, tokenFor(assignedProfId))
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "담당 김교수입니다. 추가 보충 설명드립니다."))
        }
        assertEquals(HttpStatusCode.Created, assignedAnsRes.status)

        // After assigned professor answered, other professor can NO LONGER answer
        val detailAfterRes = client.get("/api/questions/$lectureQId") {
            header(HttpHeaders.Authorization, tokenFor(otherProfId))
        }
        val detailAfter = detailAfterRes.body<ApiResponse<QuestionDetailDto>>().data!!
        assertTrue(detailAfter.isAnsweredByAssignedProfessor)
        assertFalse(detailAfter.canAnswer) // Can answer is now false for other professor!
    }

    @Test
    fun testSimilarQuestions() = testApplication {

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // GRADE category -> must return empty list (feature disabled)
        val gradeSimRes = client.get("/api/questions/similar?courseId=$courseId&category=GRADE&query=성적") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
        }
        assertEquals(HttpStatusCode.OK, gradeSimRes.status)
        val gradeSimBody = gradeSimRes.body<ApiResponse<List<SimilarQuestionDto>>>()
        assertTrue(gradeSimBody.data!!.isEmpty())

        // LECTURE category -> should find similar question seeded in DB
        val lecSimRes = client.get("/api/questions/similar?courseId=$courseId&category=LECTURE&query=Ktor 라우팅") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
        }
        assertEquals(HttpStatusCode.OK, lecSimRes.status)
        val lecSimBody = lecSimRes.body<ApiResponse<List<SimilarQuestionDto>>>()
        assertTrue(lecSimBody.data!!.isNotEmpty())
    }

    @Test
    fun testNotificationAndQuietHours() = testApplication {

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // Check notifications for student
        val notifRes = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
        }
        assertEquals(HttpStatusCode.OK, notifRes.status)
        val notifs = notifRes.body<ApiResponse<List<NotificationDto>>>().data!!
        assertNotNull(notifs)

        // Update professor notification settings
        val updateRes = client.put("/api/professor/settings") {
            header(HttpHeaders.Authorization, tokenFor(assignedProfId))
            contentType(ContentType.Application.Json)
            setBody(
                UpdateProfessorNotificationSettingRequest(
                    quietHoursEnabled = true,
                    quietHoursStart = "23:00",
                    quietHoursEnd = "07:00",
                    notifyOtherProfessorQuestions = true
                )
            )
        }
        assertEquals(HttpStatusCode.OK, updateRes.status)
        val setting = updateRes.body<ApiResponse<ProfessorNotificationSettingDto>>().data!!
        assertTrue(setting.quietHoursEnabled)
        assertEquals("23:00", setting.quietHoursStart)
    }

    @Test
    fun testProfessorCategoryToggle() = testApplication {
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // Professor disables GRADE category
        val disableRes = client.put("/api/courses/$courseId/categories") {
            header(HttpHeaders.Authorization, tokenFor(assignedProfId))
            contentType(ContentType.Application.Json)
            setBody(
                UpdateCategorySettingRequest(
                    category = QuestionCategory.GRADE,
                    isEnabled = false
                )
            )
        }
        assertEquals(HttpStatusCode.OK, disableRes.status)

        // Student tries to submit GRADE question -> should be Forbidden
        val tryGradeReq = CreateQuestionRequest(
            courseId = courseId,
            profId = assignedProfId,
            category = QuestionCategory.GRADE,
            title = "비활성화된 카테고리 질문 시도",
            rawContent = "성적 문의드립니다.",
            refinedContent = "안녕하십니까 교수님 성적 문의드립니다.",
            isAnonymous = false
        )
        val tryGradeRes = client.post("/api/questions") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
            contentType(ContentType.Application.Json)
            setBody(tryGradeReq)
        }
        assertEquals(HttpStatusCode.Forbidden, tryGradeRes.status)

        // Restore category to enabled
        client.put("/api/courses/$courseId/categories") {
            header(HttpHeaders.Authorization, tokenFor(assignedProfId))
            contentType(ContentType.Application.Json)
            setBody(
                UpdateCategorySettingRequest(
                    category = QuestionCategory.GRADE,
                    isEnabled = true
                )
            )
        }
    }

    @Test
    fun testFrontendIntegrationEndpoints() = testApplication {
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 1. GET /api/notifications with authenticated Bearer token
        val notifRes = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
        }
        assertEquals(HttpStatusCode.OK, notifRes.status)
        val notifBody = notifRes.body<ApiResponse<List<NotificationDto>>>()
        assertTrue(notifBody.success)
        assertNotNull(notifBody.data)

        // 2. GET /api/settings
        val settingsRes = client.get("/api/settings") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
        }
        assertEquals(HttpStatusCode.OK, settingsRes.status)
        val settingsBody = settingsRes.body<UnifiedSettingDto>()
        assertNotNull(settingsBody.theme)
        assertNotNull(settingsBody.fontSize)

        // 3. POST /api/settings
        val updateSettingsRes = client.post("/api/settings") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
            contentType(ContentType.Application.Json)
            setBody(
                UpdateUnifiedSettingRequest(
                    theme = "dark",
                    fontSize = "lg"
                )
            )
        }
        assertEquals(HttpStatusCode.OK, updateSettingsRes.status)
        val updatedSettings = updateSettingsRes.body<UnifiedSettingDto>()
        assertEquals("dark", updatedSettings.theme)
        assertEquals("lg", updatedSettings.fontSize)

        // 4. POST /api/questions/similar (Frontend JSON format)
        val simPostRes = client.post("/api/questions/similar") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
            contentType(ContentType.Application.Json)
            setBody(
                SimilarQuestionRequest(
                    text = "Ktor 라우팅 설정",
                    category = QuestionCategory.LECTURE
                )
            )
        }
        assertEquals(HttpStatusCode.OK, simPostRes.status)
        val simPostBody = simPostRes.body<ApiResponse<List<SimilarQuestionDto>>>()
        assertTrue(simPostBody.success)
        assertNotNull(simPostBody.data)

        // 5. Verify error response does not crash with SerializationException
        val badFormatRes = client.get("/api/questions/nonexistent-id") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
        }
        assertEquals(HttpStatusCode.BadRequest, badFormatRes.status)
        val errBody = badFormatRes.body<ErrorResponse>()
        assertFalse(errBody.success)

        val notFoundRes = client.get("/api/questions/00000000-0000-0000-0000-000000000000") {
            header(HttpHeaders.Authorization, tokenFor(studentId))
        }
        assertEquals(HttpStatusCode.NotFound, notFoundRes.status)
    }

    @Test
    fun testConvertedCourseTimetableData() = testApplication {
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 1. Check summary endpoint
        val summaryRes = client.get("/api/courses/summary")
        assertEquals(HttpStatusCode.OK, summaryRes.status)
        val summaryBody = summaryRes.body<ApiResponse<CourseSummaryDto>>()
        assertTrue(summaryBody.success)
        assertNotNull(summaryBody.data)
        val summary = summaryBody.data!!
        assertEquals("2026-2", summary.semester)
        assertTrue(summary.totalCourses >= 20, "Mockup should have loaded trimmed AI Convergence College courses")
        assertTrue(summary.totalSections >= 20, "Mockup should have loaded trimmed AI Convergence College sections")
        assertTrue(summary.totalProfessors >= 10, "Mockup should have loaded AI Convergence College professors")
        assertTrue(summary.colleges.contains("인공지능융합대학"))

        // 2. Search course by name: 공학설계입문
        val searchNameRes = client.get("/api/courses/search?query=공학설계입문")
        assertEquals(HttpStatusCode.OK, searchNameRes.status)
        val searchNameBody = searchNameRes.body<ApiResponse<CourseListResponse>>()
        assertTrue(searchNameBody.success)
        assertNotNull(searchNameBody.data)
        val courses = searchNameBody.data!!.courses
        assertTrue(courses.isNotEmpty())
        val engDesign = courses.find { it.name == "공학설계입문" && it.code == "I000-1-2957" }
        assertNotNull(engDesign)
        assertTrue(engDesign.professors.size >= 3, "공학설계입문 should have multiple professor sections")

        // 3. Search course by professor name: 장진곤
        val searchProfRes = client.get("/api/courses/search?query=장진곤")
        assertEquals(HttpStatusCode.OK, searchProfRes.status)
        val searchProfBody = searchProfRes.body<ApiResponse<CourseListResponse>>()
        assertTrue(searchProfBody.success)
        assertNotNull(searchProfBody.data)
        assertTrue(searchProfBody.data!!.courses.any { c -> c.professors.any { it.name == "장진곤" } })

        // 4. Get specific course by ID
        val courseDetailRes = client.get("/api/courses/33333333-3333-3333-3333-333333333301")
        assertEquals(HttpStatusCode.OK, courseDetailRes.status)
        val courseDetailBody = courseDetailRes.body<ApiResponse<CourseDto>>()
        assertTrue(courseDetailBody.success)
        assertEquals("공학설계입문", courseDetailBody.data?.name)

        // 5. Test alias: /api/courses/course-001
        val aliasCourseRes = client.get("/api/courses/course-001")
        assertEquals(HttpStatusCode.OK, aliasCourseRes.status)
        val aliasBody = aliasCourseRes.body<ApiResponse<CourseDto>>()
        assertTrue(aliasBody.success)
        assertEquals("공학설계입문", aliasBody.data?.name)
    }
}
