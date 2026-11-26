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
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)
    private val lifecycleLock = Any()

    @Volatile
    private var activeDataSource: HikariDataSource? = null

    @Volatile
    private var activeDatabase: Database? = null

    fun init(config: ApplicationConfig? = null) {
        synchronized(lifecycleLock) {
            val existingPool = activeDataSource
            val candidatePool = createDataSource(config)
            val reuseExisting = existingPool != null &&
                !existingPool.isClosed &&
                existingPool.jdbcUrl == candidatePool.jdbcUrl &&
                existingPool.username == candidatePool.username
            if (reuseExisting) {
                runCatching { candidatePool.close() }
            } else {
                activeDatabase = Database.connect(candidatePool)
                activeDataSource = candidatePool
                if (existingPool != null && !existingPool.isClosed) {
                    runCatching { existingPool.close() }
                }
            }
        }

        val tables = arrayOf(
            UsersTable,
            CollegesTable,
            CollegeAliasesTable,
            DepartmentsTable,
            DepartmentAliasesTable,
            CoursesTable,
            CourseProfessorsTable,
            EnrollmentsTable,
            ProfessorCategorySettingsTable,
            ProfessorNotificationSettingsTable,
            SystemSettingsTable,
            UserSettingsTable,
            QuestionsTable,
            AnswersTable,
            NotificationsTable,
            AttachmentsTable,
            EmailLogsTable,
            EmailReplyTokensTable,
            ProcessedInboundEmailsTable,
            WebPushKeysTable,
            PushSubscriptionsTable
        )

        // 1. Create missing tables first in an isolated transaction
        transaction {
            SchemaUtils.create(*tables)
        }

        // 2. Backfill legacy nullable timestamps and token_version only when the column already exists
        val timestampTables = listOf(
            "users", "colleges", "college_aliases", "departments", "department_aliases",
            "questions", "answers", "notifications", "attachments", "email_logs"
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
        runCatching {
            transaction {
                var hasTokenVersion = false
                exec(
                    "SELECT 1 FROM information_schema.columns WHERE LOWER(table_name) = 'users' AND LOWER(column_name) = 'token_version'"
                ) { rs ->
                    if (rs.next()) hasTokenVersion = true
                }
                if (hasTokenVersion) {
                    exec("UPDATE users SET token_version = 0 WHERE token_version IS NULL")
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
            val stmt = when {
                rawStmt.contains(" ADD ", ignoreCase = true) &&
                    rawStmt.contains("TIMESTAMP NOT NULL", ignoreCase = true) &&
                    !rawStmt.contains(" DEFAULT ", ignoreCase = true) ->
                    "$rawStmt DEFAULT CURRENT_TIMESTAMP"
                rawStmt.contains(" ADD ", ignoreCase = true) &&
                    rawStmt.contains("token_version", ignoreCase = true) &&
                    rawStmt.contains("NOT NULL", ignoreCase = true) &&
                    !rawStmt.contains(" DEFAULT ", ignoreCase = true) ->
                    "$rawStmt DEFAULT 0"
                else -> rawStmt
            }
            runCatching {
                transaction { exec(stmt) }
            }.onFailure {
                logger.warn("DDL statement skipped ($stmt): ${it.message}")
            }
        }

        // Allow enrollments without an assigned professor yet (admin-created courses before professor assignment)
        listOf(
            "ALTER TABLE enrollments ALTER COLUMN assigned_professor_id DROP NOT NULL",
            "ALTER TABLE enrollments ALTER COLUMN assigned_professor_id SET NULL",
            "ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS help_level VARCHAR(20) DEFAULT 'SOME'",
            "ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS exhibition_mode BOOLEAN DEFAULT FALSE",
            "ALTER TABLE questions ADD COLUMN IF NOT EXISTS is_thread_open BOOLEAN DEFAULT FALSE",
            "ALTER TABLE questions ADD COLUMN IF NOT EXISTS last_sequence_number INTEGER DEFAULT 1",
            "ALTER TABLE answers ADD COLUMN IF NOT EXISTS sequence_number INTEGER DEFAULT 2",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS answer_id UUID NULL",
            "ALTER TABLE courses ADD COLUMN IF NOT EXISTS prev_course_id UUID NULL",
            "ALTER TABLE courses ADD COLUMN IF NOT EXISTS next_course_id UUID NULL",
            "ALTER TABLE enrollments ADD COLUMN IF NOT EXISTS course_professor_id UUID NULL",
            "ALTER TABLE professor_notification_settings ADD COLUMN IF NOT EXISTS notification_scope VARCHAR(24) DEFAULT 'ALL_SECTIONS'",
            "ALTER TABLE professor_notification_settings ADD COLUMN IF NOT EXISTS email_scope VARCHAR(24) DEFAULT 'ASSIGNED_SECTION'",
            "ALTER TABLE questions ADD COLUMN IF NOT EXISTS course_professor_id UUID NULL",
            "ALTER TABLE questions ADD COLUMN IF NOT EXISTS created_semester VARCHAR(20) DEFAULT ''"
        ).forEach { alterStmt ->
            runCatching {
                transaction { exec(alterStmt) }
            }
        }

        // 4. Seed reference and demo data in a clean transaction
        transaction {
            seedDefaultColleges()
            seedDefaultDepartments()
            seedConvertedCoursesData()
            seedDemoData()
            backfillQuestionSequences()
            backfillSemesterAndSections()
        }
    }

    private fun createDataSource(config: ApplicationConfig?): HikariDataSource {
        val dbUrl = config?.propertyOrNull("database.url")?.getString()
            ?: EnvConfig.get("DB_URL")

        val hikariConfig = HikariConfig()

        if (!dbUrl.isNullOrBlank()) {
            val isProd = EnvConfig.get("PRODUCTION_MODE")?.equals("true", ignoreCase = true) == true
            val dbDriver = config?.propertyOrNull("database.driver")?.getString()
                ?: EnvConfig.get("DB_DRIVER")
                ?: "org.postgresql.Driver"
            val dbUser = config?.propertyOrNull("database.user")?.getString()
                ?: EnvConfig.get("DB_USER")
                ?: "postgres"
            val rawPassword = config?.propertyOrNull("database.password")?.getString()
                ?: EnvConfig.get("DB_PASSWORD")
            if (isProd && (rawPassword.isNullOrBlank() || rawPassword == "postgres")) {
                throw IllegalStateException("DB_PASSWORD must be explicitly configured (not default) when PRODUCTION_MODE=true.")
            }
            if (rawPassword.isNullOrBlank() || rawPassword == "postgres") {
                logger.warn("[Security] DB_PASSWORD is unset or using the default 'postgres' value.")
            }
            val dbPassword = rawPassword ?: "postgres"

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

    fun close(preserveInMemory: Boolean = false) {
        synchronized(lifecycleLock) {
            val pool = activeDataSource ?: return
            if (preserveInMemory && pool.jdbcUrl?.startsWith("jdbc:h2:mem:") == true) {
                return
            }
            activeDataSource = null
            activeDatabase = null
            if (!pool.isClosed) {
                runCatching { pool.close() }
            }
        }
    }

    suspend fun <T> dbQuery(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend () -> T
    ): T = newSuspendedTransaction(dispatcher, db = activeDatabase) { block() }

    suspend fun <T> dbQueryWithRetry(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        maxAttempts: Int = 3,
        block: suspend () -> T
    ): T {
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                return newSuspendedTransaction(dispatcher, db = activeDatabase) { block() }
            } catch (ex: Throwable) {
                if (attempt == maxAttempts || !isTransientConcurrencyFailure(ex)) {
                    throw ex
                }
                lastError = ex
            }
        }
        throw lastError!!
    }

    private fun isTransientConcurrencyFailure(ex: Throwable): Boolean {
        var cur: Throwable? = ex
        while (cur != null) {
            if (cur is java.sql.SQLException) {
                val state = cur.sqlState
                if (state == "40001" || state == "40P01" || state == "23505" || cur.errorCode == 90131) {
                    return true
                }
            }
            cur = cur.cause
        }
        return false
    }

    private val defaultCollegeNames = listOf(
        "인공지능융합대학",
        "전자정보공과대학",
        "공과대학",
        "자연과학대학",
        "인문사회과학대학",
        "경영대학",
        "정책법학대학",
        "참빛인재대학"
    )

    private val defaultCollegeAliases = listOf(
        "인공지능융합대학" to "소프트웨어융합대학"
    )

    private val specialCollegeScopes = setOf("전체", "공통", "광운대학교")

    internal fun isReservedCatalogName(rawName: String): Boolean {
        val clean = rawName.trim()
        return clean in specialCollegeScopes || clean.endsWith(" 전체")
    }

    internal fun resolveCollegeRow(rawName: String): ResultRow? {
        val clean = rawName.trim()
        if (clean.isBlank()) return null
        val lower = clean.lowercase()
        val direct = CollegesTable.select { CollegesTable.name.lowerCase() eq lower }.firstOrNull()
        if (direct != null) return direct

        val aliasRow = CollegeAliasesTable.select {
            CollegeAliasesTable.aliasName.lowerCase() eq lower
        }.firstOrNull() ?: return null
        return CollegesTable.select {
            CollegesTable.id eq aliasRow[CollegeAliasesTable.collegeId]
        }.firstOrNull()
    }

    internal fun resolveCanonicalCollegeName(rawName: String): String {
        val clean = rawName.trim()
        if (clean.isBlank()) return clean
        return resolveCollegeRow(clean)?.get(CollegesTable.name) ?: clean
    }

    internal fun expandCollegeAliasNames(rawName: String): List<String> {
        val clean = rawName.trim()
        if (clean.isBlank()) return emptyList()
        val colRow = resolveCollegeRow(clean) ?: return listOf(clean)
        val canonicalName = colRow[CollegesTable.name]
        val colId = colRow[CollegesTable.id].value
        val aliases = CollegeAliasesTable.select {
            CollegeAliasesTable.collegeId eq colId
        }.map { it[CollegeAliasesTable.aliasName] }
        return (listOf(canonicalName) + aliases + clean).distinct()
    }

    internal fun ensureCollegeAliasInternal(collegeId: UUID, rawAlias: String) {
        val cleanAlias = rawAlias.trim()
        if (cleanAlias.isBlank() || cleanAlias.length > 100) return
        val lower = cleanAlias.lowercase()
        val canonicalExists = CollegesTable.select { CollegesTable.name.lowerCase() eq lower }.count() > 0L
        if (canonicalExists) return
        val aliasExists = CollegeAliasesTable.select {
            CollegeAliasesTable.aliasName.lowerCase() eq lower
        }.count() > 0L
        if (!aliasExists) {
            CollegeAliasesTable.insert {
                it[id] = UUID.randomUUID()
                it[CollegeAliasesTable.collegeId] = collegeId
                it[aliasName] = cleanAlias
            }
        }
    }

    internal fun ensureCollegeExistsInternal(rawCollegeName: String): String {
        val cleanCollege = rawCollegeName.trim()
        if (cleanCollege.isBlank() || cleanCollege in specialCollegeScopes) return cleanCollege
        val resolved = resolveCollegeRow(cleanCollege)
        if (resolved != null) return resolved[CollegesTable.name]

        CollegesTable.insert {
            it[id] = UUID.randomUUID()
            it[name] = cleanCollege
        }
        return cleanCollege
    }

    private fun seedDefaultColleges() {
        defaultCollegeAliases.forEach { (canonicalName, legacyName) ->
            val legacyRow = CollegesTable.select { CollegesTable.name eq legacyName }.singleOrNull()
            val canonRow = CollegesTable.select { CollegesTable.name eq canonicalName }.singleOrNull()
            if (legacyRow != null && canonRow == null) {
                CollegesTable.update({ CollegesTable.id eq legacyRow[CollegesTable.id] }) {
                    it[name] = canonicalName
                }
            } else if (legacyRow != null && canonRow != null) {
                val legacyId = legacyRow[CollegesTable.id].value
                val canonId = canonRow[CollegesTable.id].value
                CollegeAliasesTable.update({ CollegeAliasesTable.collegeId eq legacyId }) {
                    it[collegeId] = canonId
                }
                CollegesTable.deleteWhere { CollegesTable.id eq legacyId }
            }
            DepartmentsTable.update({ DepartmentsTable.college eq legacyName }) {
                it[college] = canonicalName
            }
            CoursesTable.update({ CoursesTable.college eq legacyName }) {
                it[college] = canonicalName
            }
            CoursesTable.update({ CoursesTable.department eq "$legacyName 전체" }) {
                it[department] = "$canonicalName 전체"
            }
        }

        defaultCollegeNames.forEach { collegeName ->
            if (CollegesTable.select { CollegesTable.name eq collegeName }.singleOrNull() == null) {
                CollegesTable.insert {
                    it[id] = UUID.randomUUID()
                    it[name] = collegeName
                }
            }
        }

        defaultCollegeAliases.forEach { (canonicalName, legacyAlias) ->
            val colRow = CollegesTable.select { CollegesTable.name eq canonicalName }.singleOrNull()
            if (colRow != null) {
                ensureCollegeAliasInternal(colRow[CollegesTable.id].value, legacyAlias)
            }
        }
    }

    private val defaultDepartmentAliases = listOf(
        "소프트웨어학부" to "소프트웨어학과",
        "컴퓨터정보공학부" to "컴퓨터공학과"
    )

    internal fun resolveDepartmentRow(rawName: String): ResultRow? {
        val clean = rawName.trim()
        if (clean.isBlank()) return null
        val lower = clean.lowercase()
        val direct = DepartmentsTable.select { DepartmentsTable.name.lowerCase() eq lower }.firstOrNull()
        if (direct != null) return direct

        val aliasRow = DepartmentAliasesTable.select {
            DepartmentAliasesTable.aliasName.lowerCase() eq lower
        }.firstOrNull() ?: return null
        return DepartmentsTable.select {
            DepartmentsTable.id eq aliasRow[DepartmentAliasesTable.departmentId]
        }.firstOrNull()
    }

    internal fun resolveCanonicalDepartmentName(rawName: String): String {
        val clean = rawName.trim()
        if (clean.isBlank()) return clean
        return resolveDepartmentRow(clean)?.get(DepartmentsTable.name) ?: clean
    }

    internal fun expandDepartmentAliasNames(rawName: String): List<String> {
        val clean = rawName.trim()
        if (clean.isBlank()) return emptyList()
        val deptRow = resolveDepartmentRow(clean) ?: return listOf(clean)
        val canonicalName = deptRow[DepartmentsTable.name]
        val deptId = deptRow[DepartmentsTable.id].value
        val aliases = DepartmentAliasesTable.select {
            DepartmentAliasesTable.departmentId eq deptId
        }.map { it[DepartmentAliasesTable.aliasName] }
        return (listOf(canonicalName) + aliases + clean).distinct()
    }

    internal fun ensureDepartmentAliasInternal(departmentId: UUID, rawAlias: String) {
        val cleanAlias = rawAlias.trim()
        if (cleanAlias.isBlank() || cleanAlias.length > 100) return
        val lower = cleanAlias.lowercase()
        val canonicalExists = DepartmentsTable.select { DepartmentsTable.name.lowerCase() eq lower }.count() > 0L
        if (canonicalExists) return
        val aliasExists = DepartmentAliasesTable.select {
            DepartmentAliasesTable.aliasName.lowerCase() eq lower
        }.count() > 0L
        if (!aliasExists) {
            DepartmentAliasesTable.insert {
                it[id] = UUID.randomUUID()
                it[DepartmentAliasesTable.departmentId] = departmentId
                it[aliasName] = cleanAlias
            }
        }
    }

    private fun seedDefaultDepartments() {
        // Migrate legacy department rows (e.g. 소프트웨어학과 -> 소프트웨어학부) before seeding defaults
        defaultDepartmentAliases.forEach { (canonicalName, legacyName) ->
            val legacyRow = DepartmentsTable.select { DepartmentsTable.name eq legacyName }.singleOrNull()
            val canonicalRow = DepartmentsTable.select { DepartmentsTable.name eq canonicalName }.singleOrNull()
            if (legacyRow != null && canonicalRow == null) {
                DepartmentsTable.update({ DepartmentsTable.id eq legacyRow[DepartmentsTable.id] }) {
                    it[name] = canonicalName
                }
            } else if (legacyRow != null && canonicalRow != null) {
                val legacyDeptId = legacyRow[DepartmentsTable.id].value
                val canonDeptId = canonicalRow[DepartmentsTable.id].value
                DepartmentAliasesTable.update({ DepartmentAliasesTable.departmentId eq legacyDeptId }) {
                    it[departmentId] = canonDeptId
                }
                DepartmentsTable.deleteWhere { DepartmentsTable.id eq legacyDeptId }
            }
        }

        val initialDepartments = listOf(
            "소프트웨어학부" to "인공지능융합대학",
            "컴퓨터정보공학부" to "인공지능융합대학",
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
            val existing = DepartmentsTable.select { DepartmentsTable.name eq deptName }.singleOrNull()
            if (existing == null) {
                DepartmentsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[name] = deptName
                    it[college] = collegeName
                }
            }
        }

        defaultDepartmentAliases.forEach { (canonicalName, legacyAlias) ->
            val deptRow = DepartmentsTable.select { DepartmentsTable.name eq canonicalName }.singleOrNull()
            if (deptRow != null) {
                ensureDepartmentAliasInternal(deptRow[DepartmentsTable.id].value, legacyAlias)
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
                val cleanCollege = ensureCollegeExistsInternal(c.college.ifBlank { "광운대학교" })
                val cleanDept = c.department.trim()
                if (cleanDept.isNotBlank() && cleanDept != "공통") {
                    if (resolveDepartmentRow(cleanDept) == null) {
                        val collegeName = cleanCollege.ifBlank { "광운대학교" }
                        DepartmentsTable.insert {
                            it[id] = UUID.randomUUID()
                            it[name] = cleanDept
                            it[college] = collegeName
                        }
                    }
                }

                if (CoursesTable.select { CoursesTable.id eq cId }.count() == 0L) {
                    CoursesTable.insert {
                        it[id] = cId
                        it[name] = c.name
                        it[code] = c.code
                        it[semester] = c.semester
                        it[college] = cleanCollege
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
            it[identifier] = "2026101001"
            it[department] = "소프트웨어학부"
            it[displayTag] = "26_소프트_서영민"
            it[isDemo] = true
        }
        UsersTable.insert {
            it[id] = student2Id
            it[name] = "김민지"
            it[role] = UserRole.STUDENT.name
            it[identifier] = "2026101002"
            it[department] = "소프트웨어학부"
            it[displayTag] = "25_소프트_김민지"
            it[isDemo] = true
        }
        UsersTable.insert {
            it[id] = student3Id
            it[name] = "박준형"
            it[role] = UserRole.STUDENT.name
            it[identifier] = "2026102501"
            it[department] = "컴퓨터정보공학부"
            it[displayTag] = "24_컴정_박준형"
            it[isDemo] = true
        }

        UsersTable.insert {
            it[id] = prof1Id
            it[name] = "김교수"
            it[role] = UserRole.PROFESSOR.name
            it[identifier] = "P2011"
            it[department] = "소프트웨어학부"
            it[displayTag] = "소프트웨어학부 김교수"
            it[email] = "kim.prof@kw.ac.kr"
            it[isDemo] = true
        }
        UsersTable.insert {
            it[id] = prof2Id
            it[name] = "이교수"
            it[role] = UserRole.PROFESSOR.name
            it[identifier] = "P2012"
            it[department] = "컴퓨터정보공학부"
            it[displayTag] = "컴퓨터정보공학부 이교수"
            it[email] = "lee.prof@kw.ac.kr"
            it[isDemo] = true
        }
        UsersTable.insert {
            it[id] = prof3Id
            it[name] = "박교수"
            it[role] = UserRole.PROFESSOR.name
            it[identifier] = "P2013"
            it[department] = "컴퓨터정보공학부"
            it[displayTag] = "컴퓨터정보공학부 박교수"
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
                it[notificationScope] = "ALL_SECTIONS"
                it[emailScope] = "ALL_SECTIONS"
                it[notificationEmail] = mail
            }
        }

        // 6. User Settings
        listOf(student1Id, student2Id, student3Id, prof1Id, prof2Id, prof3Id).forEach { uId ->
            UserSettingsTable.insert {
                it[userId] = uId
                it[themeMode] = "light"
                it[fontSize] = "md"
                it[helpLevel] = "SOME"
                it[exhibitionMode] = false
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
            it[isThreadOpen] = false
            it[lastSequenceNumber] = 2
            it[status] = QuestionStatus.ANSWERED.name
            it[createdAt] = Instant.now().minusSeconds(86400)
        }

        AnswersTable.insert {
            it[questionId] = q1Id
            it[professorId] = prof1Id
            it[sequenceNumber] = 2
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
            it[isThreadOpen] = false
            it[lastSequenceNumber] = 1
            it[status] = QuestionStatus.OPEN.name
            it[createdAt] = Instant.now().minusSeconds(3600)
        }

        logger.info("Base data seeding completed successfully.")
    }

    private fun backfillQuestionSequences() {
        val questionRows = QuestionsTable.selectAll().toList()
        questionRows.forEach { q ->
            val qId = q[QuestionsTable.id].value
            val answers = AnswersTable.select { AnswersTable.questionId eq qId }
                .orderBy(AnswersTable.createdAt to SortOrder.ASC)
                .toList()
            if (answers.isEmpty()) {
                if (q[QuestionsTable.lastSequenceNumber] < 1) {
                    QuestionsTable.update({ QuestionsTable.id eq qId }) {
                        it[lastSequenceNumber] = 1
                    }
                }
                return@forEach
            }
            val currentLast = q[QuestionsTable.lastSequenceNumber]
            val distinctSeqs = answers.map { it[AnswersTable.sequenceNumber] }.distinct()
            if (currentLast < answers.size + 1 || distinctSeqs.size != answers.size || answers.any { it[AnswersTable.sequenceNumber] < 2 }) {
                answers.forEachIndexed { idx, ans ->
                    val seq = idx + 2
                    if (ans[AnswersTable.sequenceNumber] != seq) {
                        AnswersTable.update({ AnswersTable.id eq ans[AnswersTable.id] }) {
                            it[sequenceNumber] = seq
                        }
                    }
                }
                val newLast = answers.size + 1
                QuestionsTable.update({ QuestionsTable.id eq qId }) {
                    it[lastSequenceNumber] = maxOf(currentLast, newLast)
                }
            }
        }
    }

    private fun backfillSemesterAndSections() {
        // Ensure current_semester exists in SystemSettingsTable
        if (SystemSettingsTable.select { SystemSettingsTable.settingKey eq "current_semester" }.count() == 0L) {
            SystemSettingsTable.insert {
                it[settingKey] = "current_semester"
                it[settingValue] = "2026-2"
                it[updatedAt] = Instant.now()
            }
        }

        // Backfill enrollments.course_professor_id when null
        val courseProfByPair = CourseProfessorsTable.selectAll()
            .toList()
            .groupBy { it[CourseProfessorsTable.courseId] to it[CourseProfessorsTable.professorId] }
        val firstCourseProfByCourse = CourseProfessorsTable.selectAll()
            .toList()
            .groupBy { it[CourseProfessorsTable.courseId] }

        EnrollmentsTable.select { EnrollmentsTable.courseProfessorId.isNull() }
            .toList()
            .forEach { row ->
                val eId = row[EnrollmentsTable.id].value
                val cId = row[EnrollmentsTable.courseId]
                val pId = row[EnrollmentsTable.assignedProfessorId]
                val matched = (if (pId != null) courseProfByPair[cId to pId]?.firstOrNull() else null)
                    ?: firstCourseProfByCourse[cId]?.firstOrNull()
                if (matched != null) {
                    val cpId = matched[CourseProfessorsTable.id].value
                    val resolvedProfId = matched[CourseProfessorsTable.professorId]
                    EnrollmentsTable.update({ EnrollmentsTable.id eq eId }) {
                        it[courseProfessorId] = cpId
                        if (pId == null) it[assignedProfessorId] = resolvedProfId
                    }
                }
            }

        // Backfill questions.course_professor_id and questions.created_semester
        val courseSemesterMap = CoursesTable.selectAll()
            .associate { it[CoursesTable.id].value to it[CoursesTable.semester] }

        QuestionsTable.selectAll().toList().forEach { q ->
            val qId = q[QuestionsTable.id].value
            val cId = q[QuestionsTable.courseId]
            val pId = q[QuestionsTable.assignedProfessorId]
            val curCp = q[QuestionsTable.courseProfessorId]
            val curSem = q[QuestionsTable.createdSemester]
            val resolvedCp = curCp ?: courseProfByPair[cId to pId]?.firstOrNull()?.get(CourseProfessorsTable.id)?.value
            val resolvedSem = curSem.ifBlank { courseSemesterMap[cId].orEmpty().ifBlank { "2026-2" } }
            if (curCp != resolvedCp || curSem != resolvedSem) {
                QuestionsTable.update({ QuestionsTable.id eq qId }) {
                    if (resolvedCp != null) it[courseProfessorId] = resolvedCp
                    it[createdSemester] = resolvedSem
                }
            }
        }
    }
}
