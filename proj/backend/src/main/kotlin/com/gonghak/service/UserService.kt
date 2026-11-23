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

    private val studentIdMiddleCodes: List<String> =
        (1..8).flatMap { d1 -> (1..6).map { d3 -> "${d1}0${d3}" } }

    private val studentIdMiddleRegex = Regex("^[1-8]0[1-6]$")

    suspend fun getNextStudentId(
        entryYear: Int,
        isForeign: Boolean,
        random: Boolean = false
    ): NextStudentIdResponse = dbQuery {
        allocateNextStudentIdInternal(entryYear, isForeign, random)
    }

    private fun allocateNextStudentIdInternal(
        entryYear: Int,
        isForeign: Boolean,
        random: Boolean = false
    ): NextStudentIdResponse {
        val safeYear = entryYear.coerceIn(1900, 2099)
        val yearPrefix = String.format("%04d", safeYear)
        val existingIds = UsersTable.select {
            (UsersTable.role eq UserRole.STUDENT.name) and (UsersTable.identifier like "$yearPrefix%")
        }.map { it[UsersTable.identifier] }.toSet()

        val serialRange = if (isForeign) (501..999) else (1..500)
        val midOrder = if (random) studentIdMiddleCodes.shuffled() else studentIdMiddleCodes

        for (midCode in midOrder) {
            val prefix = "$yearPrefix$midCode"
            val usedSerials = existingIds.mapNotNull { idStr ->
                if (idStr.length == 10 && idStr.startsWith(prefix)) {
                    idStr.substring(7, 10).toIntOrNull()
                } else {
                    null
                }
            }
            val nextSerialNum = if (random) {
                val usedSet = usedSerials.toSet()
                val candidates = serialRange.filter { it !in usedSet }
                candidates.randomOrNull()
            } else if (isForeign) {
                val maxForeign = usedSerials.filter { it in 501..999 }.maxOrNull() ?: 500
                if (maxForeign < 999) maxForeign + 1 else null
            } else {
                val maxDomestic = usedSerials.filter { it in 1..500 }.maxOrNull() ?: 0
                if (maxDomestic < 500) maxDomestic + 1 else null
            }
            if (nextSerialNum != null) {
                val serialStr = String.format("%03d", nextSerialNum)
                return NextStudentIdResponse(
                    studentId = "$prefix$serialStr",
                    year = safeYear,
                    serial = serialStr,
                    isForeign = isForeign
                )
            }
        }

        throw IllegalArgumentException(
            if (isForeign) "해당 연도의 외국인 학생 학번 순번(501~999)을 초과했습니다."
            else "해당 연도의 일반 학생 학번 순번(001~500)을 초과했습니다."
        )
    }

    suspend fun registerUser(request: RegisterRequest): AuthResponse = dbQuery {
        PasswordSecurity.validatePassword(request.password)
        val rawCleanDept = PasswordSecurity.sanitizeInput(request.department).take(80)
        if (rawCleanDept.isBlank()) {
            throw IllegalArgumentException("학과를 입력해 주세요.")
        }
        val cleanDept = DatabaseFactory.resolveCanonicalDepartmentName(rawCleanDept).take(80)

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

        val resolvedStudentId = if (role == UserRole.STUDENT) {
            val requestedId = PasswordSecurity.sanitizeInput(request.identifier)
            if (requestedId.isBlank() || requestedId.equals("AUTO", ignoreCase = true)) {
                allocateNextStudentIdInternal(
                    entryYear = request.entryYear ?: 2026,
                    isForeign = request.isForeign
                ).studentId
            } else {
                if (!Regex("^\\d{10}$").matches(requestedId)) {
                    throw IllegalArgumentException("학번은 (연도 4자리)(1~8 + 0 + 1~6)(순번 3자리: 일반 001~500, 외국인 501~999) 형식의 10자리 숫자여야 합니다.")
                }
                val idYear = requestedId.substring(0, 4).toInt()
                val idMiddleCode = requestedId.substring(4, 7)
                val idSerial = requestedId.substring(7, 10).toInt()
                if (idYear !in 1900..2099 || !studentIdMiddleRegex.matches(idMiddleCode) || idSerial !in 1..999) {
                    throw IllegalArgumentException("학번은 (연도 4자리)(1~8 + 0 + 1~6)(순번 3자리: 일반 001~500, 외국인 501~999) 형식이어야 합니다.")
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

            val rawStudentEmail = request.email?.trim().orEmpty()
            val cleanStudentEmail = if (rawStudentEmail.isNotBlank()) PasswordSecurity.validateEmail(rawStudentEmail) else null
            if (cleanStudentEmail != null) {
                val lowerEmail = cleanStudentEmail.lowercase()
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

            UsersTable.insert {
                it[id] = newUserId
                it[username] = cleanUsername
                it[UsersTable.passwordHash] = passwordHash
                it[email] = cleanStudentEmail
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
            // Fallback: pick up to 2 courses matching department (or its aliases) or default courses
            val deptCandidates = DatabaseFactory.expandDepartmentAliasNames(department)
            val deptCourses = if (deptCandidates.isNotEmpty()) {
                CoursesTable.select { CoursesTable.department inList deptCandidates }
                    .limit(2)
                    .map { it[CoursesTable.id].value }
            } else {
                emptyList()
            }
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
        PushSubscriptionsTable.deleteWhere { PushSubscriptionsTable.userId eq userId }
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

    private fun rowToDepartmentDto(row: ResultRow, aliasesByDept: Map<UUID, List<String>>): DepartmentDto {
        val deptId = row[DepartmentsTable.id].value
        return DepartmentDto(
            id = deptId.toString(),
            name = row[DepartmentsTable.name],
            college = row[DepartmentsTable.college],
            aliases = aliasesByDept[deptId].orEmpty(),
            createdAt = row[DepartmentsTable.createdAt].toString()
        )
    }

    private fun loadAliasesMap(): Map<UUID, List<String>> =
        DepartmentAliasesTable.selectAll()
            .orderBy(DepartmentAliasesTable.createdAt to SortOrder.ASC, DepartmentAliasesTable.aliasName to SortOrder.ASC)
            .groupBy({ it[DepartmentAliasesTable.departmentId] }, { it[DepartmentAliasesTable.aliasName] })

    suspend fun getDepartments(): List<DepartmentDto> = dbQuery {
        val aliasesByDept = loadAliasesMap()
        val deptRows = DepartmentsTable.selectAll()
            .orderBy(DepartmentsTable.name to SortOrder.ASC)
            .map { rowToDepartmentDto(it, aliasesByDept) }
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

    private fun rowToCollegeDto(row: ResultRow, aliasesByCollege: Map<UUID, List<String>>): CollegeDto {
        val colId = row[CollegesTable.id].value
        return CollegeDto(
            id = colId.toString(),
            name = row[CollegesTable.name],
            aliases = aliasesByCollege[colId].orEmpty(),
            createdAt = row[CollegesTable.createdAt].toString()
        )
    }

    private fun loadCollegeAliasesMap(): Map<UUID, List<String>> =
        CollegeAliasesTable.selectAll()
            .orderBy(CollegeAliasesTable.createdAt to SortOrder.ASC, CollegeAliasesTable.aliasName to SortOrder.ASC)
            .groupBy({ it[CollegeAliasesTable.collegeId] }, { it[CollegeAliasesTable.aliasName] })

    private fun validateAliasInputs(
        rawAliases: List<String>,
        canonicalName: String,
        entityLabel: String,
        excludeId: UUID? = null,
        lookupOwner: (String) -> Pair<UUID, String>?
    ): List<String> {
        val cleanAliases = rawAliases
            .map { PasswordSecurity.sanitizeInput(it).take(100) }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        cleanAliases.forEach { alias ->
            if (alias.equals(canonicalName, ignoreCase = true)) {
                throw IllegalArgumentException("${entityLabel} 정식 명칭과 동일한 별칭은 등록할 수 없습니다: $alias")
            }
            val collision = lookupOwner(alias)
            if (collision != null && collision.first != excludeId) {
                throw IllegalArgumentException("이미 다른 ${entityLabel}(${collision.second})에서 사용 중인 명칭/별칭입니다: $alias")
            }
        }
        return cleanAliases
    }

    private fun departmentOwnerLookup(alias: String): Pair<UUID, String>? =
        DatabaseFactory.resolveDepartmentRow(alias)?.let {
            it[DepartmentsTable.id].value to it[DepartmentsTable.name]
        }

    private fun collegeOwnerLookup(alias: String): Pair<UUID, String>? =
        DatabaseFactory.resolveCollegeRow(alias)?.let {
            it[CollegesTable.id].value to it[CollegesTable.name]
        }

    suspend fun getColleges(): List<CollegeDto> = dbQuery {
        val aliasesByCollege = loadCollegeAliasesMap()
        CollegesTable.selectAll()
            .orderBy(CollegesTable.name to SortOrder.ASC)
            .map { rowToCollegeDto(it, aliasesByCollege) }
    }

    suspend fun createCollege(request: CreateCollegeRequest): CollegeDto = dbQuery {
        val cleanName = PasswordSecurity.sanitizeInput(request.name).take(100)
        if (cleanName.isBlank()) {
            throw IllegalArgumentException("단과대학명을 입력해 주세요.")
        }
        if (DatabaseFactory.isReservedCatalogName(cleanName)) {
            throw IllegalArgumentException("예약된 범위 명칭('${cleanName}')은 단과대학명으로 등록할 수 없습니다.")
        }
        val existing = DatabaseFactory.resolveCollegeRow(cleanName)
        if (existing != null) {
            val ownerName = existing[CollegesTable.name]
            throw IllegalArgumentException(
                if (ownerName.equals(cleanName, ignoreCase = true)) "이미 등록된 단과대학입니다: $cleanName"
                else "'$cleanName'은(는) 이미 '${ownerName}'의 기존 단과대학명(별칭)으로 등록되어 있습니다."
            )
        }
        val cleanAliases = validateAliasInputs(request.aliases, cleanName, "단과대학", null, ::collegeOwnerLookup)

        val newId = UUID.randomUUID()
        val now = Instant.now()
        CollegesTable.insert {
            it[id] = newId
            it[name] = cleanName
            it[createdAt] = now
        }
        cleanAliases.forEach { alias ->
            CollegeAliasesTable.insert {
                it[id] = UUID.randomUUID()
                it[collegeId] = newId
                it[aliasName] = alias
            }
        }
        CollegeDto(
            id = newId.toString(),
            name = cleanName,
            aliases = cleanAliases,
            createdAt = now.toString()
        )
    }

    suspend fun updateCollege(collegeId: UUID, request: UpdateCollegeRequest): CollegeDto = dbQuery {
        val row = CollegesTable.select { CollegesTable.id eq collegeId }.singleOrNull()
            ?: throw IllegalArgumentException("존재하지 않는 단과대학입니다.")
        val oldName = row[CollegesTable.name]
        val nextName = request.name?.let { PasswordSecurity.sanitizeInput(it).take(100) }?.takeIf { it.isNotBlank() } ?: oldName
        if (DatabaseFactory.isReservedCatalogName(nextName)) {
            throw IllegalArgumentException("예약된 범위 명칭('${nextName}')은 단과대학명으로 사용할 수 없습니다.")
        }

        if (!nextName.equals(oldName, ignoreCase = true)) {
            val collision = collegeOwnerLookup(nextName)
            if (collision != null && collision.first != collegeId) {
                throw IllegalArgumentException("이미 다른 단과대학(${collision.second})에서 사용 중인 명칭입니다: $nextName")
            }
            // If new canonical name was previously our own alias, delete that alias entry first
            CollegeAliasesTable.deleteWhere {
                (CollegeAliasesTable.collegeId eq collegeId) and
                    (CollegeAliasesTable.aliasName.lowerCase() eq nextName.lowercase())
            }
        }

        CollegesTable.update({ CollegesTable.id eq collegeId }) {
            it[name] = nextName
        }

        if (nextName != oldName) {
            DepartmentsTable.update({ DepartmentsTable.college eq oldName }) {
                it[college] = nextName
            }
            CoursesTable.update({ CoursesTable.college eq oldName }) {
                it[college] = nextName
            }
            CoursesTable.update({ CoursesTable.department eq "$oldName 전체" }) {
                it[department] = "$nextName 전체"
            }
            if (request.keepOldNameAsAlias) {
                DatabaseFactory.ensureCollegeAliasInternal(collegeId, oldName)
            }
        }

        if (request.aliases != null) {
            val cleanAliases = validateAliasInputs(request.aliases, nextName, "단과대학", collegeId, ::collegeOwnerLookup)
            CollegeAliasesTable.deleteWhere { CollegeAliasesTable.collegeId eq collegeId }
            cleanAliases.forEach { alias ->
                CollegeAliasesTable.insert {
                    it[id] = UUID.randomUUID()
                    it[CollegeAliasesTable.collegeId] = collegeId
                    it[aliasName] = alias
                }
            }
        }

        val updatedRow = CollegesTable.select { CollegesTable.id eq collegeId }.single()
        val aliases = CollegeAliasesTable.select { CollegeAliasesTable.collegeId eq collegeId }
            .orderBy(CollegeAliasesTable.createdAt to SortOrder.ASC, CollegeAliasesTable.aliasName to SortOrder.ASC)
            .map { it[CollegeAliasesTable.aliasName] }
        rowToCollegeDto(updatedRow, mapOf(collegeId to aliases))
    }

    suspend fun addCollegeAlias(collegeId: UUID, rawAlias: String): CollegeDto = dbQuery {
        val colRow = CollegesTable.select { CollegesTable.id eq collegeId }.singleOrNull()
            ?: throw IllegalArgumentException("존재하지 않는 단과대학입니다.")
        val cleanAlias = PasswordSecurity.sanitizeInput(rawAlias).take(100)
        if (cleanAlias.isBlank()) {
            throw IllegalArgumentException("등록할 기존 단과대학명(별칭)을 입력해 주세요.")
        }
        val canonName = colRow[CollegesTable.name]
        if (cleanAlias.equals(canonName, ignoreCase = true)) {
            throw IllegalArgumentException("현재 단과대학명과 동일한 이름은 별칭으로 등록할 수 없습니다.")
        }
        val existingCollision = DatabaseFactory.resolveCollegeRow(cleanAlias)
        if (existingCollision != null) {
            val ownerName = existingCollision[CollegesTable.name]
            throw IllegalArgumentException(
                if (existingCollision[CollegesTable.id].value == collegeId) "이미 등록된 별칭입니다: $cleanAlias"
                else "이미 '${ownerName}'에서 사용 중인 단과대학명/별칭입니다: $cleanAlias"
            )
        }
        CollegeAliasesTable.insert {
            it[id] = UUID.randomUUID()
            it[CollegeAliasesTable.collegeId] = collegeId
            it[aliasName] = cleanAlias
        }
        val aliases = CollegeAliasesTable.select { CollegeAliasesTable.collegeId eq collegeId }
            .orderBy(CollegeAliasesTable.createdAt to SortOrder.ASC, CollegeAliasesTable.aliasName to SortOrder.ASC)
            .map { it[CollegeAliasesTable.aliasName] }
        rowToCollegeDto(colRow, mapOf(collegeId to aliases))
    }

    suspend fun renameCollegeAlias(collegeId: UUID, oldAlias: String, newAlias: String): CollegeDto = dbQuery {
        val colRow = CollegesTable.select { CollegesTable.id eq collegeId }.singleOrNull()
            ?: throw IllegalArgumentException("존재하지 않는 단과대학입니다.")
        val cleanOld = PasswordSecurity.sanitizeInput(oldAlias).take(100)
        val cleanNew = PasswordSecurity.sanitizeInput(newAlias).take(100)
        if (cleanOld.isBlank() || cleanNew.isBlank()) {
            throw IllegalArgumentException("기존 별칭과 새 별칭을 모두 입력해 주세요.")
        }
        val canonName = colRow[CollegesTable.name]
        if (cleanNew.equals(canonName, ignoreCase = true)) {
            throw IllegalArgumentException("현재 단과대학명과 동일한 이름은 별칭으로 변경할 수 없습니다.")
        }
        val existingAliasRow = CollegeAliasesTable.select {
            (CollegeAliasesTable.collegeId eq collegeId) and
                (CollegeAliasesTable.aliasName.lowerCase() eq cleanOld.lowercase())
        }.firstOrNull() ?: throw IllegalArgumentException("변경할 기존 별칭을 찾을 수 없습니다: $cleanOld")

        val collision = collegeOwnerLookup(cleanNew)
        if (collision != null && !(collision.first == collegeId && cleanOld.equals(cleanNew, ignoreCase = true))) {
            throw IllegalArgumentException("이미 사용 중인 단과대학명/별칭입니다: $cleanNew")
        }

        CollegeAliasesTable.update({ CollegeAliasesTable.id eq existingAliasRow[CollegeAliasesTable.id] }) {
            it[aliasName] = cleanNew
        }
        val aliases = CollegeAliasesTable.select { CollegeAliasesTable.collegeId eq collegeId }
            .orderBy(CollegeAliasesTable.createdAt to SortOrder.ASC, CollegeAliasesTable.aliasName to SortOrder.ASC)
            .map { it[CollegeAliasesTable.aliasName] }
        rowToCollegeDto(colRow, mapOf(collegeId to aliases))
    }

    suspend fun removeCollegeAlias(collegeId: UUID, rawAlias: String): CollegeDto = dbQuery {
        val colRow = CollegesTable.select { CollegesTable.id eq collegeId }.singleOrNull()
            ?: throw IllegalArgumentException("존재하지 않는 단과대학입니다.")
        val cleanAlias = PasswordSecurity.sanitizeInput(rawAlias).take(100)
        if (cleanAlias.isBlank()) {
            throw IllegalArgumentException("삭제할 별칭을 지정해 주세요.")
        }
        val deletedCount = CollegeAliasesTable.deleteWhere {
            (CollegeAliasesTable.collegeId eq collegeId) and
                (CollegeAliasesTable.aliasName.lowerCase() eq cleanAlias.lowercase())
        }
        if (deletedCount == 0) {
            throw IllegalArgumentException("해당 단과대학에 등록된 별칭을 찾을 수 없습니다: $cleanAlias")
        }
        val aliases = CollegeAliasesTable.select { CollegeAliasesTable.collegeId eq collegeId }
            .orderBy(CollegeAliasesTable.createdAt to SortOrder.ASC, CollegeAliasesTable.aliasName to SortOrder.ASC)
            .map { it[CollegeAliasesTable.aliasName] }
        rowToCollegeDto(colRow, mapOf(collegeId to aliases))
    }

    suspend fun createDepartment(request: CreateDepartmentRequest): DepartmentDto = dbQuery {
        val cleanName = PasswordSecurity.sanitizeInput(request.name).take(100)
        val rawCollege = PasswordSecurity.sanitizeInput(request.college).take(100).ifBlank { "광운대학교" }
        val cleanCollege = DatabaseFactory.ensureCollegeExistsInternal(rawCollege)
        if (cleanName.isBlank()) {
            throw IllegalArgumentException("학과명을 입력해 주세요.")
        }
        if (DatabaseFactory.isReservedCatalogName(cleanName)) {
            throw IllegalArgumentException("예약된 범위 명칭('${cleanName}')은 학과명으로 등록할 수 없습니다.")
        }
        val existingResolved = DatabaseFactory.resolveDepartmentRow(cleanName)
        if (existingResolved != null) {
            val existingCanonName = existingResolved[DepartmentsTable.name]
            if (existingCanonName.equals(cleanName, ignoreCase = true)) {
                throw IllegalArgumentException("이미 등록된 학과입니다: $cleanName")
            } else {
                throw IllegalArgumentException("'$cleanName'은(는) 이미 '${existingCanonName}'의 기존 학과명(별칭)으로 등록되어 있습니다.")
            }
        }

        val cleanAliases = validateAliasInputs(request.aliases, cleanName, "학과", null, ::departmentOwnerLookup)

        val newId = UUID.randomUUID()
        val now = Instant.now()
        DepartmentsTable.insert {
            it[id] = newId
            it[name] = cleanName
            it[college] = cleanCollege
            it[createdAt] = now
        }

        cleanAliases.forEach { alias ->
            DepartmentAliasesTable.insert {
                it[id] = UUID.randomUUID()
                it[departmentId] = newId
                it[aliasName] = alias
            }
        }

        DepartmentDto(
            id = newId.toString(),
            name = cleanName,
            college = cleanCollege,
            aliases = cleanAliases,
            createdAt = now.toString()
        )
    }

    suspend fun updateDepartment(departmentId: UUID, request: UpdateDepartmentRequest): DepartmentDto = dbQuery {
        val deptRow = DepartmentsTable.select { DepartmentsTable.id eq departmentId }.singleOrNull()
            ?: throw IllegalArgumentException("존재하지 않는 학과입니다.")
        val oldName = deptRow[DepartmentsTable.name]
        val oldCollege = deptRow[DepartmentsTable.college]

        val nextName = request.name?.let { PasswordSecurity.sanitizeInput(it).take(100) }?.takeIf { it.isNotBlank() } ?: oldName
        if (DatabaseFactory.isReservedCatalogName(nextName)) {
            throw IllegalArgumentException("예약된 범위 명칭('${nextName}')은 학과명으로 사용할 수 없습니다.")
        }
        val nextCollege = request.college?.let {
            val sanitized = PasswordSecurity.sanitizeInput(it).take(100)
            if (sanitized.isBlank()) oldCollege else DatabaseFactory.ensureCollegeExistsInternal(sanitized)
        } ?: oldCollege

        if (!nextName.equals(oldName, ignoreCase = true)) {
            val collision = departmentOwnerLookup(nextName)
            if (collision != null && collision.first != departmentId) {
                throw IllegalArgumentException("이미 다른 학과(${collision.second})에서 사용 중인 명칭입니다: $nextName")
            }
            DepartmentAliasesTable.deleteWhere {
                (DepartmentAliasesTable.departmentId eq departmentId) and
                    (DepartmentAliasesTable.aliasName.lowerCase() eq nextName.lowercase())
            }
        }

        DepartmentsTable.update({ DepartmentsTable.id eq departmentId }) {
            it[name] = nextName
            it[college] = nextCollege
        }

        if (nextName != oldName) {
            val affectedUsers = UsersTable.select { UsersTable.department eq oldName }.toList()
            val newShortDept = nextName.replace("학과", "").replace("학부", "").replace("전공", "").take(4)
            affectedUsers.forEach { u ->
                val uId = u[UsersTable.id]
                val uRole = u[UsersTable.role]
                val uName = u[UsersTable.name]
                val uIdent = u[UsersTable.identifier]
                val nextTag = when (uRole) {
                    UserRole.PROFESSOR.name -> "$nextName ${uName}${if (uName.endsWith("교수")) "" else " 교수"}"
                    UserRole.STUDENT.name -> if (uIdent.length >= 4) "${uIdent.substring(2, 4)}_${newShortDept}_${uName}" else u[UsersTable.displayTag]
                    else -> u[UsersTable.displayTag]
                }
                UsersTable.update({ UsersTable.id eq uId }) {
                    it[department] = nextName
                    it[displayTag] = nextTag
                }
            }
            CoursesTable.update({ CoursesTable.department eq oldName }) {
                it[department] = nextName
                it[college] = nextCollege
            }
            if (request.keepOldNameAsAlias) {
                DatabaseFactory.ensureDepartmentAliasInternal(departmentId, oldName)
            }
        } else if (nextCollege != oldCollege) {
            CoursesTable.update({ CoursesTable.department eq nextName }) {
                it[college] = nextCollege
            }
        }

        if (request.aliases != null) {
            val cleanAliases = validateAliasInputs(request.aliases, nextName, "학과", departmentId, ::departmentOwnerLookup)
            DepartmentAliasesTable.deleteWhere { DepartmentAliasesTable.departmentId eq departmentId }
            cleanAliases.forEach { alias ->
                DepartmentAliasesTable.insert {
                    it[id] = UUID.randomUUID()
                    it[DepartmentAliasesTable.departmentId] = departmentId
                    it[aliasName] = alias
                }
            }
        }

        val updatedRow = DepartmentsTable.select { DepartmentsTable.id eq departmentId }.single()
        val aliases = DepartmentAliasesTable.select { DepartmentAliasesTable.departmentId eq departmentId }
            .orderBy(DepartmentAliasesTable.createdAt to SortOrder.ASC, DepartmentAliasesTable.aliasName to SortOrder.ASC)
            .map { it[DepartmentAliasesTable.aliasName] }
        rowToDepartmentDto(updatedRow, mapOf(departmentId to aliases))
    }

    suspend fun renameDepartmentAlias(departmentId: UUID, oldAlias: String, newAlias: String): DepartmentDto = dbQuery {
        val deptRow = DepartmentsTable.select { DepartmentsTable.id eq departmentId }.singleOrNull()
            ?: throw IllegalArgumentException("존재하지 않는 학과입니다.")
        val cleanOld = PasswordSecurity.sanitizeInput(oldAlias).take(100)
        val cleanNew = PasswordSecurity.sanitizeInput(newAlias).take(100)
        if (cleanOld.isBlank() || cleanNew.isBlank()) {
            throw IllegalArgumentException("기존 별칭과 새 별칭을 모두 입력해 주세요.")
        }
        val canonName = deptRow[DepartmentsTable.name]
        if (cleanNew.equals(canonName, ignoreCase = true)) {
            throw IllegalArgumentException("현재 학과명과 동일한 이름은 별칭으로 변경할 수 없습니다.")
        }
        val existingAliasRow = DepartmentAliasesTable.select {
            (DepartmentAliasesTable.departmentId eq departmentId) and
                (DepartmentAliasesTable.aliasName.lowerCase() eq cleanOld.lowercase())
        }.firstOrNull() ?: throw IllegalArgumentException("변경할 기존 별칭을 찾을 수 없습니다: $cleanOld")

        val collision = departmentOwnerLookup(cleanNew)
        if (collision != null && !(collision.first == departmentId && cleanOld.equals(cleanNew, ignoreCase = true))) {
            throw IllegalArgumentException("이미 사용 중인 학과명/별칭입니다: $cleanNew")
        }

        DepartmentAliasesTable.update({ DepartmentAliasesTable.id eq existingAliasRow[DepartmentAliasesTable.id] }) {
            it[aliasName] = cleanNew
        }
        val aliases = DepartmentAliasesTable.select { DepartmentAliasesTable.departmentId eq departmentId }
            .orderBy(DepartmentAliasesTable.createdAt to SortOrder.ASC, DepartmentAliasesTable.aliasName to SortOrder.ASC)
            .map { it[DepartmentAliasesTable.aliasName] }
        rowToDepartmentDto(deptRow, mapOf(departmentId to aliases))
    }

    suspend fun addDepartmentAlias(departmentId: UUID, rawAlias: String): DepartmentDto = dbQuery {
        val deptRow = DepartmentsTable.select { DepartmentsTable.id eq departmentId }.singleOrNull()
            ?: throw IllegalArgumentException("존재하지 않는 학과입니다.")
        val cleanAlias = PasswordSecurity.sanitizeInput(rawAlias).take(100)
        if (cleanAlias.isBlank()) {
            throw IllegalArgumentException("등록할 기존 학과명(별칭)을 입력해 주세요.")
        }
        val canonName = deptRow[DepartmentsTable.name]
        if (cleanAlias.equals(canonName, ignoreCase = true)) {
            throw IllegalArgumentException("현재 학과명과 동일한 이름은 별칭으로 등록할 수 없습니다.")
        }
        val existingCollision = DatabaseFactory.resolveDepartmentRow(cleanAlias)
        if (existingCollision != null) {
            val ownerName = existingCollision[DepartmentsTable.name]
            if (existingCollision[DepartmentsTable.id].value == departmentId) {
                throw IllegalArgumentException("이미 등록된 별칭입니다: $cleanAlias")
            } else {
                throw IllegalArgumentException("이미 '${ownerName}'에서 사용 중인 학과명/별칭입니다: $cleanAlias")
            }
        }

        DepartmentAliasesTable.insert {
            it[id] = UUID.randomUUID()
            it[DepartmentAliasesTable.departmentId] = departmentId
            it[aliasName] = cleanAlias
        }

        val aliases = DepartmentAliasesTable.select { DepartmentAliasesTable.departmentId eq departmentId }
            .orderBy(DepartmentAliasesTable.createdAt to SortOrder.ASC, DepartmentAliasesTable.aliasName to SortOrder.ASC)
            .map { it[DepartmentAliasesTable.aliasName] }
        rowToDepartmentDto(deptRow, mapOf(departmentId to aliases))
    }

    suspend fun removeDepartmentAlias(departmentId: UUID, rawAlias: String): DepartmentDto = dbQuery {
        val deptRow = DepartmentsTable.select { DepartmentsTable.id eq departmentId }.singleOrNull()
            ?: throw IllegalArgumentException("존재하지 않는 학과입니다.")
        val cleanAlias = PasswordSecurity.sanitizeInput(rawAlias).take(100)
        if (cleanAlias.isBlank()) {
            throw IllegalArgumentException("삭제할 별칭을 지정해 주세요.")
        }
        val deletedCount = DepartmentAliasesTable.deleteWhere {
            (DepartmentAliasesTable.departmentId eq departmentId) and
                (DepartmentAliasesTable.aliasName.lowerCase() eq cleanAlias.lowercase())
        }
        if (deletedCount == 0) {
            throw IllegalArgumentException("해당 학과에 등록된 별칭을 찾을 수 없습니다: $cleanAlias")
        }
        val aliases = DepartmentAliasesTable.select { DepartmentAliasesTable.departmentId eq departmentId }
            .orderBy(DepartmentAliasesTable.createdAt to SortOrder.ASC, DepartmentAliasesTable.aliasName to SortOrder.ASC)
            .map { it[DepartmentAliasesTable.aliasName] }
        rowToDepartmentDto(deptRow, mapOf(departmentId to aliases))
    }

    private fun resolveCourseOrganizationInternal(
        rawScopeType: String?,
        rawCollegeInput: String,
        rawDeptInput: String
    ): Triple<String, String, String> {
        val rawScope = rawScopeType?.trim()?.uppercase()
        val rawDept = PasswordSecurity.sanitizeInput(rawDeptInput).take(100)
        val rawCollege = PasswordSecurity.sanitizeInput(rawCollegeInput).take(100)

        val effectiveScope = when {
            rawScope in setOf("ALL", "COLLEGE", "DEPARTMENT") -> rawScope!!
            rawDept == "전체" || (rawDept == "공통" && (rawCollege.isBlank() || rawCollege == "전체" || rawCollege == "광운대학교")) -> "ALL"
            rawDept.endsWith(" 전체") -> "COLLEGE"
            else -> "DEPARTMENT"
        }

        val (finalCollege, finalDept) = when (effectiveScope) {
            "ALL" -> "전체" to "전체"
            "COLLEGE" -> {
                val rawTargetCollege = rawCollege.takeIf { it.isNotBlank() && it != "전체" && it != "광운대학교" }
                    ?: rawDept.removeSuffix(" 전체").trim().takeIf { it.isNotBlank() && it != "전체" }
                    ?: throw IllegalArgumentException("주관 단과대를 선택해 주세요.")
                val targetCollege = DatabaseFactory.ensureCollegeExistsInternal(rawTargetCollege)
                targetCollege to "$targetCollege 전체"
            }
            else -> {
                val targetDept = if (rawDept.isBlank() || rawDept == "전체" || rawDept.endsWith(" 전체")) {
                    "공통"
                } else {
                    DatabaseFactory.resolveCanonicalDepartmentName(rawDept)
                }
                val matchedCollege = DatabaseFactory.resolveDepartmentRow(targetDept)?.get(DepartmentsTable.college)
                val rawTargetCollege = rawCollege.takeIf { it.isNotBlank() && it != "광운대학교" && it != "전체" }
                    ?: matchedCollege?.takeIf { it.isNotBlank() }
                    ?: "광운대학교"
                val targetCollege = DatabaseFactory.ensureCollegeExistsInternal(rawTargetCollege)
                targetCollege to targetDept
            }
        }

        // Only auto-register real department names (never "전체", "공통", "<단과대> 전체", or already-aliased names)
        if (effectiveScope == "DEPARTMENT" &&
            finalDept != "공통" &&
            finalDept != "전체" &&
            !finalDept.endsWith(" 전체") &&
            DatabaseFactory.resolveDepartmentRow(finalDept) == null
        ) {
            DepartmentsTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = finalDept
                it[college] = finalCollege
            }
        }

        return Triple(effectiveScope, finalCollege, finalDept)
    }

    suspend fun createCourse(request: CreateCourseRequest): CourseDto = dbQuery {
        val cleanName = PasswordSecurity.sanitizeInput(request.name).take(100)
        if (cleanName.isBlank()) {
            throw IllegalArgumentException("과목명을 입력해 주세요.")
        }
        val (_, finalCollege, finalDept) = resolveCourseOrganizationInternal(
            request.scopeType,
            request.college,
            request.department
        )
        val cleanCode = PasswordSecurity.sanitizeInput(request.code).take(50).ifBlank { "KW-${(1000..9999).random()}" }
        val cleanSemester = PasswordSecurity.sanitizeInput(request.semester).take(20).ifBlank { "2026-2" }

        val newCourseId = UUID.randomUUID()
        CoursesTable.insert {
            it[id] = newCourseId
            it[name] = cleanName
            it[code] = cleanCode
            it[semester] = cleanSemester
            it[college] = finalCollege
            it[department] = finalDept
            it[major] = PasswordSecurity.sanitizeInput(request.major).take(100)
            it[completionType] = PasswordSecurity.sanitizeInput(request.completionType).take(50).ifBlank { "전선" }
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

    suspend fun updateCourse(courseId: UUID, request: UpdateCourseRequest): CourseDto = dbQuery {
        val existing = CoursesTable.select { CoursesTable.id eq courseId }.singleOrNull()
            ?: throw IllegalArgumentException("존재하지 않는 과목입니다.")

        val nextName = request.name?.let { PasswordSecurity.sanitizeInput(it).take(100) } ?: existing[CoursesTable.name]
        if (nextName.isBlank()) {
            throw IllegalArgumentException("과목명을 입력해 주세요.")
        }

        val nextCode = request.code?.let { PasswordSecurity.sanitizeInput(it).take(50) } ?: existing[CoursesTable.code]
        if (nextCode.isBlank()) {
            throw IllegalArgumentException("학수번호(코드)를 입력해 주세요.")
        }

        val nextSemester = request.semester?.let {
            PasswordSecurity.sanitizeInput(it).take(20).ifBlank { existing[CoursesTable.semester] }
        } ?: existing[CoursesTable.semester]

        val orgChanged = request.scopeType != null || request.college != null || request.department != null
        val (nextCollege, nextDept) = if (orgChanged) {
            val existingDept = existing[CoursesTable.department]
            val existingCollege = existing[CoursesTable.college]
            val inferredExistingScope = when {
                existingDept == "전체" || existingCollege == "전체" -> "ALL"
                existingDept.endsWith(" 전체") -> "COLLEGE"
                else -> "DEPARTMENT"
            }
            val (_, resolvedCollege, resolvedDept) = resolveCourseOrganizationInternal(
                request.scopeType ?: inferredExistingScope,
                request.college ?: existingCollege,
                request.department ?: existingDept
            )
            resolvedCollege to resolvedDept
        } else {
            existing[CoursesTable.college] to existing[CoursesTable.department]
        }

        val nextMajor = request.major?.let { PasswordSecurity.sanitizeInput(it).take(100) } ?: existing[CoursesTable.major]
        val nextCompletionType = request.completionType?.let {
            PasswordSecurity.sanitizeInput(it).take(50).ifBlank { "전선" }
        } ?: existing[CoursesTable.completionType]
        val nextCredit = request.credit?.coerceIn(1, 6) ?: existing[CoursesTable.credit]

        CoursesTable.update({ CoursesTable.id eq courseId }) {
            it[name] = nextName
            it[code] = nextCode
            it[semester] = nextSemester
            it[college] = nextCollege
            it[department] = nextDept
            it[major] = nextMajor
            it[completionType] = nextCompletionType
            it[credit] = nextCredit
        }

        getCourseByIdInternal(courseId)!!
    }

    suspend fun deleteCourse(courseId: UUID): Pair<Int, List<String>> = dbQuery {
        val exists = CoursesTable.select { CoursesTable.id eq courseId }.count() > 0
        if (!exists) {
            throw IllegalArgumentException("존재하지 않는 과목입니다.")
        }

        val questionIds = QuestionsTable.select { QuestionsTable.courseId eq courseId }
            .map { it[QuestionsTable.id].value }
        val answerIds = if (questionIds.isNotEmpty()) {
            AnswersTable.select { AnswersTable.questionId inList questionIds }
                .map { it[AnswersTable.id].value }
        } else {
            emptyList()
        }
        val attachmentNames = if (questionIds.isNotEmpty()) {
            AttachmentsTable.select { AttachmentsTable.questionId inList questionIds }
                .map { it[AttachmentsTable.savedName] }
        } else {
            emptyList()
        }

        if (questionIds.isNotEmpty()) {
            NotificationsTable.deleteWhere { NotificationsTable.referenceId inList questionIds }
            if (answerIds.isNotEmpty()) {
                NotificationsTable.deleteWhere { NotificationsTable.referenceId inList answerIds }
            }
            AttachmentsTable.deleteWhere { AttachmentsTable.questionId inList questionIds }
            AnswersTable.deleteWhere { AnswersTable.questionId inList questionIds }
            QuestionsTable.deleteWhere { QuestionsTable.courseId eq courseId }
        }
        ProfessorCategorySettingsTable.deleteWhere { ProfessorCategorySettingsTable.courseId eq courseId }
        EnrollmentsTable.deleteWhere { EnrollmentsTable.courseId eq courseId }
        CourseProfessorsTable.deleteWhere { CourseProfessorsTable.courseId eq courseId }
        CoursesTable.deleteWhere { CoursesTable.id eq courseId }

        questionIds.size to attachmentNames
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
        return if (lower in setOf("light", "dark", "green", "green-dark", "blue-white", "system")) lower else "light"
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
                themeMode = normalizeThemeMode(row[UserSettingsTable.themeMode]),
                fontSize = normalizeFontSize(row[UserSettingsTable.fontSize]),
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

                val profCanonDept = DatabaseFactory.resolveCanonicalDepartmentName(profDept)
                val targetPlaceholder = matchingPlaceholders.firstOrNull {
                    DatabaseFactory.resolveCanonicalDepartmentName(it[UsersTable.department].trim()) == profCanonDept
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

    private fun ensureCourseTeachesOrAdmin(professorId: UUID, courseId: UUID) {
        val courseExists = CoursesTable.select { CoursesTable.id eq courseId }.count() > 0
        if (!courseExists) {
            throw IllegalArgumentException("존재하지 않는 강의입니다.")
        }
        val teaches = !CourseProfessorsTable.select {
            (CourseProfessorsTable.courseId eq courseId) and (CourseProfessorsTable.professorId eq professorId)
        }.empty()
        val isAdminUser = UsersTable.select { UsersTable.id eq professorId }.singleOrNull()
            ?.let { it[UsersTable.isAdmin] || it[UsersTable.role] == UserRole.ADMIN.name } ?: false
        if (!teaches && !isAdminUser) {
            throw IllegalStateException("담당 강의가 아닙니다.")
        }
    }

    suspend fun getProfessorCategories(professorId: UUID, courseId: UUID): List<CategorySettingDto> = dbQuery {
        getProfessorCategoriesInternal(professorId, courseId)
    }

    private fun getProfessorCategoriesInternal(professorId: UUID, courseId: UUID): List<CategorySettingDto> {
        ensureCourseTeachesOrAdmin(professorId, courseId)
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
        ensureCourseTeachesOrAdmin(professorId, courseId)
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
            val collegeVariants = DatabaseFactory.expandCollegeAliasNames(college)
            if (collegeVariants.isNotEmpty()) {
                conditions.add(CoursesTable.college inList collegeVariants)
            }
        }
        if (!department.isNullOrBlank()) {
            val deptVariants = DatabaseFactory.expandDepartmentAliasNames(department)
            if (deptVariants.isNotEmpty()) {
                conditions.add(CoursesTable.department inList deptVariants)
            }
        }

        if (!query.isNullOrBlank()) {
            val rawQuery = query.trim().lowercase()
            val q = rawQuery.replace("%", "").replace("_", "").trim()
            if (rawQuery.isNotEmpty()) {
                val matchedProfCourseIds = if (q.isNotEmpty()) {
                    CourseProfessorsTable.innerJoin(UsersTable, { CourseProfessorsTable.professorId }, { UsersTable.id })
                        .select { UsersTable.name.lowerCase() like "%$q%" }
                        .map { it[CourseProfessorsTable.courseId] }
                        .distinct()
                } else {
                    emptyList()
                }

                val aliasMatchedDeptNames = DepartmentAliasesTable.innerJoin(
                    DepartmentsTable,
                    { DepartmentAliasesTable.departmentId },
                    { DepartmentsTable.id }
                )
                    .selectAll()
                    .filter {
                        val aLower = it[DepartmentAliasesTable.aliasName].lowercase()
                        val dLower = it[DepartmentsTable.name].lowercase()
                        aLower.contains(rawQuery) || dLower.contains(rawQuery)
                    }
                    .flatMap { listOf(it[DepartmentsTable.name], it[DepartmentAliasesTable.aliasName]) }
                    .distinct()

                val aliasMatchedCollegeNames = CollegeAliasesTable.innerJoin(
                    CollegesTable,
                    { CollegeAliasesTable.collegeId },
                    { CollegesTable.id }
                )
                    .selectAll()
                    .filter {
                        val aLower = it[CollegeAliasesTable.aliasName].lowercase()
                        val cLower = it[CollegesTable.name].lowercase()
                        aLower.contains(rawQuery) || cLower.contains(rawQuery)
                    }
                    .flatMap { listOf(it[CollegesTable.name], it[CollegeAliasesTable.aliasName]) }
                    .distinct()

                val baseTextCondition: Op<Boolean>? = if (q.isNotEmpty()) {
                    (CoursesTable.name.lowerCase() like "%$q%") or
                            (CoursesTable.code.lowerCase() like "%$q%") or
                            (CoursesTable.department.lowerCase() like "%$q%") or
                            (CoursesTable.college.lowerCase() like "%$q%")
                } else {
                    null
                }

                val deptAliasCondition: Op<Boolean>? = if (aliasMatchedDeptNames.isNotEmpty()) {
                    CoursesTable.department inList aliasMatchedDeptNames
                } else {
                    null
                }

                val collegeAliasCondition: Op<Boolean>? = if (aliasMatchedCollegeNames.isNotEmpty()) {
                    (CoursesTable.college inList aliasMatchedCollegeNames) or
                            (CoursesTable.department inList aliasMatchedCollegeNames.map { "$it 전체" })
                } else {
                    null
                }

                val textCondition = listOfNotNull(baseTextCondition, deptAliasCondition, collegeAliasCondition)
                    .reduceOrNull { acc, op -> acc or op }
                val profCondition: Op<Boolean>? = if (matchedProfCourseIds.isNotEmpty()) {
                    CoursesTable.id inList matchedProfCourseIds
                } else {
                    null
                }

                listOfNotNull(textCondition, profCondition)
                    .reduceOrNull { acc, op -> acc or op }
                    ?.let { conditions.add(it) }
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
