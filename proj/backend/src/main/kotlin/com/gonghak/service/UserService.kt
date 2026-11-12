package com.gonghak.service

import com.gonghak.config.DatabaseFactory.dbQuery
import com.gonghak.dto.*
import com.gonghak.models.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class UserService {

    private fun rowToUserDto(row: ResultRow, includeCourses: Boolean = false): UserDto {
        val uId = row[UsersTable.id].value
        val courses = if (includeCourses) getCoursesForUserInternal(uId) else emptyList()
        return UserDto(
            id = uId.toString(),
            name = row[UsersTable.name],
            role = runCatching { UserRole.valueOf(row[UsersTable.role]) }.getOrDefault(UserRole.STUDENT),
            identifier = row[UsersTable.identifier],
            department = row[UsersTable.department],
            displayTag = row[UsersTable.displayTag],
            isDemo = row[UsersTable.isDemo],
            username = row[UsersTable.username],
            email = row[UsersTable.email],
            isAdmin = row[UsersTable.isAdmin],
            isWithdrawn = row[UsersTable.isWithdrawn],
            questionBanUntil = row[UsersTable.questionBanUntil]?.toString(),
            answerBanUntil = row[UsersTable.answerBanUntil]?.toString(),
            courses = courses
        )
    }

    suspend fun registerUser(request: RegisterRequest): AuthResponse = dbQuery {
        val cleanUsername = PasswordSecurity.validateUsername(request.username)
        PasswordSecurity.validatePassword(request.password)
        val cleanDept = PasswordSecurity.sanitizeInput(request.department)
        if (cleanDept.isBlank()) {
            throw IllegalArgumentException("학과를 입력해 주세요.")
        }

        // 1. Check duplicate username (case-insensitive)
        val existingUsername = UsersTable.select {
            UsersTable.username.lowerCase() eq cleanUsername.lowercase()
        }.count() > 0
        if (existingUsername) {
            throw IllegalArgumentException("이미 사용 중인 아이디(ID)입니다.")
        }

        val role = if (request.role == UserRole.PROFESSOR) UserRole.PROFESSOR else UserRole.STUDENT
        val passwordHash = PasswordSecurity.hashPassword(request.password)
        val newUserId = UUID.randomUUID()

        val envAdmins = (System.getenv("ADMIN_USERS") ?: "")
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        val isAutoAdmin = cleanUsername.lowercase() in envAdmins

        // Ensure department exists in DepartmentsTable
        if (DepartmentsTable.select { DepartmentsTable.name eq cleanDept }.count() == 0L) {
            DepartmentsTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = cleanDept
                it[college] = "광운대학교"
            }
        }

        if (role == UserRole.STUDENT) {
            val cleanStudentId = PasswordSecurity.sanitizeInput(request.identifier)
            val cleanName = PasswordSecurity.sanitizeInput(request.name)
            if (cleanStudentId.isBlank()) {
                throw IllegalArgumentException("학번을 입력해 주세요.")
            }
            if (cleanName.isBlank()) {
                throw IllegalArgumentException("이름을 입력해 주세요.")
            }

            // Duplicate check for student: studentId (학번) or (학번 + 학과 + 이름)
            val dupStudentId = UsersTable.select {
                (UsersTable.role eq UserRole.STUDENT.name) and
                        (UsersTable.identifier.lowerCase() eq cleanStudentId.lowercase()) and
                        (UsersTable.isDemo eq false)
            }.count() > 0
            if (dupStudentId) {
                throw IllegalArgumentException("이미 가입된 학번입니다. 중복 가입이 불가능합니다.")
            }

            val dupIdentity = UsersTable.select {
                (UsersTable.role eq UserRole.STUDENT.name) and
                        (UsersTable.department eq cleanDept) and
                        (UsersTable.name eq cleanName) and
                        (UsersTable.identifier eq cleanStudentId)
            }.count() > 0
            if (dupIdentity) {
                throw IllegalArgumentException("동일한 학생 정보로 이미 가입된 계정이 존재합니다.")
            }

            val yearPrefix = if (cleanStudentId.length >= 4 && cleanStudentId.startsWith("20")) {
                cleanStudentId.substring(2, 4)
            } else {
                cleanStudentId.take(2)
            }
            val shortDept = cleanDept.replace("학과", "").replace("학부", "").replace("전공", "").take(4)
            val displayTag = "${yearPrefix}_${shortDept}_${cleanName}"

            UsersTable.insert {
                it[id] = newUserId
                it[username] = cleanUsername
                it[UsersTable.passwordHash] = passwordHash
                it[email] = request.email?.trim()?.takeIf { e -> e.isNotBlank() }
                it[name] = cleanName
                it[UsersTable.role] = UserRole.STUDENT.name
                it[identifier] = cleanStudentId
                it[department] = cleanDept
                it[UsersTable.displayTag] = displayTag
                it[isDemo] = false
                it[isAdmin] = isAutoAdmin
                it[isWithdrawn] = false
            }

            // Enroll in selected courses or default courses
            val targetCourseUuids = resolveCourseUuids(request.courseIds, request.courseNames, cleanDept)
            targetCourseUuids.forEach { cId ->
                val assignedProfId = CourseProfessorsTable.select { CourseProfessorsTable.courseId eq cId }
                    .firstOrNull()?.get(CourseProfessorsTable.professorId)
                    ?: UsersTable.select { UsersTable.role eq UserRole.PROFESSOR.name }
                        .firstOrNull()?.get(UsersTable.id)?.value

                if (assignedProfId != null) {
                    EnrollmentsTable.insert {
                        it[courseId] = cId
                        it[studentId] = newUserId
                        it[assignedProfessorId] = assignedProfId
                    }
                }
            }
        } else {
            // PROFESSOR registration
            val cleanEmail = PasswordSecurity.validateEmail(
                request.email ?: throw IllegalArgumentException("교수 계정은 이메일을 입력해야 합니다.")
            )
            val cleanName = PasswordSecurity.sanitizeInput(request.name).ifBlank { cleanUsername }

            // Duplicate check for professor: email or (department + email)
            val dupEmail = UsersTable.select {
                UsersTable.email.lowerCase() eq cleanEmail.lowercase()
            }.count() > 0
            if (dupEmail) {
                throw IllegalArgumentException("이미 등록된 이메일 주소입니다. 중복 가입이 불가능합니다.")
            }

            val generatedProfId = request.identifier?.trim()?.takeIf { it.isNotBlank() }
                ?: "P${(1000..9999).random()}"
            val displayTag = "$cleanDept ${cleanName}${if (cleanName.endsWith("교수")) "" else " 교수"}"

            UsersTable.insert {
                it[id] = newUserId
                it[username] = cleanUsername
                it[UsersTable.passwordHash] = passwordHash
                it[email] = cleanEmail
                it[name] = cleanName
                it[UsersTable.role] = UserRole.PROFESSOR.name
                it[identifier] = generatedProfId
                it[department] = cleanDept
                it[UsersTable.displayTag] = displayTag
                it[isDemo] = false
                it[isAdmin] = isAutoAdmin
                it[isWithdrawn] = false
            }

            ProfessorNotificationSettingsTable.insert {
                it[professorId] = newUserId
                it[quietHoursEnabled] = false
                it[quietHoursStart] = "22:00"
                it[quietHoursEnd] = "08:00"
                it[notifyOtherProfessorQuestions] = true
                it[emailEnabled] = true
                it[notificationEmail] = cleanEmail
            }

            // Assign professor to selected courses
            val targetCourseUuids = resolveCourseUuids(request.courseIds, request.courseNames, cleanDept)
            targetCourseUuids.forEach { cId ->
                if (CourseProfessorsTable.select {
                        (CourseProfessorsTable.courseId eq cId) and (CourseProfessorsTable.professorId eq newUserId)
                    }.count() == 0L
                ) {
                    val existingSections = CourseProfessorsTable.select { CourseProfessorsTable.courseId eq cId }.count()
                    val nextSection = String.format("%02d분반", existingSections + 1)
                    CourseProfessorsTable.insert {
                        it[courseId] = cId
                        it[professorId] = newUserId
                        it[section] = nextSection
                        it[schedule] = "학사 시간표 참조"
                    }
                }
            }
        }

        UserSettingsTable.insert {
            it[userId] = newUserId
            it[themeMode] = "light"
            it[fontSize] = "md"
        }

        val createdRow = UsersTable.select { UsersTable.id eq newUserId }.single()
        val userDto = rowToUserDto(createdRow, includeCourses = true)
        val token = PasswordSecurity.generateAuthToken(newUserId)
        AuthResponse(token = token, user = userDto)
    }

    private fun resolveCourseUuids(
        courseIds: List<String>,
        courseNames: List<String>,
        department: String
    ): List<UUID> {
        val result = mutableListOf<UUID>()

        courseIds.forEach { rawId ->
            val parsed = runCatching { UUID.fromString(rawId.trim()) }.getOrNull()
                ?: com.gonghak.routes.DEMO_COURSE_ID_MAP[rawId.trim().lowercase()]
            if (parsed != null && CoursesTable.select { CoursesTable.id eq parsed }.count() > 0) {
                result.add(parsed)
            } else if (rawId.isNotBlank()) {
                val byName = CoursesTable.select { CoursesTable.name eq rawId.trim() }.firstOrNull()
                if (byName != null) result.add(byName[CoursesTable.id].value)
            }
        }

        courseNames.forEach { cName ->
            val cleanName = PasswordSecurity.sanitizeInput(cName)
            if (cleanName.isNotBlank()) {
                val existing = CoursesTable.select { CoursesTable.name eq cleanName }.firstOrNull()
                if (existing != null) {
                    result.add(existing[CoursesTable.id].value)
                } else {
                    // Dynamically create course if professor typed a new course name during sign-up
                    val newCourseId = UUID.randomUUID()
                    CoursesTable.insert {
                        it[id] = newCourseId
                        it[name] = cleanName
                        it[code] = "KW-${(1000..9999).random()}"
                        it[semester] = "2026-2"
                        it[college] = "광운대학교"
                        it[CoursesTable.department] = department
                        it[completionType] = "전선"
                        it[credit] = 3
                    }
                    result.add(newCourseId)
                }
            }
        }

        if (result.isEmpty()) {
            // Fallback: pick up to 2 courses matching department or default courses
            val deptCourses = CoursesTable.select { CoursesTable.department eq department }
                .limit(2)
                .map { it[CoursesTable.id].value }
            result.addAll(deptCourses)
        }
        if (result.isEmpty()) {
            val defaultCourse = UUID.fromString("33333333-3333-3333-3333-333333333301")
            if (CoursesTable.select { CoursesTable.id eq defaultCourse }.count() > 0) {
                result.add(defaultCourse)
            } else {
                CoursesTable.selectAll().limit(2).forEach { result.add(it[CoursesTable.id].value) }
            }
        }

        return result.distinct()
    }

    suspend fun loginUser(request: LoginRequest): AuthResponse = dbQuery {
        val cleanUsername = PasswordSecurity.sanitizeInput(request.username)
        if (cleanUsername.isBlank() || request.password.isBlank()) {
            throw IllegalArgumentException("아이디와 비밀번호를 모두 입력해 주세요.")
        }

        val row = UsersTable.select {
            UsersTable.username.lowerCase() eq cleanUsername.lowercase()
        }.singleOrNull() ?: throw IllegalArgumentException("존재하지 않는 아이디이거나 비밀번호가 올바르지 않습니다.")

        if (row[UsersTable.isWithdrawn]) {
            throw IllegalStateException("관리자에 의해 탈퇴 처리된 계정입니다. 로그인이 제한됩니다.")
        }

        val isValid = PasswordSecurity.verifyPassword(request.password, row[UsersTable.passwordHash])
        if (!isValid) {
            throw IllegalArgumentException("존재하지 않는 아이디이거나 비밀번호가 올바르지 않습니다.")
        }

        val uId = row[UsersTable.id].value
        val token = PasswordSecurity.generateAuthToken(uId)
        val userDto = rowToUserDto(row, includeCourses = true)
        AuthResponse(token = token, user = userDto)
    }

    suspend fun grantOrRevokeAdminByUsername(usernameOrId: String, grant: Boolean = true): UserDto = dbQuery {
        val clean = usernameOrId.trim()
        if (clean.isBlank()) throw IllegalArgumentException("대상 계정 ID를 입력해 주세요.")

        val parsedUuid = runCatching { UUID.fromString(clean) }.getOrNull()
        val row = if (parsedUuid != null) {
            UsersTable.select { UsersTable.id eq parsedUuid }.singleOrNull()
        } else {
            UsersTable.select {
                (UsersTable.username.lowerCase() eq clean.lowercase()) or
                        (UsersTable.identifier.lowerCase() eq clean.lowercase())
            }.firstOrNull()
        } ?: throw IllegalArgumentException("해당 ID('$clean')를 가진 계정을 찾을 수 없습니다.")

        val targetId = row[UsersTable.id].value
        UsersTable.update({ UsersTable.id eq targetId }) {
            it[isAdmin] = grant
        }

        val updated = UsersTable.select { UsersTable.id eq targetId }.single()
        rowToUserDto(updated, includeCourses = true)
    }

    suspend fun isUserAdmin(userId: UUID): Boolean = dbQuery {
        val row = UsersTable.select { UsersTable.id eq userId }.singleOrNull() ?: return@dbQuery false
        if (row[UsersTable.isWithdrawn]) return@dbQuery false
        row[UsersTable.isAdmin] || row[UsersTable.role] == UserRole.ADMIN.name
    }

    suspend fun getAllUsersForAdmin(): List<UserDto> = dbQuery {
        UsersTable.select { UsersTable.isDemo eq false }
            .orderBy(UsersTable.createdAt to SortOrder.DESC)
            .map { rowToUserDto(it, includeCourses = false) }
    }

    suspend fun sanctionUser(targetUserId: UUID, request: UserSanctionRequest): UserDto = dbQuery {
        val row = UsersTable.select { UsersTable.id eq targetUserId }.singleOrNull()
            ?: throw IllegalArgumentException("대상 사용자를 찾을 수 없습니다.")

        val action = request.action.trim().uppercase()
        val now = Instant.now()

        fun calculateBanExpiry(): Instant {
            if (!request.until.isNullOrBlank()) {
                runCatching { return Instant.parse(request.until) }
            }
            val days = request.durationDays ?: 0
            val hours = request.durationHours ?: 0
            return when {
                days > 0 -> now.plus(days.toLong(), ChronoUnit.DAYS)
                hours > 0 -> now.plus(hours.toLong(), ChronoUnit.HOURS)
                else -> now.plus(7, ChronoUnit.DAYS) // default 7 days
            }
        }

        UsersTable.update({ UsersTable.id eq targetUserId }) {
            when (action) {
                "WITHDRAW" -> it[isWithdrawn] = true
                "RESTORE" -> it[isWithdrawn] = false
                "BAN_QUESTION" -> it[questionBanUntil] = calculateBanExpiry()
                "UNBAN_QUESTION" -> it[questionBanUntil] = null
                "BAN_ANSWER" -> it[answerBanUntil] = calculateBanExpiry()
                "UNBAN_ANSWER" -> it[answerBanUntil] = null
                else -> throw IllegalArgumentException("지원하지 않는 제재 작업입니다: $action")
            }
        }

        val updated = UsersTable.select { UsersTable.id eq targetUserId }.single()
        rowToUserDto(updated, includeCourses = false)
    }

    suspend fun getDepartments(): List<DepartmentDto> = dbQuery {
        val deptRows = DepartmentsTable.selectAll()
            .orderBy(DepartmentsTable.name to SortOrder.ASC)
            .map {
                DepartmentDto(
                    id = it[DepartmentsTable.id].value.toString(),
                    name = it[DepartmentsTable.name],
                    college = it[DepartmentsTable.college],
                    createdAt = it[DepartmentsTable.createdAt].toString()
                )
            }
        if (deptRows.isNotEmpty()) {
            deptRows
        } else {
            val fromCourses = CoursesTable.slice(CoursesTable.department, CoursesTable.college)
                .selectAll()
                .mapNotNull { row ->
                    val d = row[CoursesTable.department].trim()
                    if (d.isBlank() || d == "공통") null else DepartmentDto(
                        id = d,
                        name = d,
                        college = row[CoursesTable.college]
                    )
                }
                .distinctBy { it.name }
                .sortedBy { it.name }
            fromCourses
        }
    }

    suspend fun createDepartment(request: CreateDepartmentRequest): DepartmentDto = dbQuery {
        val cleanName = PasswordSecurity.sanitizeInput(request.name)
        val cleanCollege = PasswordSecurity.sanitizeInput(request.college).ifBlank { "광운대학교" }
        if (cleanName.isBlank()) {
            throw IllegalArgumentException("학과명을 입력해 주세요.")
        }
        val exists = DepartmentsTable.select {
            DepartmentsTable.name.lowerCase() eq cleanName.lowercase()
        }.singleOrNull()
        if (exists != null) {
            throw IllegalArgumentException("이미 등록된 학과입니다: $cleanName")
        }

        val newId = UUID.randomUUID()
        val now = Instant.now()
        DepartmentsTable.insert {
            it[id] = newId
            it[name] = cleanName
            it[college] = cleanCollege
            it[createdAt] = now
        }

        DepartmentDto(
            id = newId.toString(),
            name = cleanName,
            college = cleanCollege,
            createdAt = now.toString()
        )
    }

    suspend fun createCourse(request: CreateCourseRequest): CourseDto = dbQuery {
        val cleanName = PasswordSecurity.sanitizeInput(request.name)
        if (cleanName.isBlank()) {
            throw IllegalArgumentException("과목명을 입력해 주세요.")
        }
        val cleanDept = PasswordSecurity.sanitizeInput(request.department).ifBlank { "공통" }
        val cleanCollege = PasswordSecurity.sanitizeInput(request.college).ifBlank { "광운대학교" }
        val cleanCode = PasswordSecurity.sanitizeInput(request.code).ifBlank { "KW-${(1000..9999).random()}" }
        val cleanSemester = PasswordSecurity.sanitizeInput(request.semester).ifBlank { "2026-2" }

        // Ensure department is registered if not "공통"
        if (cleanDept != "공통" && DepartmentsTable.select { DepartmentsTable.name eq cleanDept }.count() == 0L) {
            DepartmentsTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = cleanDept
                it[college] = cleanCollege
            }
        }

        val newCourseId = UUID.randomUUID()
        CoursesTable.insert {
            it[id] = newCourseId
            it[name] = cleanName
            it[code] = cleanCode
            it[semester] = cleanSemester
            it[college] = cleanCollege
            it[department] = cleanDept
            it[major] = request.major.trim()
            it[completionType] = request.completionType.trim().ifBlank { "전선" }
            it[credit] = request.credit.coerceIn(1, 6)
        }

        val profId = request.professorId?.trim()?.takeIf { it.isNotBlank() }?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        if (profId != null && UsersTable.select { UsersTable.id eq profId }.count() > 0) {
            CourseProfessorsTable.insert {
                it[courseId] = newCourseId
                it[professorId] = profId
                it[section] = request.section.ifBlank { "01분반" }
                it[schedule] = request.schedule
            }
        }

        getCourseByIdInternal(newCourseId)!!
    }

    suspend fun getAllDemoUsers(): List<UserDto> = dbQuery {
        UsersTable.select { UsersTable.isDemo eq true }
            .orderBy(UsersTable.role to SortOrder.ASC, UsersTable.name to SortOrder.ASC)
            .map { rowToUserDto(it, includeCourses = false) }
    }

    suspend fun getUserById(userId: UUID): UserDto? = dbQuery {
        UsersTable.select { UsersTable.id eq userId }
            .singleOrNull()
            ?.let { rowToUserDto(it, includeCourses = true) }
    }

    suspend fun getUserSetting(userId: UUID): UserSettingDto = dbQuery {
        getUserSettingInternal(userId)
    }

    private fun getUserSettingInternal(userId: UUID): UserSettingDto {
        val row = UserSettingsTable.select { UserSettingsTable.userId eq userId }.singleOrNull()
        return if (row != null) {
            UserSettingDto(
                userId = row[UserSettingsTable.userId].toString(),
                themeMode = row[UserSettingsTable.themeMode],
                fontSize = row[UserSettingsTable.fontSize]
            )
        } else {
            if (UsersTable.select { UsersTable.id eq userId }.count() > 0) {
                UserSettingsTable.insert {
                    it[UserSettingsTable.userId] = userId
                    it[themeMode] = "light"
                    it[fontSize] = "md"
                }
            }
            UserSettingDto(
                userId = userId.toString(),
                themeMode = "light",
                fontSize = "md"
            )
        }
    }

    suspend fun updateUserSetting(userId: UUID, request: UpdateUserSettingRequest): UserSettingDto = dbQuery {
        val exists = UserSettingsTable.select { UserSettingsTable.userId eq userId }.count() > 0
        if (exists) {
            UserSettingsTable.update({ UserSettingsTable.userId eq userId }) {
                request.themeMode?.let { v -> it[themeMode] = v }
                request.fontSize?.let { v -> it[fontSize] = v }
            }
        } else if (UsersTable.select { UsersTable.id eq userId }.count() > 0) {
            UserSettingsTable.insert {
                it[UserSettingsTable.userId] = userId
                it[themeMode] = request.themeMode ?: "light"
                it[fontSize] = request.fontSize ?: "md"
            }
        }
        getUserSettingInternal(userId)
    }

    suspend fun getCoursesForUser(userId: UUID): List<CourseDto> = dbQuery {
        getCoursesForUserInternal(userId)
    }

    private fun getCoursesForUserInternal(userId: UUID): List<CourseDto> {
        val user = UsersTable.select { UsersTable.id eq userId }.singleOrNull()
            ?: return emptyList()

        val role = runCatching { UserRole.valueOf(user[UsersTable.role]) }.getOrDefault(UserRole.STUDENT)

        if (role == UserRole.STUDENT) {
            val enrollments = EnrollmentsTable.innerJoin(CoursesTable, { EnrollmentsTable.courseId }, { CoursesTable.id })
                .select { EnrollmentsTable.studentId eq userId }
                .toList()

            return enrollments.map { row ->
                val cId = row[EnrollmentsTable.courseId]
                val assignedProfId = row[EnrollmentsTable.assignedProfessorId]

                val profRows = CourseProfessorsTable.innerJoin(UsersTable, { CourseProfessorsTable.professorId }, { UsersTable.id })
                    .select { CourseProfessorsTable.courseId eq cId }
                    .toList()

                val profList = profRows.map { pRow ->
                    ProfessorSimpleDto(
                        id = pRow[UsersTable.id].value.toString(),
                        name = pRow[UsersTable.name],
                        department = pRow[UsersTable.department],
                        section = pRow[CourseProfessorsTable.section],
                        schedule = pRow[CourseProfessorsTable.schedule]
                    )
                }

                val enabledCategories = getAvailableCategoriesForStudent(assignedProfId, cId)

                CourseDto(
                    id = cId.toString(),
                    name = row[CoursesTable.name],
                    code = row[CoursesTable.code],
                    semester = row[CoursesTable.semester],
                    college = row[CoursesTable.college],
                    department = row[CoursesTable.department],
                    major = row[CoursesTable.major],
                    completionType = row[CoursesTable.completionType],
                    credit = row[CoursesTable.credit],
                    professors = profList,
                    availableCategories = enabledCategories,
                    assignedProfessorId = assignedProfId.toString()
                )
            }
        } else {
            val teachingRows = CourseProfessorsTable.innerJoin(CoursesTable, { CourseProfessorsTable.courseId }, { CoursesTable.id })
                .select { CourseProfessorsTable.professorId eq userId }
                .toList()

            return teachingRows.map { row ->
                val cId = row[CourseProfessorsTable.courseId]

                val allProfRows = CourseProfessorsTable.innerJoin(UsersTable, { CourseProfessorsTable.professorId }, { UsersTable.id })
                    .select { CourseProfessorsTable.courseId eq cId }
                    .toList()

                val profList = allProfRows.map { pRow ->
                    ProfessorSimpleDto(
                        id = pRow[UsersTable.id].value.toString(),
                        name = pRow[UsersTable.name],
                        department = pRow[UsersTable.department],
                        section = pRow[CourseProfessorsTable.section],
                        schedule = pRow[CourseProfessorsTable.schedule]
                    )
                }

                val myCategories = getProfessorCategoriesInternal(userId, cId).filter { it.isEnabled }.map { it.category }

                CourseDto(
                    id = cId.toString(),
                    name = row[CoursesTable.name],
                    code = row[CoursesTable.code],
                    semester = row[CoursesTable.semester],
                    college = row[CoursesTable.college],
                    department = row[CoursesTable.department],
                    major = row[CoursesTable.major],
                    completionType = row[CoursesTable.completionType],
                    credit = row[CoursesTable.credit],
                    professors = profList,
                    availableCategories = myCategories,
                    assignedProfessorId = userId.toString()
                )
            }
        }
    }

    suspend fun getProfessorCategories(professorId: UUID, courseId: UUID): List<CategorySettingDto> = dbQuery {
        getProfessorCategoriesInternal(professorId, courseId)
    }

    private fun getProfessorCategoriesInternal(professorId: UUID, courseId: UUID): List<CategorySettingDto> {
        val rows = ProfessorCategorySettingsTable.select {
            (ProfessorCategorySettingsTable.professorId eq professorId) and (ProfessorCategorySettingsTable.courseId eq courseId)
        }.toList()

        return if (rows.isEmpty()) {
            QuestionCategory.values().map { cat ->
                ProfessorCategorySettingsTable.insert {
                    it[ProfessorCategorySettingsTable.professorId] = professorId
                    it[ProfessorCategorySettingsTable.courseId] = courseId
                    it[category] = cat.name
                    it[isEnabled] = true
                }
                CategorySettingDto(category = cat, isEnabled = true)
            }
        } else {
            rows.map {
                CategorySettingDto(
                    category = QuestionCategory.valueOf(it[ProfessorCategorySettingsTable.category]),
                    isEnabled = it[ProfessorCategorySettingsTable.isEnabled]
                )
            }
        }
    }

    suspend fun updateCategorySetting(
        professorId: UUID,
        courseId: UUID,
        request: UpdateCategorySettingRequest
    ): List<CategorySettingDto> = dbQuery {
        val existing = ProfessorCategorySettingsTable.select {
            (ProfessorCategorySettingsTable.professorId eq professorId) and
                    (ProfessorCategorySettingsTable.courseId eq courseId) and
                    (ProfessorCategorySettingsTable.category eq request.category.name)
        }.singleOrNull()

        if (existing != null) {
            ProfessorCategorySettingsTable.update({
                (ProfessorCategorySettingsTable.professorId eq professorId) and
                        (ProfessorCategorySettingsTable.courseId eq courseId) and
                        (ProfessorCategorySettingsTable.category eq request.category.name)
            }) {
                it[isEnabled] = request.isEnabled
            }
        } else {
            ProfessorCategorySettingsTable.insert {
                it[ProfessorCategorySettingsTable.professorId] = professorId
                it[ProfessorCategorySettingsTable.courseId] = courseId
                it[category] = request.category.name
                it[isEnabled] = request.isEnabled
            }
        }

        getProfessorCategoriesInternal(professorId, courseId)
    }

    private fun getAvailableCategoriesForStudent(professorId: UUID, courseId: UUID): List<QuestionCategory> {
        val rows = ProfessorCategorySettingsTable.select {
            (ProfessorCategorySettingsTable.professorId eq professorId) and
                    (ProfessorCategorySettingsTable.courseId eq courseId)
        }.toList()

        return if (rows.isEmpty()) {
            QuestionCategory.values().toList()
        } else {
            rows.filter { it[ProfessorCategorySettingsTable.isEnabled] }
                .map { QuestionCategory.valueOf(it[ProfessorCategorySettingsTable.category]) }
        }
    }

    suspend fun searchCourses(
        query: String? = null,
        college: String? = null,
        department: String? = null,
        page: Int = 1,
        pageSize: Int = 50
    ): CourseListResponse = dbQuery {
        val conditions = mutableListOf<Op<Boolean>>()

        if (!college.isNullOrBlank()) {
            conditions.add(CoursesTable.college eq college)
        }
        if (!department.isNullOrBlank()) {
            conditions.add(CoursesTable.department eq department)
        }

        if (!query.isNullOrBlank()) {
            val q = query.trim().lowercase()
            val matchedProfCourseIds = CourseProfessorsTable.innerJoin(UsersTable, { CourseProfessorsTable.professorId }, { UsersTable.id })
                .select { UsersTable.name.lowerCase() like "%$q%" }
                .map { it[CourseProfessorsTable.courseId] }
                .distinct()

            val textCondition = (CoursesTable.name.lowerCase() like "%$q%") or
                    (CoursesTable.code.lowerCase() like "%$q%") or
                    (CoursesTable.department.lowerCase() like "%$q%") or
                    (CoursesTable.college.lowerCase() like "%$q%")

            if (matchedProfCourseIds.isNotEmpty()) {
                conditions.add(textCondition or (CoursesTable.id inList matchedProfCourseIds))
            } else {
                conditions.add(textCondition)
            }
        }

        val finalCondition = if (conditions.isNotEmpty()) conditions.reduce { acc, op -> acc and op } else null
        val total = if (finalCondition != null) CoursesTable.select(finalCondition).count() else CoursesTable.selectAll().count()

        val offset = ((page - 1).coerceAtLeast(0) * pageSize).toLong()
        val queryObj = if (finalCondition != null) CoursesTable.select(finalCondition) else CoursesTable.selectAll()
        val rows = queryObj
            .orderBy(CoursesTable.name to SortOrder.ASC)
            .limit(pageSize, offset)
            .toList()

        val courseDtos = rows.map { row ->
            getCourseByIdInternal(row[CoursesTable.id].value)!!
        }

        CourseListResponse(
            total = total,
            page = page,
            pageSize = pageSize,
            courses = courseDtos
        )
    }

    suspend fun getCourseById(courseId: UUID): CourseDto? = dbQuery {
        getCourseByIdInternal(courseId)
    }

    private fun getCourseByIdInternal(courseId: UUID): CourseDto? {
        val row = CoursesTable.select { CoursesTable.id eq courseId }.singleOrNull() ?: return null
        val profRows = CourseProfessorsTable.innerJoin(UsersTable, { CourseProfessorsTable.professorId }, { UsersTable.id })
            .select { CourseProfessorsTable.courseId eq courseId }
            .toList()

        val profList = profRows.map { pRow ->
            ProfessorSimpleDto(
                id = pRow[UsersTable.id].value.toString(),
                name = pRow[UsersTable.name],
                department = pRow[UsersTable.department],
                section = pRow[CourseProfessorsTable.section],
                schedule = pRow[CourseProfessorsTable.schedule]
            )
        }

        return CourseDto(
            id = courseId.toString(),
            name = row[CoursesTable.name],
            code = row[CoursesTable.code],
            semester = row[CoursesTable.semester],
            college = row[CoursesTable.college],
            department = row[CoursesTable.department],
            major = row[CoursesTable.major],
            completionType = row[CoursesTable.completionType],
            credit = row[CoursesTable.credit],
            professors = profList,
            availableCategories = listOf(QuestionCategory.GRADE, QuestionCategory.ASSIGNMENT, QuestionCategory.LECTURE)
        )
    }

    suspend fun getCoursesSummary(): CourseSummaryDto = dbQuery {
        val totalCourses = CoursesTable.selectAll().count()
        val totalSections = CourseProfessorsTable.selectAll().count()
        val totalProfessors = UsersTable.select { UsersTable.role eq UserRole.PROFESSOR.name }.count()
        val colleges = CoursesTable.slice(CoursesTable.college)
            .selectAll()
            .map { it[CoursesTable.college] }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        val departments = DepartmentsTable.selectAll()
            .map { it[DepartmentsTable.name] }
            .distinct()
            .sorted()

        CourseSummaryDto(
            title = "2026학년도 2학기 개설과목 및 강의시간표",
            semester = "2026-2",
            totalCourses = totalCourses,
            totalSections = totalSections,
            totalProfessors = totalProfessors,
            colleges = colleges,
            departments = departments
        )
    }

    suspend fun getProfessorActiveCategoriesMap(professorId: UUID): Map<String, Boolean> = dbQuery {
        val rows = ProfessorCategorySettingsTable.select { ProfessorCategorySettingsTable.professorId eq professorId }.toList()
        if (rows.isEmpty()) {
            mapOf("grade" to true, "assignment" to true, "lecture" to true)
        } else {
            val map = mutableMapOf("grade" to true, "assignment" to true, "lecture" to true)
            rows.forEach { r ->
                val catKey = r[ProfessorCategorySettingsTable.category].lowercase()
                val isEnabled = r[ProfessorCategorySettingsTable.isEnabled]
                map[catKey] = isEnabled
            }
            map
        }
    }

    suspend fun updateProfessorActiveCategoriesMap(professorId: UUID, categoriesMap: Map<String, Boolean>) = dbQuery {
        val courseIds = CourseProfessorsTable.select { CourseProfessorsTable.professorId eq professorId }
            .map { it[CourseProfessorsTable.courseId] }
            .distinct()
            .ifEmpty {
                CoursesTable.selectAll().map { it[CoursesTable.id].value }
            }

        categoriesMap.forEach { (catKey, isEnabled) ->
            val catEnum = runCatching { QuestionCategory.valueOf(catKey.uppercase()) }.getOrNull() ?: return@forEach
            courseIds.forEach { cId ->
                val exists = ProfessorCategorySettingsTable.select {
                    (ProfessorCategorySettingsTable.professorId eq professorId) and
                            (ProfessorCategorySettingsTable.courseId eq cId) and
                            (ProfessorCategorySettingsTable.category eq catEnum.name)
                }.count() > 0

                if (exists) {
                    ProfessorCategorySettingsTable.update({
                        (ProfessorCategorySettingsTable.professorId eq professorId) and
                                (ProfessorCategorySettingsTable.courseId eq cId) and
                                (ProfessorCategorySettingsTable.category eq catEnum.name)
                    }) {
                        it[ProfessorCategorySettingsTable.isEnabled] = isEnabled
                    }
                } else {
                    ProfessorCategorySettingsTable.insert {
                        it[ProfessorCategorySettingsTable.professorId] = professorId
                        it[ProfessorCategorySettingsTable.courseId] = cId
                        it[ProfessorCategorySettingsTable.category] = catEnum.name
                        it[ProfessorCategorySettingsTable.isEnabled] = isEnabled
                    }
                }
            }
        }
    }
}
