package com.gonghak.service

import com.gonghak.config.DatabaseFactory
import com.gonghak.config.DatabaseFactory.dbQuery
import com.gonghak.config.DatabaseFactory.dbQueryWithRetry
import com.gonghak.config.EnvConfig
import com.gonghak.dto.*
import com.gonghak.models.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class UserService {

    private fun resolveAdminNickname(row: ResultRow): String? {
        val isAdminAcc = row[UsersTable.isAdmin] || row[UsersTable.role] == UserRole.ADMIN.name
        if (!isAdminAcc) return null
        return row[UsersTable.nickname]?.trim()?.takeIf { it.isNotEmpty() }
    }

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
            nickname = resolveAdminNickname(row),
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

    suspend fun getNextStudentId(
        entryYear: Int,
        departmentName: String,
        isForeign: Boolean,
        random: Boolean = false
    ): NextStudentIdResponse = dbQuery {
        val cleanDept = PasswordSecurity.sanitizeInput(departmentName)
        val deptCode = if (cleanDept.isBlank()) {
            "101"
        } else {
            DepartmentsTable.select { DepartmentsTable.name eq cleanDept }
                .singleOrNull()?.get(DepartmentsTable.code)?.takeIf { it.isNotBlank() } ?: "101"
        }
        allocateNextStudentIdInternal(entryYear, deptCode, isForeign, random)
    }

    private fun allocateNextStudentIdInternal(
        entryYear: Int,
        deptCode: String,
        isForeign: Boolean,
        random: Boolean = false
    ): NextStudentIdResponse {
        val safeYear = entryYear.coerceIn(1900, 2099)
        val trimmedDeptCode = deptCode.trim()
        val safeDeptCode = if (Regex("^[1-9](?:0[1-9]|[1-9]\\d)$").matches(trimmedDeptCode)) trimmedDeptCode else "101"
        val prefix = String.format("%04d%s", safeYear, safeDeptCode)

        val existingSerials = UsersTable.select {
            (UsersTable.role eq UserRole.STUDENT.name) and (UsersTable.identifier like "$prefix%")
        }.mapNotNull { row ->
            val idStr = row[UsersTable.identifier]
            if (idStr.length == 10 && idStr.startsWith(prefix)) {
                idStr.substring(7, 10).toIntOrNull()
            } else {
                null
            }
        }

        val nextSerialNum = if (random) {
            val range = if (isForeign) (501..999) else (1..500)
            val existingSet = existingSerials.toSet()
            val candidates = range.filter { it !in existingSet }
            if (candidates.isEmpty()) {
                throw IllegalArgumentException(
                    if (isForeign) "해당 연도/학과의 외국인 학생 학번 순번(501~999)을 초과했습니다."
                    else "해당 연도/학과의 일반 학생 학번 순번(001~500)을 초과했습니다."
                )
            }
            candidates.random()
        } else if (isForeign) {
            val maxForeign = existingSerials.filter { it in 501..999 }.maxOrNull() ?: 500
            if (maxForeign >= 999) {
                throw IllegalArgumentException("해당 연도/학과의 외국인 학생 학번 순번(501~999)을 초과했습니다.")
            }
            maxForeign + 1
        } else {
            val maxDomestic = existingSerials.filter { it in 1..500 }.maxOrNull() ?: 0
            if (maxDomestic >= 500) {
                throw IllegalArgumentException("해당 연도/학과의 일반 학생 학번 순번(001~500)을 초과했습니다.")
            }
            maxDomestic + 1
        }

        val serialStr = String.format("%03d", nextSerialNum)
        return NextStudentIdResponse(
            studentId = "$prefix$serialStr",
            year = safeYear,
            departmentCode = safeDeptCode,
            serial = serialStr,
            isForeign = isForeign
        )
    }

    suspend fun registerUser(request: RegisterRequest): AuthResponse = dbQuery {
        PasswordSecurity.validatePassword(request.password)
        val cleanDept = PasswordSecurity.sanitizeInput(request.department).take(80)
        if (cleanDept.isBlank()) {
            throw IllegalArgumentException("학과를 입력해 주세요.")
        }

        val role = if (request.role == UserRole.PROFESSOR) UserRole.PROFESSOR else UserRole.STUDENT
        if (role == UserRole.PROFESSOR) {
            val requiredInviteCode = EnvConfig.get("PROFESSOR_INVITE_CODE")
            if (!requiredInviteCode.isNullOrBlank()) {
                val providedCode = request.professorInviteCode?.trim().orEmpty()
                val matches = MessageDigest.isEqual(
                    requiredInviteCode.toByteArray(StandardCharsets.UTF_8),
                    providedCode.toByteArray(StandardCharsets.UTF_8)
                )
                if (!matches) {
                    throw IllegalStateException("교수 가입 인증 코드가 올바르지 않습니다.")
                }
            }
        }
        val passwordHash = PasswordSecurity.hashPassword(request.password)
        val newUserId = UUID.randomUUID()

        // Resolve 3-digit department code from existing catalog without mutating DepartmentsTable
        val existingDeptRow = DepartmentsTable.select { DepartmentsTable.name eq cleanDept }.singleOrNull()
        val deptCode = existingDeptRow?.get(DepartmentsTable.code)?.ifBlank { "101" } ?: "101"

        val resolvedStudentId = if (role == UserRole.STUDENT) {
            val requestedId = PasswordSecurity.sanitizeInput(request.identifier)
            if (requestedId.isBlank() || requestedId.equals("AUTO", ignoreCase = true)) {
                allocateNextStudentIdInternal(
                    entryYear = request.entryYear ?: 2026,
                    deptCode = deptCode,
                    isForeign = request.isForeign
                ).studentId
            } else {
                if (!Regex("^\\d{10}$").matches(requestedId)) {
                    throw IllegalArgumentException("학번은 (연도 4자리)(단과대 1~9 + 학과 01~99)(순번 3자리: 일반 001~500, 외국인 501~999) 형식의 10자리 숫자여야 합니다.")
                }
                val idYear = requestedId.substring(0, 4).toInt()
                val idDeptCode = requestedId.substring(4, 7)
                val idSerial = requestedId.substring(7, 10).toInt()
                if (idYear !in 1900..2099 || !Regex("^[1-9](?:0[1-9]|[1-9]\\d)$").matches(idDeptCode) || idSerial !in 1..999) {
                    throw IllegalArgumentException("학번은 (연도 4자리)(단과대 1~9 + 학과 01~99)(순번 3자리: 일반 001~500, 외국인 501~999) 형식이어야 합니다.")
                }
                if (request.isForeign && idSerial < 501) {
                    throw IllegalArgumentException("외국인 학생의 학번 순번은 501부터 시작해야 합니다.")
                }
                requestedId
            }
        } else {
            null
        }

        val rawLoginInput = if (role == UserRole.STUDENT && request.username.isBlank()) {
            resolvedStudentId!!
        } else {
            request.username
        }
        val cleanUsername = PasswordSecurity.validateUsername(rawLoginInput)

        // 1. Check duplicate username (case-insensitive)
        val existingUsername = UsersTable.select {
            UsersTable.username.lowerCase() eq cleanUsername.lowercase()
        }.count() > 0
        if (existingUsername) {
            throw IllegalArgumentException("이미 사용 중인 아이디(ID)입니다.")
        }

        val envAdmins = (System.getenv("ADMIN_USERS") ?: "")
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        val isAutoAdmin = cleanUsername.lowercase() in envAdmins

        if (role == UserRole.STUDENT) {
            val cleanStudentId = resolvedStudentId!!
            val cleanName = PasswordSecurity.sanitizeInput(request.name).take(80)
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

            val yearPrefix = cleanStudentId.substring(2, 4)
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

            // Enroll in selected courses or default courses (preferring registered professors over timetable placeholders)
            val targetCourseUuids = resolveCourseUuids(request.courseIds, request.courseNames, cleanDept)
            targetCourseUuids.forEach { cId ->
                val assignedProfId = pickDefaultProfessorIdForCourse(cId)

                EnrollmentsTable.insert {
                    it[courseId] = cId
                    it[studentId] = newUserId
                    it[assignedProfessorId] = assignedProfId
                }
            }
        } else {
            // PROFESSOR registration (email optional in R4; disables email notifications if omitted)
            val rawEmail = request.email?.trim().orEmpty()
            val cleanEmail = if (rawEmail.isNotBlank()) PasswordSecurity.validateEmail(rawEmail) else null
            val cleanName = PasswordSecurity.sanitizeInput(request.name).take(80).ifBlank { cleanUsername }

            if (cleanEmail != null) {
                val lowerEmail = cleanEmail.lowercase()
                val dupUserEmail = UsersTable.select {
                    UsersTable.email.lowerCase() eq lowerEmail
                }.count() > 0
                val dupNotifEmail = ProfessorNotificationSettingsTable.select {
                    ProfessorNotificationSettingsTable.notificationEmail.lowerCase() eq lowerEmail
                }.count() > 0
                if (dupUserEmail || dupNotifEmail) {
                    throw IllegalArgumentException("이미 등록된 이메일 주소입니다. 중복 가입이 불가능합니다.")
                }
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
                it[emailEnabled] = (cleanEmail != null)
                it[notificationEmail] = cleanEmail
            }

            // Assign professor to selected courses
            val targetCourseUuids = resolveCourseUuids(request.courseIds, request.courseNames, cleanDept)
            assignProfessorToCoursesInternal(newUserId, targetCourseUuids)
        }

        UserSettingsTable.insert {
            it[userId] = newUserId
            it[themeMode] = "light"
            it[fontSize] = "md"
            it[helpLevel] = "SOME"
            it[exhibitionMode] = false
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
        val cleanLoginId = PasswordSecurity.sanitizeInput(request.username)
        if (cleanLoginId.isBlank() || request.password.isBlank()) {
            throw IllegalArgumentException("아이디(학번)와 비밀번호를 모두 입력해 주세요.")
        }
        val cleanLoginLower = cleanLoginId.lowercase()

        // Match real registered accounts by username OR non-professor identifier (학번 등).
        // Collect all candidates instead of .singleOrNull() / .firstOrNull() so demo rows (isDemo=true),
        // withdrawn duplicates, case differences, or username/identifier overlaps never block active login.
        val candidates = UsersTable.select {
            (UsersTable.isDemo eq false) and
                UsersTable.passwordHash.isNotNull() and
                (
                    (UsersTable.username.lowerCase() eq cleanLoginLower) or
                        (
                            (UsersTable.role.lowerCase() neq UserRole.PROFESSOR.name.lowercase()) and
                                (UsersTable.identifier.lowerCase() eq cleanLoginLower)
                        )
                )
        }.toList()

        if (candidates.isEmpty()) {
            PasswordSecurity.dummyVerifyPassword(request.password)
            throw IllegalArgumentException("존재하지 않는 아이디(학번)이거나 비밀번호가 올바르지 않습니다.")
        }

        val orderedCandidates = candidates.sortedBy { row ->
            if (row[UsersTable.username]?.trim()?.lowercase() == cleanLoginLower) 0 else 1
        }
        val activeCandidates = orderedCandidates.filter { !it[UsersTable.isWithdrawn] }
        val matchedActiveRow = activeCandidates.firstOrNull { row ->
            PasswordSecurity.verifyPassword(request.password, row[UsersTable.passwordHash])
        }

        val row = if (matchedActiveRow != null) {
            matchedActiveRow
        } else {
            val withdrawnCandidates = orderedCandidates.filter { it[UsersTable.isWithdrawn] }
            if (withdrawnCandidates.any { candidate ->
                    PasswordSecurity.verifyPassword(request.password, candidate[UsersTable.passwordHash])
                }
            ) {
                throw IllegalStateException("관리자에 의해 탈퇴 처리된 계정입니다. 로그인이 제한됩니다.")
            }
            throw IllegalArgumentException("존재하지 않는 아이디(학번)이거나 비밀번호가 올바르지 않습니다.")
        }

        val uId = row[UsersTable.id].value
        val token = PasswordSecurity.generateAuthToken(uId, row[UsersTable.tokenVersion])
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
            if (!grant) {
                it[tokenVersion] = row[UsersTable.tokenVersion] + 1
            }
        }

        val updated = UsersTable.select { UsersTable.id eq targetId }.single()
        rowToUserDto(updated, includeCourses = true)
    }

    suspend fun getActiveAuthenticatedUser(userId: UUID, tokenVersion: Int): UserDto? = dbQuery {
        val row = UsersTable.select { UsersTable.id eq userId }.singleOrNull() ?: return@dbQuery null
        if (row[UsersTable.isWithdrawn]) return@dbQuery null
        if (row[UsersTable.tokenVersion] != tokenVersion) return@dbQuery null
        rowToUserDto(row, includeCourses = true)
    }

    suspend fun revokeUserTokens(userId: UUID) = dbQuery {
        val row = UsersTable.select { UsersTable.id eq userId }.singleOrNull() ?: return@dbQuery
        UsersTable.update({ UsersTable.id eq userId }) {
            it[tokenVersion] = row[UsersTable.tokenVersion] + 1
        }
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

    suspend fun updateAdminNickname(adminUserId: UUID, rawNickname: String?): UserDto = dbQuery {
        val row = UsersTable.select { UsersTable.id eq adminUserId }.singleOrNull()
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다.")
        if (row[UsersTable.isWithdrawn]) {
            throw IllegalStateException("탈퇴 처리된 계정은 닉네임을 변경할 수 없습니다.")
        }
        val isAdminAcc = row[UsersTable.isAdmin] || row[UsersTable.role] == UserRole.ADMIN.name
        if (!isAdminAcc) {
            throw IllegalStateException("관리자 계정만 닉네임을 설정할 수 있습니다.")
        }

        val cleaned = PasswordSecurity.sanitizeInput(rawNickname).trim().takeIf { it.isNotEmpty() }
        if (cleaned != null && cleaned.length > 30) {
            throw IllegalArgumentException("관리자 닉네임은 최대 30자까지 입력할 수 있습니다.")
        }

        UsersTable.update({ UsersTable.id eq adminUserId }) {
            it[nickname] = cleaned
        }

        val updated = UsersTable.select { UsersTable.id eq adminUserId }.single()
        rowToUserDto(updated, includeCourses = true)
    }

    suspend fun sanctionUser(targetUserId: UUID, request: UserSanctionRequest): UserDto = dbQuery {
        val row = UsersTable.select { UsersTable.id eq targetUserId }.singleOrNull()
            ?: throw IllegalArgumentException("대상 사용자를 찾을 수 없습니다.")

        val action = request.action.trim().uppercase()
        val now = Instant.now()
        val targetIsAdmin = row[UsersTable.isAdmin] ||
            row[UsersTable.role].equals(UserRole.ADMIN.name, ignoreCase = true)
        if (targetIsAdmin && action in setOf("WITHDRAW", "BAN_QUESTION", "BAN_ANSWER")) {
            throw IllegalStateException("관리자 계정은 제재하거나 탈퇴 처리할 수 없습니다.")
        }

        fun calculateBanExpiry(): Instant {
            val minExpiry = now.plus(1, ChronoUnit.MINUTES)
            val maxExpiry = now.plus(3650, ChronoUnit.DAYS)
            if (!request.until.isNullOrBlank()) {
                runCatching {
                    val parsed = Instant.parse(request.until)
                    return parsed.coerceIn(minExpiry, maxExpiry)
                }
            }
            val days = request.durationDays ?: 0
            val hours = request.durationHours ?: 0
            return when {
                days > 0 -> now.plus(days.coerceIn(1, 3650).toLong(), ChronoUnit.DAYS)
                hours > 0 -> now.plus(hours.coerceIn(1, 87600).toLong(), ChronoUnit.HOURS)
                else -> now.plus(7, ChronoUnit.DAYS) // default 7 days
            }
        }

        UsersTable.update({ UsersTable.id eq targetUserId }) {
            when (action) {
                "WITHDRAW" -> {
                    it[isWithdrawn] = true
                    it[tokenVersion] = row[UsersTable.tokenVersion] + 1
                }
                "RESTORE" -> it[isWithdrawn] = false
                "BAN_QUESTION" -> it[questionBanUntil] = calculateBanExpiry()
                "UNBAN_QUESTION" -> it[questionBanUntil] = null
                "BAN_ANSWER" -> it[answerBanUntil] = calculateBanExpiry()
                "UNBAN_ANSWER" -> it[answerBanUntil] = null
                else -> throw IllegalArgumentException("지원하지 않는 제재 작업입니다: $action")
            }
        }

        if (action == "WITHDRAW") {
            PushSubscriptionsTable.deleteWhere { PushSubscriptionsTable.userId eq targetUserId }
        }

        val updated = UsersTable.select { UsersTable.id eq targetUserId }.single()
        rowToUserDto(updated, includeCourses = false)
    }

    suspend fun getDepartments(): List<DepartmentDto> = dbQuery {
        val deptRows = DepartmentsTable.selectAll()
            .orderBy(DepartmentsTable.code to SortOrder.ASC, DepartmentsTable.name to SortOrder.ASC)
            .map {
                DepartmentDto(
                    id = it[DepartmentsTable.id].value.toString(),
                    name = it[DepartmentsTable.name],
                    code = it[DepartmentsTable.code],
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
                        code = "101",
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

        val deptCode = if (!request.code.isNullOrBlank()) {
            val trimmed = request.code.trim()
            if (!Regex("^[1-9](?:0[1-9]|[1-9]\\d)$").matches(trimmed)) {
                throw IllegalArgumentException("학과 고유번호는 (단과대 1~9)(학과 01~99) 형식의 3자리 숫자여야 합니다.")
            }
            if (DepartmentsTable.select { DepartmentsTable.code eq trimmed }.count() > 0L) {
                throw IllegalArgumentException("이미 사용 중인 학과 고유번호입니다: $trimmed")
            }
            trimmed
        } else {
            DatabaseFactory.allocateDepartmentCodeForCollege(cleanCollege)
        }

        val newId = UUID.randomUUID()
        val now = Instant.now()
        DepartmentsTable.insert {
            it[id] = newId
            it[name] = cleanName
            it[code] = deptCode
            it[college] = cleanCollege
            it[createdAt] = now
        }

        DepartmentDto(
            id = newId.toString(),
            name = cleanName,
            code = deptCode,
            college = cleanCollege,
            createdAt = now.toString()
        )
    }

    suspend fun createCourse(request: CreateCourseRequest): CourseDto = dbQuery {
        val cleanName = PasswordSecurity.sanitizeInput(request.name)
        if (cleanName.isBlank()) {
            throw IllegalArgumentException("과목명을 입력해 주세요.")
        }
        val rawScope = request.scopeType?.trim()?.uppercase()
        val rawDept = PasswordSecurity.sanitizeInput(request.department)
        val rawCollege = PasswordSecurity.sanitizeInput(request.college)

        val effectiveScope = when {
            rawScope in setOf("ALL", "COLLEGE", "DEPARTMENT") -> rawScope!!
            rawDept == "전체" || (rawDept == "공통" && (rawCollege.isBlank() || rawCollege == "전체" || rawCollege == "광운대학교")) -> "ALL"
            rawDept.endsWith(" 전체") -> "COLLEGE"
            else -> "DEPARTMENT"
        }

        val (finalCollege, finalDept) = when (effectiveScope) {
            "ALL" -> "전체" to "전체"
            "COLLEGE" -> {
                val targetCollege = rawCollege.takeIf { it.isNotBlank() && it != "전체" }
                    ?: rawDept.removeSuffix(" 전체").trim().takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("주관 단과대를 선택해 주세요.")
                targetCollege to "$targetCollege 전체"
            }
            else -> {
                val targetDept = rawDept.ifBlank { "공통" }
                val matchedCollege = DepartmentsTable.select { DepartmentsTable.name eq targetDept }
                    .firstOrNull()?.get(DepartmentsTable.college)
                val targetCollege = rawCollege.takeIf { it.isNotBlank() && it != "광운대학교" }
                    ?: matchedCollege?.takeIf { it.isNotBlank() }
                    ?: "광운대학교"
                targetCollege to targetDept
            }
        }

        val cleanCode = PasswordSecurity.sanitizeInput(request.code).ifBlank { "KW-${(1000..9999).random()}" }
        val cleanSemester = PasswordSecurity.sanitizeInput(request.semester).ifBlank { "2026-2" }

        // Only auto-register real department names (never "전체", "공통", or "<단과대> 전체")
        if (effectiveScope == "DEPARTMENT" &&
            finalDept != "공통" &&
            finalDept != "전체" &&
            !finalDept.endsWith(" 전체") &&
            DepartmentsTable.select { DepartmentsTable.name eq finalDept }.count() == 0L
        ) {
            DepartmentsTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = finalDept
                it[code] = DatabaseFactory.allocateDepartmentCodeForCollege(finalCollege)
                it[college] = finalCollege
            }
        }

        val newCourseId = UUID.randomUUID()
        CoursesTable.insert {
            it[id] = newCourseId
            it[name] = cleanName
            it[code] = cleanCode
            it[semester] = cleanSemester
            it[college] = finalCollege
            it[department] = finalDept
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

    suspend fun getUserSetting(userId: UUID): UserSettingDto = dbQueryWithRetry {
        getUserSettingInternal(userId)
    }

    private fun normalizeHelpLevel(raw: String?): String {
        val upper = raw?.trim()?.uppercase() ?: return "SOME"
        return if (upper in listOf("NONE", "SOME", "ALL")) upper else "SOME"
    }

    private fun normalizeThemeMode(raw: String?): String {
        val lower = raw?.trim()?.lowercase() ?: return "light"
        return if (lower in setOf("light", "dark", "green", "green-dark", "system")) lower else "light"
    }

    private fun normalizeFontSize(raw: String?): String {
        val lower = raw?.trim()?.lowercase() ?: return "md"
        return if (lower in setOf("sm", "md", "lg", "xl", "small", "medium", "large", "xlarge")) lower else "md"
    }

    private fun getUserSettingInternal(userId: UUID): UserSettingDto {
        val row = UserSettingsTable.select { UserSettingsTable.userId eq userId }.singleOrNull()
        return if (row != null) {
            UserSettingDto(
                userId = row[UserSettingsTable.userId].toString(),
                themeMode = row[UserSettingsTable.themeMode],
                fontSize = row[UserSettingsTable.fontSize],
                helpLevel = normalizeHelpLevel(row[UserSettingsTable.helpLevel]),
                exhibitionMode = row[UserSettingsTable.exhibitionMode]
            )
        } else {
            if (UsersTable.select { UsersTable.id eq userId }.count() > 0) {
                UserSettingsTable.insert {
                    it[UserSettingsTable.userId] = userId
                    it[themeMode] = "light"
                    it[fontSize] = "md"
                    it[helpLevel] = "SOME"
                    it[exhibitionMode] = false
                }
            }
            UserSettingDto(
                userId = userId.toString(),
                themeMode = "light",
                fontSize = "md",
                helpLevel = "SOME",
                exhibitionMode = false
            )
        }
    }

    suspend fun updateUserSetting(userId: UUID, request: UpdateUserSettingRequest): UserSettingDto = dbQueryWithRetry {
        val exists = UserSettingsTable.select { UserSettingsTable.userId eq userId }.count() > 0
        if (exists) {
            UserSettingsTable.update({ UserSettingsTable.userId eq userId }) {
                request.themeMode?.let { v -> it[themeMode] = normalizeThemeMode(v) }
                request.fontSize?.let { v -> it[fontSize] = normalizeFontSize(v) }
                request.helpLevel?.let { v -> it[helpLevel] = normalizeHelpLevel(v) }
                request.exhibitionMode?.let { v -> it[exhibitionMode] = v }
            }
        } else if (UsersTable.select { UsersTable.id eq userId }.count() > 0) {
            UserSettingsTable.insert {
                it[UserSettingsTable.userId] = userId
                it[themeMode] = normalizeThemeMode(request.themeMode)
                it[fontSize] = normalizeFontSize(request.fontSize)
                it[helpLevel] = normalizeHelpLevel(request.helpLevel)
                it[exhibitionMode] = request.exhibitionMode ?: false
            }
        }
        getUserSettingInternal(userId)
    }

    suspend fun getCoursesForUser(userId: UUID): List<CourseDto> = dbQuery {
        getCoursesForUserInternal(userId)
    }

    private fun isRegisteredProfessorRow(row: ResultRow): Boolean =
        !row[UsersTable.isDemo] && !row[UsersTable.passwordHash].isNullOrBlank()

    private fun loadCourseProfessorDtos(courseId: UUID): List<ProfessorSimpleDto> =
        CourseProfessorsTable.innerJoin(UsersTable, { CourseProfessorsTable.professorId }, { UsersTable.id })
            .select { CourseProfessorsTable.courseId eq courseId }
            .sortedByDescending { isRegisteredProfessorRow(it) }
            .map { pRow ->
                ProfessorSimpleDto(
                    id = pRow[UsersTable.id].value.toString(),
                    name = pRow[UsersTable.name],
                    department = pRow[UsersTable.department],
                    section = pRow[CourseProfessorsTable.section],
                    schedule = pRow[CourseProfessorsTable.schedule],
                    isRegistered = isRegisteredProfessorRow(pRow)
                )
            }

    private fun pickDefaultProfessorIdForCourse(courseId: UUID): UUID? {
        val rows = CourseProfessorsTable.innerJoin(UsersTable, { CourseProfessorsTable.professorId }, { UsersTable.id })
            .select { CourseProfessorsTable.courseId eq courseId }
            .toList()
        val chosen = rows.firstOrNull { isRegisteredProfessorRow(it) } ?: rows.firstOrNull()
        return chosen?.get(UsersTable.id)?.value
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
                val profList = loadCourseProfessorDtos(cId)

                val matchedAssigned = assignedProfId?.let { targetId ->
                    profList.firstOrNull { it.id == targetId.toString() }
                }
                // Upgrade assignment display preference to a registered professor if the stored link points to a placeholder
                // and a real professor is registered for the course (excluding homonym ambiguity only via stored ID match first).
                val effectiveProf = if (matchedAssigned != null && matchedAssigned.isRegistered) {
                    matchedAssigned
                } else {
                    profList.firstOrNull { it.isRegistered } ?: matchedAssigned ?: profList.firstOrNull()
                }
                val effectiveProfId = effectiveProf?.id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                val effectiveProfName = effectiveProf?.name
                val enabledCategories = effectiveProfId?.let {
                    getAvailableCategoriesForStudent(it, cId)
                } ?: QuestionCategory.values().toList()

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
                    assignedProfessorId = effectiveProfId?.toString(),
                    assignedProfessorName = effectiveProfName,
                    assignedProfessorRegistered = effectiveProf?.isRegistered ?: true
                )
            }
        } else {
            val teachingRows = CourseProfessorsTable.innerJoin(CoursesTable, { CourseProfessorsTable.courseId }, { CoursesTable.id })
                .select { CourseProfessorsTable.professorId eq userId }
                .toList()

            return teachingRows.map { row ->
                val cId = row[CourseProfessorsTable.courseId]
                val profList = loadCourseProfessorDtos(cId)
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
                    assignedProfessorId = userId.toString(),
                    assignedProfessorName = user[UsersTable.name],
                    assignedProfessorRegistered = isRegisteredProfessorRow(user)
                )
            }
        }
    }

    private fun assignProfessorToCoursesInternal(professorId: UUID, courseUuids: List<UUID>) {
        val profRow = UsersTable.select { UsersTable.id eq professorId }.singleOrNull()
        val profName = profRow?.get(UsersTable.name)?.trim().orEmpty()
        val profDept = profRow?.get(UsersTable.department)?.trim().orEmpty()

        courseUuids.forEach { cId ->
            val alreadyAssigned = CourseProfessorsTable.select {
                (CourseProfessorsTable.courseId eq cId) and (CourseProfessorsTable.professorId eq professorId)
            }.count() > 0

            if (!alreadyAssigned) {
                // Scope placeholder takeover strictly to (this selected courseId, exact name),
                // tie-breaking homonyms by department and replacing at most one section row.
                val matchingPlaceholders = if (profName.isNotBlank()) {
                    CourseProfessorsTable.innerJoin(UsersTable, { CourseProfessorsTable.professorId }, { UsersTable.id })
                        .select {
                            (CourseProfessorsTable.courseId eq cId) and
                                (UsersTable.isDemo eq true) and
                                UsersTable.passwordHash.isNull() and
                                (UsersTable.name eq profName)
                        }
                        .toList()
                } else {
                    emptyList()
                }

                val targetPlaceholder = matchingPlaceholders.firstOrNull {
                    it[UsersTable.department].trim() == profDept
                } ?: matchingPlaceholders.firstOrNull()

                if (targetPlaceholder != null) {
                    val cpId = targetPlaceholder[CourseProfessorsTable.id].value
                    val oldPlaceholderProfId = targetPlaceholder[CourseProfessorsTable.professorId]

                    CourseProfessorsTable.update({ CourseProfessorsTable.id eq cpId }) {
                        it[CourseProfessorsTable.professorId] = professorId
                    }

                    // Reassign only enrollments & questions on THIS course that pointed to the claimed placeholder.
                    EnrollmentsTable.update({
                        (EnrollmentsTable.courseId eq cId) and
                            (EnrollmentsTable.assignedProfessorId eq oldPlaceholderProfId)
                    }) {
                        it[assignedProfessorId] = professorId
                    }

                    QuestionsTable.update({
                        (QuestionsTable.courseId eq cId) and
                            (QuestionsTable.assignedProfessorId eq oldPlaceholderProfId)
                    }) {
                        it[assignedProfessorId] = professorId
                    }
                } else {
                    val existingSections = CourseProfessorsTable.select { CourseProfessorsTable.courseId eq cId }.count()
                    val nextSection = String.format("%02d분반", existingSections + 1)
                    CourseProfessorsTable.insert {
                        it[courseId] = cId
                        it[CourseProfessorsTable.professorId] = professorId
                        it[section] = nextSection
                        it[schedule] = "학사 시간표 참조"
                    }
                }
            }

            val validProfIds = CourseProfessorsTable.select { CourseProfessorsTable.courseId eq cId }
                .map { it[CourseProfessorsTable.professorId] }
            EnrollmentsTable.update({
                (EnrollmentsTable.courseId eq cId) and
                    (EnrollmentsTable.assignedProfessorId.isNull() or (EnrollmentsTable.assignedProfessorId notInList validProfIds))
            }) {
                it[assignedProfessorId] = professorId
            }
        }
    }

    suspend fun updateUserCourses(userId: UUID, rawCourseIds: List<String>): List<CourseDto> = dbQuery {
        val user = UsersTable.select { UsersTable.id eq userId }.singleOrNull()
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다.")

        val validCourseUuids = rawCourseIds.mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) null
            else runCatching { UUID.fromString(trimmed) }.getOrNull()
                ?: com.gonghak.routes.DEMO_COURSE_ID_MAP[trimmed.lowercase()]
        }.distinct().filter { cId ->
            CoursesTable.select { CoursesTable.id eq cId }.count() > 0
        }

        if (validCourseUuids.isEmpty()) {
            throw IllegalArgumentException("최소 1개 이상의 과목을 선택해 주세요.")
        }

        val role = runCatching { UserRole.valueOf(user[UsersTable.role]) }.getOrDefault(UserRole.STUDENT)
        if (role == UserRole.PROFESSOR) {
            CourseProfessorsTable.deleteWhere {
                (CourseProfessorsTable.professorId eq userId) and
                    (CourseProfessorsTable.courseId notInList validCourseUuids)
            }
            assignProfessorToCoursesInternal(userId, validCourseUuids)
        } else {
            EnrollmentsTable.deleteWhere {
                (EnrollmentsTable.studentId eq userId) and
                    (EnrollmentsTable.courseId notInList validCourseUuids)
            }
            val existingEnrolledIds = EnrollmentsTable.select { EnrollmentsTable.studentId eq userId }
                .map { it[EnrollmentsTable.courseId] }
                .toSet()

            validCourseUuids.filterNot { it in existingEnrolledIds }.forEach { cId ->
                val assignedProfId = pickDefaultProfessorIdForCourse(cId)
                EnrollmentsTable.insert {
                    it[courseId] = cId
                    it[studentId] = userId
                    it[assignedProfessorId] = assignedProfId
                }
            }
        }

        getCoursesForUserInternal(userId)
    }

    suspend fun getProfessorCategories(professorId: UUID, courseId: UUID): List<CategorySettingDto> = dbQuery {
        getProfessorCategoriesInternal(professorId, courseId)
    }

    private fun getProfessorCategoriesInternal(professorId: UUID, courseId: UUID): List<CategorySettingDto> {
        val courseExists = CoursesTable.select { CoursesTable.id eq courseId }.count() > 0
        if (!courseExists) {
            throw IllegalArgumentException("존재하지 않는 강의입니다.")
        }
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
    ): List<CategorySettingDto> = dbQueryWithRetry {
        val courseExists = CoursesTable.select { CoursesTable.id eq courseId }.count() > 0
        if (!courseExists) {
            throw IllegalArgumentException("존재하지 않는 강의입니다.")
        }
        val existing = ProfessorCategorySettingsTable.select {
            (ProfessorCategorySettingsTable.professorId eq professorId) and
                    (ProfessorCategorySettingsTable.courseId eq courseId) and
                    (ProfessorCategorySettingsTable.category eq request.category.name)
        }.firstOrNull()

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
            val q = query.trim().lowercase().replace("%", "").replace("_", "").trim()
            if (q.isNotEmpty()) {
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
        }

        val finalCondition = if (conditions.isNotEmpty()) conditions.reduce { acc, op -> acc and op } else null
        val total = if (finalCondition != null) CoursesTable.select(finalCondition).count() else CoursesTable.selectAll().count()

        val safePage = page.coerceIn(1, 10_000)
        val safePageSize = pageSize.coerceIn(1, 2000)
        val offset = ((safePage - 1).toLong() * safePageSize.toLong())
        val queryObj = if (finalCondition != null) CoursesTable.select(finalCondition) else CoursesTable.selectAll()
        val rows = queryObj
            .orderBy(CoursesTable.name to SortOrder.ASC)
            .limit(safePageSize, offset)
            .toList()

        val courseDtos = rows.map { row ->
            getCourseByIdInternal(row[CoursesTable.id].value)!!
        }

        CourseListResponse(
            total = total,
            page = safePage,
            pageSize = safePageSize,
            courses = courseDtos
        )
    }

    suspend fun getCourseById(courseId: UUID): CourseDto? = dbQuery {
        getCourseByIdInternal(courseId)
    }

    private fun getCourseByIdInternal(courseId: UUID): CourseDto? {
        val row = CoursesTable.select { CoursesTable.id eq courseId }.singleOrNull() ?: return null
        val profList = loadCourseProfessorDtos(courseId)
        val primaryProf = profList.firstOrNull()

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
            availableCategories = listOf(QuestionCategory.GRADE, QuestionCategory.ASSIGNMENT, QuestionCategory.LECTURE),
            assignedProfessorId = primaryProf?.id,
            assignedProfessorName = primaryProf?.name,
            assignedProfessorRegistered = primaryProf?.isRegistered ?: true
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
        if (courseIds.isEmpty()) return@dbQuery

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
