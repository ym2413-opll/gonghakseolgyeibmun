package com.gonghak

import com.gonghak.dto.*
import com.gonghak.models.QuestionCategory
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

    private val studentId = "11111111-1111-1111-1111-111111111101" // 서영민
    private val assignedProfId = "22222222-2222-2222-2222-222222222201" // 김교수 (담당교수)
    private val otherProfId = "22222222-2222-2222-2222-222222222202" // 이교수 (같은 과목 다른 교수)
    private val courseId = "33333333-3333-3333-3333-333333333301" // 공학설계입문

    @Test
    fun testAuthAndDemoUsers() = testApplication {

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val res = client.get("/api/auth/users")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.body<ApiResponse<List<UserDto>>>()
        assertTrue(body.success)
        assertNotNull(body.data)
        assertTrue(body.data!!.size >= 6)

        // Check current me
        val meRes = client.get("/api/auth/me") {
            header("X-User-Id", studentId)
        }
        val meBody = meRes.body<ApiResponse<UserDto>>()
        assertEquals("서영민", meBody.data?.name)
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
            header("X-User-Id", studentId)
            contentType(ContentType.Application.Json)
            setBody(failReq)
        }
        assertEquals(HttpStatusCode.BadRequest, failRes.status)

        // Grade question with isAnonymous = false should SUCCEED
        val successReq = failReq.copy(isAnonymous = false)
        val successRes = client.post("/api/questions") {
            header("X-User-Id", studentId)
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
            header("X-User-Id", studentId)
            contentType(ContentType.Application.Json)
            setBody(anonReq)
        }
        assertEquals(HttpStatusCode.Created, createRes.status)
        val qId = createRes.body<ApiResponse<QuestionDetailDto>>().data!!.id

        // Viewed by author: should indicate it's mine
        val authorViewRes = client.get("/api/questions/$qId") {
            header("X-User-Id", studentId)
        }
        val authorView = authorViewRes.body<ApiResponse<QuestionDetailDto>>().data!!
        assertTrue(authorView.isMyQuestion)
        assertTrue(authorView.authorDisplayName.contains("(나)"))

        // Viewed by professor: should be masked as "[Course] 수강생"
        val profViewRes = client.get("/api/questions/$qId") {
            header("X-User-Id", assignedProfId)
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
            category = QuestionCategory.ASSIGNMENT,
            title = "과제 질문 - 다른 교수 답변 제한 테스트",
            rawContent = "과제 제출 형식 문의합니다.",
            refinedContent = "안녕하십니까 교수님 과제 제출 형식 문의합니다.",
            isAnonymous = false
        )
        val createAssignRes = client.post("/api/questions") {
            header("X-User-Id", studentId)
            contentType(ContentType.Application.Json)
            setBody(assignReq)
        }
        assertEquals(HttpStatusCode.Created, createAssignRes.status)
        val assignQId = createAssignRes.body<ApiResponse<QuestionDetailDto>>().data!!.id

        // Other professor attempts to answer assignment -> Forbidden
        val otherAnsRes = client.post("/api/questions/$assignQId/answers") {
            header("X-User-Id", otherProfId)
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "제가 대신 답변드립니다."))
        }
        assertEquals(HttpStatusCode.Forbidden, otherAnsRes.status)

        // 2. Lecture Category: other professor CAN answer if assigned professor hasn't answered yet
        val lectureReq = CreateQuestionRequest(
            courseId = courseId,
            category = QuestionCategory.LECTURE,
            title = "강의 질문 - 공동 과목 다른 교수 답변 테스트",
            rawContent = "공동 과목 강의 질문입니다.",
            refinedContent = "안녕하십니까 교수님 공동 과목 강의 질문입니다.",
            isAnonymous = false
        )
        val createLectureRes = client.post("/api/questions") {
            header("X-User-Id", studentId)
            contentType(ContentType.Application.Json)
            setBody(lectureReq)
        }
        assertEquals(HttpStatusCode.Created, createLectureRes.status)
        val lectureQId = createLectureRes.body<ApiResponse<QuestionDetailDto>>().data!!.id

        // Other professor answers first -> Allowed
        val otherAnsLecRes = client.post("/api/questions/$lectureQId/answers") {
            header("X-User-Id", otherProfId)
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "다른 분반 담당 이교수입니다. 질문에 대한 답변입니다."))
        }
        assertEquals(HttpStatusCode.Created, otherAnsLecRes.status)

        // Assigned professor now answers
        val assignedAnsRes = client.post("/api/questions/$lectureQId/answers") {
            header("X-User-Id", assignedProfId)
            contentType(ContentType.Application.Json)
            setBody(CreateAnswerRequest(content = "담당 김교수입니다. 추가 보충 설명드립니다."))
        }
        assertEquals(HttpStatusCode.Created, assignedAnsRes.status)

        // After assigned professor answered, other professor can NO LONGER answer
        val detailAfterRes = client.get("/api/questions/$lectureQId") {
            header("X-User-Id", otherProfId)
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
        val gradeSimRes = client.get("/api/questions/similar?courseId=$courseId&category=GRADE&query=성적")
        assertEquals(HttpStatusCode.OK, gradeSimRes.status)
        val gradeSimBody = gradeSimRes.body<ApiResponse<List<SimilarQuestionDto>>>()
        assertTrue(gradeSimBody.data!!.isEmpty())

        // LECTURE category -> should find similar question seeded in DB
        val lecSimRes = client.get("/api/questions/similar?courseId=$courseId&category=LECTURE&query=Ktor 라우팅")
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
            header("X-User-Id", studentId)
        }
        assertEquals(HttpStatusCode.OK, notifRes.status)
        val notifs = notifRes.body<ApiResponse<List<NotificationDto>>>().data!!
        assertNotNull(notifs)

        // Update professor notification settings
        val updateRes = client.put("/api/professor/settings") {
            header("X-User-Id", assignedProfId)
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
            header("X-User-Id", assignedProfId)
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
            category = QuestionCategory.GRADE,
            title = "비활성화된 카테고리 질문 시도",
            rawContent = "성적 문의드립니다.",
            refinedContent = "안녕하십니까 교수님 성적 문의드립니다.",
            isAnonymous = false
        )
        val tryGradeRes = client.post("/api/questions") {
            header("X-User-Id", studentId)
            contentType(ContentType.Application.Json)
            setBody(tryGradeReq)
        }
        assertEquals(HttpStatusCode.Forbidden, tryGradeRes.status)

        // Restore category to enabled
        client.put("/api/courses/$courseId/categories") {
            header("X-User-Id", assignedProfId)
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

        // 1. GET /api/notifications?userId=user-stu-001 (Previously failed with 400 Bad Request & SerializationException)
        val notifRes = client.get("/api/notifications?userId=user-stu-001")
        assertEquals(HttpStatusCode.OK, notifRes.status)
        val notifBody = notifRes.body<ApiResponse<List<NotificationDto>>>()
        assertTrue(notifBody.success)
        assertNotNull(notifBody.data)

        // 2. GET /api/settings?userId=user-stu-001 (Previously failed with 404 Not Found)
        val settingsRes = client.get("/api/settings?userId=user-stu-001")
        assertEquals(HttpStatusCode.OK, settingsRes.status)
        val settingsBody = settingsRes.body<UnifiedSettingDto>()
        assertNotNull(settingsBody.theme)
        assertNotNull(settingsBody.fontSize)

        // 3. POST /api/settings
        val updateSettingsRes = client.post("/api/settings") {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateUnifiedSettingRequest(
                    userId = "user-stu-001",
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
        val badFormatRes = client.get("/api/questions/nonexistent-id")
        assertEquals(HttpStatusCode.BadRequest, badFormatRes.status)
        val errBody = badFormatRes.body<ErrorResponse>()
        assertFalse(errBody.success)

        val notFoundRes = client.get("/api/questions/00000000-0000-0000-0000-000000000000")
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
        assertTrue(summary.totalCourses >= 800, "Should have loaded at least 800 courses from converted data")
        assertTrue(summary.totalSections >= 1200, "Should have loaded at least 1200 sections from converted data")
        assertTrue(summary.totalProfessors >= 600, "Should have loaded at least 600 professors from converted data")
        assertTrue(summary.colleges.isNotEmpty())

        // 2. Search course by name: 공학설계입문
        val searchNameRes = client.get("/api/courses/search?query=공학설계입문")
        assertEquals(HttpStatusCode.OK, searchNameRes.status)
        val searchNameBody = searchNameRes.body<ApiResponse<CourseListResponse>>()
        assertTrue(searchNameBody.success)
        assertNotNull(searchNameBody.data)
        val courses = searchNameBody.data!!.courses
        assertTrue(courses.isNotEmpty())
        val engDesign = courses.find { it.name == "공학설계입문" && it.code == "7000-1-2957" }
        assertNotNull(engDesign)
        assertEquals("33333333-3333-3333-3333-333333333301", engDesign.id)
        assertTrue(engDesign.professors.size >= 6, "공학설계입문 should have multiple professor sections")

        // 3. Search course by professor name: 지정주
        val searchProfRes = client.get("/api/courses/search?query=지정주")
        assertEquals(HttpStatusCode.OK, searchProfRes.status)
        val searchProfBody = searchProfRes.body<ApiResponse<CourseListResponse>>()
        assertTrue(searchProfBody.success)
        assertNotNull(searchProfBody.data)
        assertTrue(searchProfBody.data!!.courses.any { c -> c.professors.any { it.name == "지정주" } })

        // 4. Get specific course by ID
        val courseDetailRes = client.get("/api/courses/33333333-3333-3333-3333-333333333301")
        assertEquals(HttpStatusCode.OK, courseDetailRes.status)
        val courseDetailBody = courseDetailRes.body<ApiResponse<CourseDto>>()
        assertTrue(courseDetailBody.success)
        assertEquals("공학설계입문", courseDetailBody.data?.name)
        assertEquals("7000-1-2957", courseDetailBody.data?.code)

        // 5. Test alias: /api/courses/course-001
        val aliasCourseRes = client.get("/api/courses/course-001")
        assertEquals(HttpStatusCode.OK, aliasCourseRes.status)
        val aliasBody = aliasCourseRes.body<ApiResponse<CourseDto>>()
        assertTrue(aliasBody.success)
        assertEquals("공학설계입문", aliasBody.data?.name)
    }
}
