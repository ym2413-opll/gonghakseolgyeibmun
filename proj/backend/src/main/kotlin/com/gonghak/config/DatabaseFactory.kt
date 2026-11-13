package com.gonghak.config

import com.gonghak.dto.CourseDataFile
import com.gonghak.models.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init(config: ApplicationConfig? = null) {
        val pool = createDataSource(config)
        Database.connect(pool)

        val tables = arrayOf(
            UsersTable,
            DepartmentsTable,
            CoursesTable,
            CourseProfessorsTable,
            EnrollmentsTable,
            ProfessorCategorySettingsTable,
            ProfessorNotificationSettingsTable,
            UserSettingsTable,
            QuestionsTable,
            AnswersTable,
            NotificationsTable,
            AttachmentsTable,
            EmailLogsTable
        )

        // 1. Create missing tables first in an isolated transaction
        transaction {
            SchemaUtils.create(*tables)
        }

        // 2. Backfill legacy nullable timestamps only when the column already exists
        val timestampTables = listOf(
            "users", "departments", "questions", "answers",
            "notifications", "attachments", "email_logs"
        )
        for (tableName in timestampTables) {
            runCatching {
                transaction {
                    var hasCreatedAt = false
                    exec(
                        "SELECT 1 FROM information_schema.columns WHERE LOWER(table_name) = '$tableName' AND LOWER(column_name) = 'created_at'"
                    ) { rs ->
                        if (rs.next()) hasCreatedAt = true
                    }
                    if (hasCreatedAt) {
                        exec("UPDATE $tableName SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL")
                    }
                }
            }
        }

        // 3. Apply each missing-column / DDL statement in its own transaction so one failure
        // never poisons a PostgreSQL transaction block for subsequent statements or seeding
        val ddlStatements = runCatching {
            transaction {
                SchemaUtils.addMissingColumnsStatements(*tables)
            }
        }.getOrElse { emptyList() }

        for (rawStmt in ddlStatements) {
            val stmt = if (
                rawStmt.contains(" ADD ", ignoreCase = true) &&
                rawStmt.contains("TIMESTAMP NOT NULL", ignoreCase = true) &&
                !rawStmt.contains(" DEFAULT ", ignoreCase = true)
            ) {
                "$rawStmt DEFAULT CURRENT_TIMESTAMP"
            } else {
                rawStmt
            }
            runCatching {
                transaction { exec(stmt) }
            }.onFailure {
                logger.warn("DDL statement skipped ($stmt): ${it.message}")
            }
        }

        // 4. Seed reference and demo data in a clean transaction
        transaction {
            seedDefaultDepartments()
            seedConvertedCoursesData()
            seedDemoData()
        }
    }

    private fun createDataSource(config: ApplicationConfig?): HikariDataSource {
        val dbUrl = config?.propertyOrNull("database.url")?.getString()
            ?: System.getenv("DB_URL")

        val hikariConfig = HikariConfig()

        if (!dbUrl.isNullOrBlank()) {
            val dbDriver = config?.propertyOrNull("database.driver")?.getString()
                ?: System.getenv("DB_DRIVER")
                ?: "org.postgresql.Driver"
            val dbUser = config?.propertyOrNull("database.user")?.getString()
                ?: System.getenv("DB_USER")
                ?: "postgres"
            val dbPassword = config?.propertyOrNull("database.password")?.getString()
                ?: System.getenv("DB_PASSWORD")
                ?: "postgres"

            hikariConfig.driverClassName = dbDriver
            hikariConfig.jdbcUrl = dbUrl
            hikariConfig.username = dbUser
            hikariConfig.password = dbPassword
            logger.info("Attempting connection to database: $dbUrl")
        } else {
            // Default H2 in PostgreSQL mode for seamless zero-setup demo & development
            hikariConfig.driverClassName = "org.h2.Driver"
            hikariConfig.jdbcUrl = "jdbc:h2:mem:qnadb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
            hikariConfig.username = "sa"
            hikariConfig.password = ""
            logger.info("No DB_URL specified. Initializing in-memory PostgreSQL-compatible H2 database.")
        }

        hikariConfig.maximumPoolSize = 10
        hikariConfig.isAutoCommit = false
        hikariConfig.transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        hikariConfig.validate()
        return HikariDataSource(hikariConfig)
    }

    suspend fun <T> dbQuery(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend () -> T
    ): T = newSuspendedTransaction(dispatcher) { block() }

    private fun seedDefaultDepartments() {
        val initialDepartments = listOf(
            "소프트웨어학과" to "인공지능융합대학",
            "컴퓨터공학과" to "인공지능융합대학",
            "정보융합학부" to "인공지능융합대학",
            "로봇학부" to "인공지능융합대학",
            "전자공학과" to "전자정보공과대학",
            "전자통신공학과" to "전자정보공과대학",
            "전자융합공학과" to "전자정보공과대학",
            "전기공학과" to "전자정보공과대학",
            "반도체시스템공학부" to "전자정보공과대학",
            "화학공학과" to "공과대학",
            "건축공학과" to "공과대학",
            "수학교육과" to "자연과학대학",
            "미디어커뮤니케이션학부" to "인문사회과학대학",
            "경영학부" to "경영대학"
        )
        initialDepartments.forEach { (deptName, collegeName) ->
            if (DepartmentsTable.select { DepartmentsTable.name eq deptName }.count() == 0L) {
                DepartmentsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[name] = deptName
                    it[college] = collegeName
                }
            }
        }
    }

    private fun seedConvertedCoursesData() {
        if (CoursesTable.selectAll().count() > 0) {
            return
        }

        logger.info("Loading converted course data from resources/courses_data.json...")
        val jsonStream = DatabaseFactory::class.java.getResourceAsStream("/courses_data.json")
        if (jsonStream == null) {
            logger.warn("courses_data.json not found in resources! Skipping course import.")
            return
        }

        try {
            val jsonString = jsonStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val courseDataFile = Json {
                ignoreUnknownKeys = true
                isLenient = true
            }.decodeFromString<CourseDataFile>(jsonString)

            logger.info("Importing ${courseDataFile.professors.size} professors and ${courseDataFile.courses.size} courses from converted JSON...")

            // 1. Insert distinct professors
            courseDataFile.professors.forEach { prof ->
                val pId = UUID.fromString(prof.id)
                if (UsersTable.select { UsersTable.id eq pId }.count() == 0L) {
                    UsersTable.insert {
                        it[id] = pId
                        it[name] = prof.name
                        it[role] = UserRole.PROFESSOR.name
                        it[identifier] = prof.identifier
                        it[department] = prof.department
                        it[displayTag] = if (prof.department.isNotBlank() && prof.department != "공통")
                            "${prof.department} ${prof.name} 교수"
                        else
                            "${prof.name} 교수"
                        it[isDemo] = true
                    }
                }
            }

            // 2. Insert courses, departments, and sections
            courseDataFile.courses.forEach { c ->
                val cId = UUID.fromString(c.id)
                val cleanDept = c.department.trim()
                if (cleanDept.isNotBlank() && cleanDept != "공통") {
                    if (DepartmentsTable.select { DepartmentsTable.name eq cleanDept }.count() == 0L) {
                        DepartmentsTable.insert {
                            it[id] = UUID.randomUUID()
                            it[name] = cleanDept
                            it[college] = c.college.ifBlank { "광운대학교" }
                        }
                    }
                }

                if (CoursesTable.select { CoursesTable.id eq cId }.count() == 0L) {
                    CoursesTable.insert {
                        it[id] = cId
                        it[name] = c.name
                        it[code] = c.code
                        it[semester] = c.semester
                        it[college] = c.college
                        it[department] = c.department
                        it[major] = c.major
                        it[completionType] = c.completionType
                        it[credit] = c.credit
                    }
                }

                // Insert sections
                c.sections.forEach { sec ->
                    val profId = sec.professorId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    if (profId != null) {
                        CourseProfessorsTable.insert {
                            it[courseId] = cId
                            it[professorId] = profId
                            it[section] = sec.section
                            it[schedule] = sec.schedule
                        }
                    }
                }
            }

            logger.info("Successfully imported ${courseDataFile.courses.size} courses and ${courseDataFile.metadata.totalSections} sections from courses_data.json.")
        } catch (e: Exception) {
            logger.error("Failed to seed converted course data: ${e.message}", e)
        }
    }

    private fun seedDemoData() {
        if (UsersTable.select { UsersTable.id eq UUID.fromString("11111111-1111-1111-1111-111111111101") }.count() > 0) {
            return
        }

        logger.info("Seeding base courses & test fixture records...")

        val student1Id = UUID.fromString("11111111-1111-1111-1111-111111111101")
        val student2Id = UUID.fromString("11111111-1111-1111-1111-111111111102")
        val student3Id = UUID.fromString("11111111-1111-1111-1111-111111111103")

        val prof1Id = UUID.fromString("22222222-2222-2222-2222-222222222201")
        val prof2Id = UUID.fromString("22222222-2222-2222-2222-222222222202")
        val prof3Id = UUID.fromString("22222222-2222-2222-2222-222222222203")

        UsersTable.insert {
            it[id] = student1Id
            it[name] = "서영민"
            it[role] = UserRole.STUDENT.name
            it[identifier] = "20261101"
            it[department] = "소프트웨어학과"
            it[displayTag] = "26_소프트_서영민"
            it[isDemo] = true
        }
        UsersTable.insert {
            it[id] = student2Id
            it[name] = "김민지"
            it[role] = UserRole.STUDENT.name
            it[identifier] = "20251102"
            it[department] = "소프트웨어학과"
            it[displayTag] = "25_소프트_김민지"
            it[isDemo] = true
        }
        UsersTable.insert {
            it[id] = student3Id
            it[name] = "박준형"
            it[role] = UserRole.STUDENT.name
            it[identifier] = "20241103"
            it[department] = "컴퓨터공학과"
            it[displayTag] = "24_컴공_박준형"
            it[isDemo] = true
        }

        UsersTable.insert {
            it[id] = prof1Id
            it[name] = "김교수"
            it[role] = UserRole.PROFESSOR.name
            it[identifier] = "P2011"
            it[department] = "소프트웨어학과"
            it[displayTag] = "소프트웨어학과 김교수"
            it[email] = "kim.prof@kw.ac.kr"
            it[isDemo] = true
        }
        UsersTable.insert {
            it[id] = prof2Id
            it[name] = "이교수"
            it[role] = UserRole.PROFESSOR.name
            it[identifier] = "P2012"
            it[department] = "컴퓨터공학과"
            it[displayTag] = "컴퓨터공학과 이교수"
            it[email] = "lee.prof@kw.ac.kr"
            it[isDemo] = true
        }
        UsersTable.insert {
            it[id] = prof3Id
            it[name] = "박교수"
            it[role] = UserRole.PROFESSOR.name
            it[identifier] = "P2013"
            it[department] = "컴퓨터공학과"
            it[displayTag] = "컴퓨터공학과 박교수"
            it[email] = "park.prof@kw.ac.kr"
            it[isDemo] = true
        }

        // 2. Courses
        val course1Id = UUID.fromString("33333333-3333-3333-3333-333333333301")
        val course2Id = UUID.fromString("33333333-3333-3333-3333-333333333302")

        if (CoursesTable.select { CoursesTable.id eq course1Id }.count() == 0L) {
            CoursesTable.insert {
                it[id] = course1Id
                it[name] = "공학설계입문"
                it[code] = "7000-1-2957"
                it[semester] = "2026-2"
                it[college] = "전자정보공과대학"
                it[department] = "공통"
                it[completionType] = "전선"
                it[credit] = 3
            }
        }
        if (CoursesTable.select { CoursesTable.id eq course2Id }.count() == 0L) {
            CoursesTable.insert {
                it[id] = course2Id
                it[name] = "자료구조"
                it[code] = "I030-2-1243"
                it[semester] = "2026-2"
                it[college] = "인공지능융합대학"
                it[department] = "소프트웨어학부"
                it[completionType] = "전선"
                it[credit] = 3
            }
        }

        // 3. Course Professors
        if (CourseProfessorsTable.select { (CourseProfessorsTable.courseId eq course1Id) and (CourseProfessorsTable.professorId eq prof1Id) }.count() == 0L) {
            CourseProfessorsTable.insert {
                it[courseId] = course1Id
                it[professorId] = prof1Id
                it[section] = "01분반"
                it[schedule] = "월 1교시(새101), 수 2교시(새101)"
            }
        }
        if (CourseProfessorsTable.select { (CourseProfessorsTable.courseId eq course1Id) and (CourseProfessorsTable.professorId eq prof2Id) }.count() == 0L) {
            CourseProfessorsTable.insert {
                it[courseId] = course1Id
                it[professorId] = prof2Id
                it[section] = "02분반"
                it[schedule] = "화 3교시(새102), 목 4교시(새102)"
            }
        }
        if (CourseProfessorsTable.select { (CourseProfessorsTable.courseId eq course2Id) and (CourseProfessorsTable.professorId eq prof3Id) }.count() == 0L) {
            CourseProfessorsTable.insert {
                it[courseId] = course2Id
                it[professorId] = prof3Id
                it[section] = "01분반"
                it[schedule] = "월 3교시(비301), 수 4교시(비301)"
            }
        }

        // 4. Enrollments
        EnrollmentsTable.insert {
            it[courseId] = course1Id
            it[studentId] = student1Id
            it[assignedProfessorId] = prof1Id
        }
        EnrollmentsTable.insert {
            it[courseId] = course1Id
            it[studentId] = student2Id
            it[assignedProfessorId] = prof2Id
        }
        EnrollmentsTable.insert {
            it[courseId] = course2Id
            it[studentId] = student3Id
            it[assignedProfessorId] = prof3Id
        }

        // 5. Professor Notification Settings
        listOf(
            prof1Id to "kim.prof@kw.ac.kr",
            prof2Id to "lee.prof@kw.ac.kr",
            prof3Id to "park.prof@kw.ac.kr"
        ).forEach { (pId, mail) ->
            ProfessorNotificationSettingsTable.insert {
                it[professorId] = pId
                it[quietHoursEnabled] = false
                it[quietHoursStart] = "22:00"
                it[quietHoursEnd] = "08:00"
                it[notifyOtherProfessorQuestions] = true
                it[emailEnabled] = true
                it[notificationEmail] = mail
            }
        }

        // 6. User Settings
        listOf(student1Id, student2Id, student3Id, prof1Id, prof2Id, prof3Id).forEach { uId ->
            UserSettingsTable.insert {
                it[userId] = uId
                it[themeMode] = "light"
                it[fontSize] = "md"
            }
        }

        // 7. Category Settings
        listOf(prof1Id, prof2Id).forEach { pId ->
            QuestionCategory.values().forEach { cat ->
                ProfessorCategorySettingsTable.insert {
                    it[professorId] = pId
                    it[courseId] = course1Id
                    it[category] = cat.name
                    it[isEnabled] = true
                }
            }
        }

        // 8. Sample Forum Threads
        val q1Id = UUID.fromString("44444444-4444-4444-4444-444444444401")
        val q1Title = "Ktor 서버 아키텍처 및 라우팅 설정 관련 문의"
        val q1Raw = "교수님 이번 과제에서 Ktor 라우팅 설정할 때 Application.module 안에 작성해야 하나요 아니면 routing 확장 함수로 분리하는 게 좋나요?"
        val q1Refined = "안녕하십니까, 교수님. 공학설계입문 과제를 진행하며 Ktor 서버 아키텍처에 대해 여쭙고자 합니다. 프로젝트의 확장성을 고려할 때 라우팅 로직을 Application.module 내부에 모두 작성하는 것과 routing 확장 함수로 분리하여 모듈화하는 방식 중 실무적으로 어떤 패턴이 권장되는지 조언을 구합니다."

        QuestionsTable.insert {
            it[id] = q1Id
            it[courseId] = course1Id
            it[studentId] = student1Id
            it[assignedProfessorId] = prof1Id
            it[category] = QuestionCategory.LECTURE.name
            it[title] = q1Title
            it[rawContent] = q1Raw
            it[refinedContent] = q1Refined
            it[isAnonymous] = true
            it[isHidden] = false
            it[status] = QuestionStatus.ANSWERED.name
            it[createdAt] = Instant.now().minusSeconds(86400)
        }

        AnswersTable.insert {
            it[questionId] = q1Id
            it[professorId] = prof1Id
            it[content] = "좋은 질문입니다. 실무 프로젝트에서는 라우팅을 도메인별(예: AuthRoutes, CourseRoutes)로 확장 함수로 분리하여 Application.module에서 호출하도록 구성하는 것이 유지보수와 협업에 훨씬 유리합니다."
            it[isAssignedProfessor] = true
            it[isHidden] = false
            it[createdAt] = Instant.now().minusSeconds(80000)
        }

        val q2Id = UUID.fromString("44444444-4444-4444-4444-444444444402")
        val q2Title = "중간 발표 PPT 제출 기한 문의"
        val q2Raw = "과제 제출 기한이 이번 주 금요일 23:59까지 맞나요? 시간 엄수인지 궁금합니다."
        val q2Refined = "안녕하십니까, 교수님. 공학설계입문 수강생입니다. 중간 발표 PPT 과제 제출 기한이 이번 주 금요일 23:59까지가 맞는지 확인차 연락드렸습니다. 바쁘신 일정에 확인 감사드립니다."

        QuestionsTable.insert {
            it[id] = q2Id
            it[courseId] = course1Id
            it[studentId] = student1Id
            it[assignedProfessorId] = prof1Id
            it[category] = QuestionCategory.ASSIGNMENT.name
            it[title] = q2Title
            it[rawContent] = q2Raw
            it[refinedContent] = q2Refined
            it[isAnonymous] = false
            it[isHidden] = false
            it[status] = QuestionStatus.OPEN.name
            it[createdAt] = Instant.now().minusSeconds(3600)
        }

        logger.info("Base data seeding completed successfully.")
    }
}
