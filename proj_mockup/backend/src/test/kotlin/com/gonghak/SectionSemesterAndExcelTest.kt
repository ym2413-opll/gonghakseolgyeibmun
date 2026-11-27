package com.gonghak

import com.gonghak.dto.*
import com.gonghak.models.*
import com.gonghak.service.ExcelCourseImporter
import com.gonghak.service.PasswordSecurity
import com.gonghak.util.SemesterUtils
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class SectionSemesterAndExcelTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val student1Id = "11111111-1111-1111-1111-111111111101"
    private val student2Id = "11111111-1111-1111-1111-111111111102"
    private val prof1Id = "22222222-2222-2222-2222-222222222201" // 김교수 (01분반)
    private val prof2Id = "22222222-2222-2222-2222-222222222202" // 이교수 (02분반)
    private val demoCourseId = "33333333-3333-3333-3333-333333333301" // 공학설계입문

    private fun bearerToken(userId: String): String =
        "Bearer ${PasswordSecurity.generateAuthToken(UUID.fromString(userId))}"

    private fun ensureAdminUser(): String {
        val adminUuid = UUID.fromString("99999999-9999-9999-9999-999999999901")
        transaction {
            if (UsersTable.select { UsersTable.id eq adminUuid }.count() == 0L) {
                UsersTable.insert {
                    it[id] = adminUuid
                    it[username] = "test_super_admin"
                    it[passwordHash] = PasswordSecurity.hashPassword("adminpass123")
                    it[name] = "관리자"
                    it[role] = UserRole.ADMIN.name
                    it[identifier] = "ADMIN01"
                    it[department] = "본부"
                    it[displayTag] = "시스템 관리자"
                    it[isAdmin] = true
                    it[isDemo] = false
                }
            }
        }
        return adminUuid.toString()
    }

    private fun buildMinimalXlsx(bannerText: String, rows: List<List<String>>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("xl/workbook.xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <workbook><sheets><sheet name="강의시간표" sheetId="1" r:id="rId1"/></sheets></workbook>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8"?><worksheet><sheetData>""")
            // Row 2: Banner
            sb.append("""<row r="2"><c r="B2" t="inlineStr"><is><t>$bannerText</t></is></c></row>""")
            // Row 3: Official KW header layout
            val headers = mapOf(
                "B" to "NO",
                "C" to "개설대학",
                "D" to "개설학과",
                "E" to "개설전공",
                "G" to "학정번호",
                "H" to "과목명",
                "R" to "이수구분",
                "T" to "학점",
                "V" to "담당교수",
                "Y" to "요일1",
                "AB" to "요일2"
            )
            sb.append("""<row r="3">""")
            headers.forEach { (col, text) ->
                sb.append("""<c r="${col}3" t="inlineStr"><is><t>$text</t></is></c>""")
            }
            sb.append("""</row>""")
            // Row 4: Subheader
            sb.append("""<row r="4"><c r="Y4" t="inlineStr"><is><t>일</t></is></c><c r="Z4" t="inlineStr"><is><t>시간</t></is></c><c r="AA4" t="inlineStr"><is><t>강의실</t></is></c></row>""")

            rows.forEachIndexed { idx, data ->
                val rNum = idx + 5
                sb.append("""<row r="$rNum">""")
                val cols = listOf("C", "D", "E", "G", "H", "R", "T", "V", "Y", "Z", "AA")
                cols.forEachIndexed { cIdx, colLetter ->
                    val v = data.getOrNull(cIdx).orEmpty()
                    if (v.isNotEmpty()) {
                        sb.append("""<c r="$colLetter$rNum" t="inlineStr"><is><t>$v</t></is></c>""")
                    }
                }
                sb.append("""</row>""")
            }
            sb.append("""</sheetData></worksheet>""")

            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(sb.toString().toByteArray())
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    @Test
    fun testSeasonalSemesterNormalizationAndOrdering() {
        assertEquals("2026-1", SemesterUtils.normalizeSemester("2026학년도 1학기"))
        assertEquals("2026-여름", SemesterUtils.normalizeSemester("2026학년도 여름계절학기"))
        assertEquals("2026-여름", SemesterUtils.normalizeSemester("2026-하계"))
        assertEquals("2026-2", SemesterUtils.normalizeSemester("2026-02"))
        assertEquals("2026-겨울", SemesterUtils.normalizeSemester("2026-겨울학기"))
        assertEquals("2026-겨울", SemesterUtils.normalizeSemester("2026-동계"))

        val ordered = listOf("2027-1", "2026-겨울", "2026-1", "2026-2", "2026-여름")
            .sortedWith(SemesterUtils::compareSemesters)
        assertEquals(listOf("2026-1", "2026-여름", "2026-2", "2026-겨울", "2027-1"), ordered)

        val (base, sec) = SemesterUtils.splitCourseCodeAndSection("7000-01-2608-03")
        assertEquals("7000-01-2608", base)
        assertEquals("03분반", sec)
    }

    @Test
    fun testRealWorkspaceExcelFileParsingIfPresent() {
        val candidates = listOf(
            File("../../2026학년도 2학기 개설과목 및 강의시간표 (폐강정정기간).xlsx"),
            File("2026학년도 2학기 개설과목 및 강의시간표 (폐강정정기간).xlsx")
        )
        val realXlsx = candidates.firstOrNull { it.isFile } ?: return
        val parsed = ExcelCourseImporter.parseXlsx(realXlsx.readBytes())
        assertEquals("2026-2", parsed.detectedSemester)
        assertTrue(parsed.sections.size >= 1200, "Expected >=1200 section rows, got ${parsed.sections.size}")
        assertTrue(parsed.sections.any { it.courseName == "공학설계입문" && it.section == "01분반" })
    }

    @Test
    fun testAllEightFeaturesEndToEnd() = testApplication {
        environment {
            config = ApplicationConfig("application.conf")
        }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // Trigger app init
        val summaryInit = client.get("/api/courses/summary")
        assertEquals(HttpStatusCode.OK, summaryInit.status)
        val adminId = ensureAdminUser()

        try {
        // Ensure prof1 & prof2 are non-demo with passwordHash so section selection treats both as registered
        transaction {
            listOf(UUID.fromString(prof1Id), UUID.fromString(prof2Id)).forEach { pUuid ->
                UsersTable.update({ UsersTable.id eq pUuid }) {
                    it[isDemo] = false
                    it[passwordHash] = PasswordSecurity.hashPassword("profpass123")
                }
            }
        }

        // 1. Fetch demo course sections (`courseProfessorId` on `01분반` & `02분반`)
        val courseRes = client.get("/api/courses/$demoCourseId")
        assertEquals(HttpStatusCode.OK, courseRes.status)
        val demoCourse = courseRes.body<ApiResponse<CourseDto>>().data!!
        val sec01 = demoCourse.professors.first { it.id == prof1Id }
        val sec02 = demoCourse.professors.first { it.id == prof2Id }
        assertNotNull(sec01.courseProfessorId)
        assertNotNull(sec02.courseProfessorId)

        // Student 1 explicitly selects 02분반 (`sec02.courseProfessorId`) via PUT /api/courses/my
        val updateMyRes = client.put("/api/courses/my") {
            header(HttpHeaders.Authorization, bearerToken(student1Id))
            contentType(ContentType.Application.Json)
            setBody(
                UpdateUserCoursesRequest(
                    courseIds = listOf(demoCourseId),
                    courseSelections = listOf(
                        CourseSelectionItemDto(courseId = demoCourseId, courseProfessorId = sec02.courseProfessorId)
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, updateMyRes.status)
        val enrolledCourses = updateMyRes.body<ApiResponse<List<CourseDto>>>().data!!
        val myDemoCourse = enrolledCourses.first { it.id == demoCourseId }
        assertEquals(sec02.courseProfessorId, myDemoCourse.assignedCourseProfessorId)
        assertEquals(sec02.section, myDemoCourse.assignedSection)
        assertEquals(prof2Id, myDemoCourse.assignedProfessorId)

        // 2. Configure 3-tier notification & email scopes independently:
        //    - Prof 2 (`02분반`): notificationScope = ASSIGNED_SECTION, emailScope = NONE
        //    - Prof 1 (`01분반`): notificationScope = NONE, emailScope = ALL_SECTIONS
        val prof2SetRes = client.post("/api/settings") {
            header(HttpHeaders.Authorization, bearerToken(prof2Id))
            contentType(ContentType.Application.Json)
            setBody(
                UpdateUnifiedSettingRequest(
                    notificationScope = "ASSIGNED_SECTION",
                    emailScope = "NONE",
                    notificationEmail = "prof2.scope@kw.ac.kr"
                )
            )
        }
        assertEquals(HttpStatusCode.OK, prof2SetRes.status)
        val prof2Unified = prof2SetRes.body<UnifiedSettingDto>()
        assertEquals("ASSIGNED_SECTION", prof2Unified.notificationScope)
        assertEquals("NONE", prof2Unified.emailScope)

        val prof1SetRes = client.post("/api/settings") {
            header(HttpHeaders.Authorization, bearerToken(prof1Id))
            contentType(ContentType.Application.Json)
            setBody(
                UpdateUnifiedSettingRequest(
                    notificationScope = "NONE",
                    emailScope = "ALL_SECTIONS",
                    notificationEmail = "prof1.scope@kw.ac.kr"
                )
            )
        }
        assertEquals(HttpStatusCode.OK, prof1SetRes.status)

        // Student 1 posts a LECTURE question on 02분반 (`sec02.courseProfessorId`)
        val createQRes = client.post("/api/questions") {
            header(HttpHeaders.Authorization, bearerToken(student1Id))
            contentType(ContentType.Application.Json)
            setBody(
                CreateQuestionRequest(
                    courseId = demoCourseId,
                    courseProfessorId = sec02.courseProfessorId,
                    category = QuestionCategory.LECTURE,
                    title = "아두이노 PWM 주파수 제어 원리 질문",
                    rawContent = "아두이노 타이머 레지스터로 PWM 주파수를 조절하는 공식과 듀티비 계산 방법이 궁금합니다."
                )
            )
        }
        assertEquals(HttpStatusCode.Created, createQRes.status)
        val createdQ = createQRes.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals(sec02.courseProfessorId, createdQ.courseProfessorId)
        assertEquals(sec02.section, createdQ.section)
        assertFalse(createdQ.isPastSemester)

        delay(250) // allow async notification/email workers to commit

        val qUuid = UUID.fromString(createdQ.id)
        transaction {
            val prof2Notifs = NotificationsTable.select {
                (NotificationsTable.userId eq UUID.fromString(prof2Id)) and (NotificationsTable.referenceId eq qUuid)
            }.count()
            val prof1Notifs = NotificationsTable.select {
                (NotificationsTable.userId eq UUID.fromString(prof1Id)) and (NotificationsTable.referenceId eq qUuid)
            }.count()
            val prof2Emails = EmailLogsTable.select {
                (EmailLogsTable.professorId eq UUID.fromString(prof2Id)) and (EmailLogsTable.questionId eq qUuid)
            }.count()
            val prof1Emails = EmailLogsTable.select {
                (EmailLogsTable.professorId eq UUID.fromString(prof1Id)) and (EmailLogsTable.questionId eq qUuid)
            }.count()

            assertEquals(1L, prof2Notifs, "Prof2 (ASSIGNED_SECTION notif) must receive in-app notification for 02분반")
            assertEquals(0L, prof1Notifs, "Prof1 (NONE notif) must receive zero in-app notifications")
            assertEquals(0L, prof2Emails, "Prof2 (NONE email) must receive zero emails")
            assertEquals(1L, prof1Emails, "Prof1 (ALL_SECTIONS email) must receive co-prof email for 02분반 LECTURE question")
        }

        // 3 & 5 & 7. Excel bulk upload for seasonal semester `2026-겨울` with auto-linking to `2026-2` (`공학설계입문` 7000-1-2957)
        val winterXlsx = buildMinimalXlsx(
            bannerText = "2026학년도 겨울계절학기 개설과목 및 강의시간표",
            rows = listOf(
                listOf("공통", "공통", "공통", "7000-1-2957-01", "공학설계입문", "교선", "3", "김교수", "월", "1,2", "비501"),
                listOf("공통", "공통", "공통", "7000-1-2957-02", "공학설계입문", "교선", "3", "최신규", "수", "3,4", "비502")
            )
        )

        val importRes = client.post("/api/admin/courses/import-excel") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("autoLinkPrevSemester", "true")
                        append("setAsCurrentSemester", "true")
                        append(
                            "file",
                            winterXlsx,
                            Headers.build {
                                append(HttpHeaders.ContentType, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                                append(HttpHeaders.ContentDisposition, "filename=\"2026_winter.xlsx\"")
                            }
                        )
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.OK, importRes.status)
        val importData = importRes.body<ApiResponse<ExcelCourseImportResultDto>>().data!!
        assertEquals("2026-겨울", importData.semester)
        assertEquals(1, importData.createdCourses, "7000-01-2608-01 and -02 must merge into 1 course with 2 sections")
        assertEquals(2, importData.createdSections)
        assertTrue(importData.autoLinkedCourses >= 1, "Winter '공학설계입문' must auto-link to 2026-2 '공학설계입문'")

        // 6. Verify that the earlier `2026-2` question now reports `isPastSemester = true` since currentSemester is `2026-겨울`!
        val detailAfterRollover = client.get("/api/questions/${createdQ.id}") {
            header(HttpHeaders.Authorization, bearerToken(student1Id))
        }.body<ApiResponse<QuestionDetailDto>>().data!!
        assertEquals("2026-2", detailAfterRollover.semester)
        assertEquals("2026-겨울", detailAfterRollover.currentSemester)
        assertTrue(detailAfterRollover.isPastSemester, "Thread from 2026-2 must flag isPastSemester=true when active semester is 2026-겨울")

        // 4. Verify similar questions works across sections AND across the linked-list (`2026-겨울` course finds `2026-2` 02분반 question!)
        val winterCourses = client.get("/api/courses?semester=2026-겨울&limit=50")
            .body<ApiResponse<CourseListResponse>>().data!!.courses
        val winterDesignCourse = winterCourses.first { it.name == "공학설계입문" }
        assertNotNull(winterDesignCourse.prevCourseId, "Doubly-linked list prevCourseId must point to 2026-2 course")
        if (winterDesignCourse.prevCourseId != demoCourseId) {
            val relinkRes = client.put("/api/admin/courses/${winterDesignCourse.id}/link") {
                header(HttpHeaders.Authorization, bearerToken(adminId))
                contentType(ContentType.Application.Json)
                setBody(LinkCourseRequest(prevCourseId = demoCourseId))
            }
            assertEquals(HttpStatusCode.OK, relinkRes.status)
        }

        val simRes = client.post("/api/questions/similar") {
            header(HttpHeaders.Authorization, bearerToken(student1Id))
            contentType(ContentType.Application.Json)
            setBody(
                SimilarQuestionRequest(
                    text = "아두이노 타이머 레지스터 PWM 주파수와 듀티비 계산 공식",
                    category = QuestionCategory.ASSIGNMENT, // even across ASSIGNMENT vs LECTURE!
                    courseId = winterDesignCourse.id
                )
            )
        }
        assertEquals(HttpStatusCode.OK, simRes.status)
        val simList = simRes.body<ApiResponse<List<SimilarQuestionDto>>>().data!!
        assertTrue(simList.any { it.id == createdQ.id && it.isPastSemester }, "Similar search on linked winter course must find 2026-2 section 02 question")

        // 5b. Manual linked-list linking & cycle prevention
        val spring2027Course = client.post("/api/admin/courses") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
            contentType(ContentType.Application.Json)
            setBody(
                CreateCourseRequest(
                    name = "공학설계심화트랙",
                    code = "7000-2-9999",
                    semester = "2027-1",
                    scopeType = "ALL",
                    autoLinkPrevSemester = false
                )
            )
        }.body<ApiResponse<CourseDto>>().data!!

        val manualLinkRes = client.put("/api/admin/courses/${spring2027Course.id}/link") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
            contentType(ContentType.Application.Json)
            setBody(LinkCourseRequest(prevCourseId = winterDesignCourse.id))
        }
        assertEquals(HttpStatusCode.OK, manualLinkRes.status)
        assertEquals(winterDesignCourse.id, manualLinkRes.body<ApiResponse<CourseDto>>().data!!.prevCourseId)

        // Attempting to link `demoCourseId.prevCourseId = spring2027Course.id` creates a cycle -> 400 Bad Request
        val cycleRes = client.put("/api/admin/courses/$demoCourseId/link") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
            contentType(ContentType.Application.Json)
            setBody(LinkCourseRequest(prevCourseId = spring2027Course.id))
        }
        assertEquals(HttpStatusCode.BadRequest, cycleRes.status)

        // 5c. Continuous mid-chain splicing across arbitrary semesters (e.g., 2026-여름 inserted between demoCourseId and winterDesignCourse)
        val bridgeCourse = client.post("/api/admin/courses") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
            contentType(ContentType.Application.Json)
            setBody(
                CreateCourseRequest(
                    name = "계절브릿지특강",
                    code = "7000-2-7777",
                    semester = "2026-여름",
                    scopeType = "ALL",
                    autoLinkPrevSemester = false
                )
            )
        }.body<ApiResponse<CourseDto>>().data!!

        val spliceRes = client.put("/api/admin/courses/${bridgeCourse.id}/link") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
            contentType(ContentType.Application.Json)
            setBody(LinkCourseRequest(prevCourseId = demoCourseId))
        }
        assertEquals(HttpStatusCode.OK, spliceRes.status)
        val splicedBridge = spliceRes.body<ApiResponse<CourseDto>>().data!!
        assertEquals(demoCourseId, splicedBridge.prevCourseId)
        assertEquals(winterDesignCourse.id, splicedBridge.nextCourseId, "Mid-chain splice must preserve downstream winterDesignCourse")

        // Continuous 4-hop traversal: GET /api/questions?courseId=spring2027Course.id finds createdQ from demoCourseId
        val lineageQuestionsRes = client.get("/api/questions?courseId=${spring2027Course.id}") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
        }
        assertEquals(HttpStatusCode.OK, lineageQuestionsRes.status)
        val lineageQuestions = lineageQuestionsRes.body<ApiResponse<List<QuestionListItemDto>>>().data!!
        assertTrue(lineageQuestions.any { it.id == createdQ.id }, "Course filter must traverse continuous linked list to find ancestor course question")

        // 8. Professor registration with NO existing `courseIds`, creating a custom course directly!
        val profRegRes = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.PROFESSOR,
                    username = "new_winter_prof",
                    password = "securePassword123!",
                    name = "박신임",
                    department = "소프트웨어학부",
                    email = "new.winter.prof@kw.ac.kr",
                    courseIds = emptyList(),
                    newCourses = listOf(
                        ProfessorSignupCourseInput(
                            name = "동계AI집중특강",
                            code = "I030-3-8888-01",
                            semester = "2026-겨울",
                            section = "01분반",
                            completionType = "전선",
                            credit = 3,
                            schedule = "월,수 1,2교시(새빛201)"
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, profRegRes.status)
        val registeredProf = profRegRes.body<ApiResponse<AuthResponse>>().data!!.user
        assertEquals(1, registeredProf.courses.size)
        assertEquals("동계AI집중특강", registeredProf.courses[0].name)
        assertEquals("I030-3-8888", registeredProf.courses[0].code)
        assertEquals("2026-겨울", registeredProf.courses[0].semester)
        assertEquals("01분반", registeredProf.courses[0].assignedSection)

        // 9. Doubly-linked list aliases for courses (append -> mid-chain splice remove -> search by alias)
        val a1 = client.post("/api/admin/courses/${spring2027Course.id}/aliases") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
            contentType(ContentType.Application.Json)
            setBody(DepartmentAliasRequest(alias = "구_공학설계1"))
        }
        assertEquals(HttpStatusCode.Created, a1.status)
        val a2 = client.post("/api/admin/courses/${spring2027Course.id}/aliases") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
            contentType(ContentType.Application.Json)
            setBody(DepartmentAliasRequest(alias = "구_공학설계2"))
        }
        assertEquals(HttpStatusCode.Created, a2.status)
        val a3 = client.post("/api/admin/courses/${spring2027Course.id}/aliases") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
            contentType(ContentType.Application.Json)
            setBody(DepartmentAliasRequest(alias = "구_공학설계3"))
        }
        assertEquals(HttpStatusCode.Created, a3.status)
        assertEquals(listOf("구_공학설계1", "구_공학설계2", "구_공학설계3"), a3.body<ApiResponse<CourseDto>>().data!!.aliases)

        // Remove middle alias node -> doubly-linked list splices 1 <-> 3 cleanly
        val rmMid = client.delete("/api/admin/courses/${spring2027Course.id}/aliases?alias=${java.net.URLEncoder.encode("구_공학설계2", "UTF-8")}") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
        }
        assertEquals(HttpStatusCode.OK, rmMid.status)
        assertEquals(listOf("구_공학설계1", "구_공학설계3"), rmMid.body<ApiResponse<CourseDto>>().data!!.aliases)

        // 10. Admin transaction atomicity: failed updateCourse (invalid prevCourseId causing cycle) must roll back name change too
        val failTxRes = client.put("/api/admin/courses/$demoCourseId") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
            contentType(ContentType.Application.Json)
            setBody(
                UpdateCourseRequest(
                    name = "트랜잭션롤백테스트명칭",
                    prevCourseId = spring2027Course.id // triggers cycle -> IllegalArgumentException inside adminTransaction
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, failTxRes.status)
        val afterFailName = transaction {
            CoursesTable.select { CoursesTable.id eq UUID.fromString(demoCourseId) }.single()[CoursesTable.name]
        }
        assertTrue(afterFailName != "트랜잭션롤백테스트명칭", "Admin transaction must roll back course rename when subsequent link fails")

        // 11. Atomic batch-commit (all-or-nothing rollback on failure, then full commit on valid batch)
        val failBatchRes = client.post("/api/admin/batch-commit") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
            contentType(ContentType.Application.Json)
            setBody(
                AdminBatchCommitRequest(
                    operations = listOf(
                        AdminBatchOperationDto(
                            type = "ADD_COURSE_ALIAS",
                            targetId = spring2027Course.id,
                            label = "일괄별칭테스트",
                            payload = kotlinx.serialization.json.JsonObject(
                                mapOf("alias" to kotlinx.serialization.json.JsonPrimitive("롤백되어야할별칭"))
                            )
                        ),
                        AdminBatchOperationDto(
                            type = "LINK_COURSE",
                            targetId = demoCourseId,
                            label = "순환오류유발",
                            payload = kotlinx.serialization.json.JsonObject(
                                mapOf("prevCourseId" to kotlinx.serialization.json.JsonPrimitive(spring2027Course.id))
                            )
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, failBatchRes.status)
        val aliasCountAfterRollback = transaction {
            CourseAliasesTable.select {
                (CourseAliasesTable.courseId eq UUID.fromString(spring2027Course.id)) and
                    (CourseAliasesTable.aliasName eq "롤백되어야할별칭")
            }.count()
        }
        assertEquals(0L, aliasCountAfterRollback, "Batch commit failure must roll back earlier operation in the same batch")

        val okBatchRes = client.post("/api/admin/batch-commit") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
            contentType(ContentType.Application.Json)
            setBody(
                AdminBatchCommitRequest(
                    operations = listOf(
                        AdminBatchOperationDto(
                            type = "ADD_COURSE_ALIAS",
                            targetId = spring2027Course.id,
                            label = "일괄별칭성공",
                            payload = kotlinx.serialization.json.JsonObject(
                                mapOf("alias" to kotlinx.serialization.json.JsonPrimitive("일괄커밋별칭"))
                            )
                        ),
                        AdminBatchOperationDto(
                            type = "DELETE_COURSE",
                            targetId = bridgeCourse.id,
                            label = "브릿지과목삭제(휴지통)"
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, okBatchRes.status)

        // 12. 30-day Trash Bin: deleted bridgeCourse must appear in /api/admin/trash and restore losslessly!
        val trashListRes = client.get("/api/admin/trash") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
        }
        assertEquals(HttpStatusCode.OK, trashListRes.status)
        val trashList = trashListRes.body<ApiResponse<List<AdminTrashItemDto>>>().data!!
        val deletedBridgeItem = trashList.firstOrNull { it.entityType == "COURSE" && it.entityId == bridgeCourse.id }
        assertTrue(deletedBridgeItem != null, "Deleted course must be preserved in 90-day trash bin")
        assertTrue(deletedBridgeItem!!.remainingDays >= 89L, "Trash retention must be 90 days (3 months)")
        val storedSnapshot = transaction {
            AdminTrashBinTable.select { AdminTrashBinTable.id eq UUID.fromString(deletedBridgeItem.id) }
                .single()[AdminTrashBinTable.snapshotJson]
        }
        assertTrue(storedSnapshot.startsWith("GZIP:"), "Trash snapshot must be losslessly GZIP-compressed")

        val restoreRes = client.post("/api/admin/trash/${deletedBridgeItem.id}/restore") {
            header(HttpHeaders.Authorization, bearerToken(adminId))
        }
        assertEquals(HttpStatusCode.OK, restoreRes.status)
        val restoredExists = transaction {
            CoursesTable.select { CoursesTable.id eq UUID.fromString(bridgeCourse.id) }.count() > 0L
        }
        assertTrue(restoredExists, "Restored course must be back in CoursesTable")
        } finally {
            // Restore shared demo state so subsequent test classes see default 2026-2, original emails & ALL_SECTIONS
            transaction {
                SystemSettingsTable.update({ SystemSettingsTable.settingKey eq "current_semester" }) {
                    it[settingValue] = "2026-2"
                }
                val p1 = UUID.fromString(prof1Id)
                val p2 = UUID.fromString(prof2Id)
                UsersTable.update({ UsersTable.id eq p1 }) { it[email] = "kim.prof@kw.ac.kr" }
                UsersTable.update({ UsersTable.id eq p2 }) { it[email] = "lee.prof@kw.ac.kr" }
                ProfessorNotificationSettingsTable.update({ ProfessorNotificationSettingsTable.professorId eq p1 }) {
                    it[notificationScope] = "ALL_SECTIONS"
                    it[emailScope] = "ALL_SECTIONS"
                    it[emailEnabled] = true
                    it[notifyOtherProfessorQuestions] = true
                    it[notificationEmail] = "kim.prof@kw.ac.kr"
                }
                ProfessorNotificationSettingsTable.update({ ProfessorNotificationSettingsTable.professorId eq p2 }) {
                    it[notificationScope] = "ALL_SECTIONS"
                    it[emailScope] = "ALL_SECTIONS"
                    it[emailEnabled] = true
                    it[notifyOtherProfessorQuestions] = true
                    it[notificationEmail] = "lee.prof@kw.ac.kr"
                }
                val sec01Row = CourseProfessorsTable.select {
                    (CourseProfessorsTable.courseId eq UUID.fromString(demoCourseId)) and
                        (CourseProfessorsTable.professorId eq p1)
                }.firstOrNull()
                EnrollmentsTable.update({
                    (EnrollmentsTable.studentId eq UUID.fromString(student1Id)) and
                        (EnrollmentsTable.courseId eq UUID.fromString(demoCourseId))
                }) {
                    it[assignedProfessorId] = p1
                    it[courseProfessorId] = sec01Row?.get(CourseProfessorsTable.id)?.value
                }
            }
        }
    }
}
