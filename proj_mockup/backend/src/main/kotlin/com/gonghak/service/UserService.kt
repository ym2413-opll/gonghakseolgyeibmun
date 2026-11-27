package com.gonghak.service

import com.gonghak.config.DatabaseFactory
import com.gonghak.config.DatabaseFactory.dbQuery
import com.gonghak.config.DatabaseFactory.dbQueryWithRetry
import com.gonghak.config.EnvConfig
import com.gonghak.dto.*
import com.gonghak.models.*
import com.gonghak.util.SemesterUtils
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

            // Enroll in selected courses/sections or default courses (preferring registered professors over timetable placeholders)
            val explicitSelections = normalizeCourseSelections(request.courseIds, request.courseSelections)
            val targetCourseUuids = if (explicitSelections.isNotEmpty()) {
                explicitSelections.keys.toList()
            } else {
                resolveCourseUuids(request.courseIds, request.courseNames, cleanDept)
            }
            targetCourseUuids.forEach { cId ->
                val requestedCpId = explicitSelections[cId]?.firstOrNull()
                val (resolvedCpId, resolvedProfId) = resolveStudentSectionEnrollment(cId, requestedCpId)

                EnrollmentsTable.insert {
                    it[courseId] = cId
                    it[studentId] = newUserId
                    it[assignedProfessorId] = resolvedProfId
                    it[courseProfessorId] = resolvedCpId
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

            val initialEmailScope = if (cleanEmail != null) "ASSIGNED_SECTION" else "NONE"
            ProfessorNotificationSettingsTable.insert {
                it[professorId] = newUserId
                it[quietHoursEnabled] = false
                it[quietHoursStart] = "22:00"
                it[quietHoursEnd] = "08:00"
                it[notifyOtherProfessorQuestions] = true
                it[emailEnabled] = (cleanEmail != null)
                it[notificationScope] = "ALL_SECTIONS"
                it[emailScope] = initialEmailScope
                it[notificationEmail] = cleanEmail
            }

            // 1. Create/attach any custom courses specified directly during professor signup
            val createdCustomCourseIds = createProfessorSignupCoursesInternal(
                professorId = newUserId,
                professorDept = cleanDept,
                inputs = request.newCourses
            )

            // 2. Assign professor to selected existing courses/sections (skip random fallback if custom courses were created)
            val explicitSelections = normalizeCourseSelections(request.courseIds, request.courseSelections)
            val targetCourseUuids = when {
                explicitSelections.isNotEmpty() -> explicitSelections.keys.toList()
                request.courseNames.isNotEmpty() -> resolveCourseUuids(emptyList(), request.courseNames, cleanDept)
                createdCustomCourseIds.isNotEmpty() -> emptyList()
                else -> resolveCourseUuids(emptyList(), emptyList(), cleanDept)
            }
            if (targetCourseUuids.isNotEmpty()) {
                assignProfessorToCoursesInternal(newUserId, targetCourseUuids, explicitSelections)
            }
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

    suspend fun demoLogin(userId: UUID): AuthResponse = dbQuery {
        val row = UsersTable.select {
            (UsersTable.id eq userId) and (UsersTable.isDemo eq true) and (UsersTable.isWithdrawn eq false)
        }.singleOrNull()
            ?: throw IllegalArgumentException("선택한 목업 계정을 찾을 수 없습니다.")
        val uId = row[UsersTable.id].value
        val token = PasswordSecurity.generateAuthToken(uId, row[UsersTable.tokenVersion])
        val userDto = rowToUserDto(row, includeCourses = true)
        AuthResponse(token = token, user = userDto)
    }

    suspend fun grantOrRevokeAdminByUsername(usernameOrId: String, grant: Boolean = true): UserDto =
        DatabaseFactory.adminTransaction("grantOrRevokeAdmin") {
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

    suspend fun getAllUsersForAdmin(): List<UserDto> =
        DatabaseFactory.adminTransaction("getAllUsersForAdmin") {
        UsersTable.select { UsersTable.isDemo eq false }
            .orderBy(UsersTable.createdAt to SortOrder.DESC)
            .map { rowToUserDto(it, includeCourses = false) }
    }

    suspend fun updateAdminNickname(adminUserId: UUID, rawNickname: String?): UserDto =
        DatabaseFactory.adminTransaction("updateAdminNickname") {
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

    suspend fun sanctionUser(targetUserId: UUID, request: UserSanctionRequest): UserDto =
        DatabaseFactory.adminTransaction("sanctionUser") {
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
        DatabaseFactory.loadAllDepartmentAliasesLinkedMap()

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
        DatabaseFactory.loadAllCollegeAliasesLinkedMap()

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

    suspend fun createCollege(request: CreateCollegeRequest): CollegeDto =
        DatabaseFactory.adminTransaction("createCollege") {
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
            DatabaseFactory.appendCollegeAliasNodeInternal(newId, alias)
        }
        CollegeDto(
            id = newId.toString(),
            name = cleanName,
            aliases = DatabaseFactory.traverseCollegeAliasesInternal(newId),
            createdAt = now.toString()
        )
    }

    suspend fun updateCollege(collegeId: UUID, request: UpdateCollegeRequest): CollegeDto =
        DatabaseFactory.adminTransaction("updateCollege") {
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
            // If new canonical name was previously our own alias, splice-remove that alias node first
            DatabaseFactory.removeCollegeAliasNodeInternal(collegeId, nextName)
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
            DatabaseFactory.replaceCollegeAliasesInternal(collegeId, cleanAliases)
        }

        val updatedRow = CollegesTable.select { CollegesTable.id eq collegeId }.single()
        val aliases = DatabaseFactory.traverseCollegeAliasesInternal(collegeId)
        rowToCollegeDto(updatedRow, mapOf(collegeId to aliases))
    }

    suspend fun addCollegeAlias(collegeId: UUID, rawAlias: String): CollegeDto =
        DatabaseFactory.adminTransaction("addCollegeAlias") {
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
        DatabaseFactory.appendCollegeAliasNodeInternal(collegeId, cleanAlias)
        val aliases = DatabaseFactory.traverseCollegeAliasesInternal(collegeId)
        rowToCollegeDto(colRow, mapOf(collegeId to aliases))
    }

    suspend fun renameCollegeAlias(collegeId: UUID, oldAlias: String, newAlias: String): CollegeDto =
        DatabaseFactory.adminTransaction("renameCollegeAlias") {
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
        val aliases = DatabaseFactory.traverseCollegeAliasesInternal(collegeId)
        rowToCollegeDto(colRow, mapOf(collegeId to aliases))
    }

    suspend fun removeCollegeAlias(collegeId: UUID, rawAlias: String): CollegeDto =
        DatabaseFactory.adminTransaction("removeCollegeAlias") {
        val colRow = CollegesTable.select { CollegesTable.id eq collegeId }.singleOrNull()
            ?: throw IllegalArgumentException("존재하지 않는 단과대학입니다.")
        val cleanAlias = PasswordSecurity.sanitizeInput(rawAlias).take(100)
        if (cleanAlias.isBlank()) {
            throw IllegalArgumentException("삭제할 별칭을 지정해 주세요.")
        }
        if (!DatabaseFactory.removeCollegeAliasNodeInternal(collegeId, cleanAlias)) {
            throw IllegalArgumentException("해당 단과대학에 등록된 별칭을 찾을 수 없습니다: $cleanAlias")
        }
        val aliases = DatabaseFactory.traverseCollegeAliasesInternal(collegeId)
        rowToCollegeDto(colRow, mapOf(collegeId to aliases))
    }

    suspend fun createDepartment(request: CreateDepartmentRequest): DepartmentDto =
        DatabaseFactory.adminTransaction("createDepartment") {
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
            DatabaseFactory.appendDepartmentAliasNodeInternal(newId, alias)
        }

        DepartmentDto(
            id = newId.toString(),
            name = cleanName,
            college = cleanCollege,
            aliases = DatabaseFactory.traverseDepartmentAliasesInternal(newId),
            createdAt = now.toString()
        )
    }

    suspend fun updateDepartment(departmentId: UUID, request: UpdateDepartmentRequest): DepartmentDto =
        DatabaseFactory.adminTransaction("updateDepartment") {
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
            DatabaseFactory.removeDepartmentAliasNodeInternal(departmentId, nextName)
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
            DatabaseFactory.replaceDepartmentAliasesInternal(departmentId, cleanAliases)
        }

        val updatedRow = DepartmentsTable.select { DepartmentsTable.id eq departmentId }.single()
        val aliases = DatabaseFactory.traverseDepartmentAliasesInternal(departmentId)
        rowToDepartmentDto(updatedRow, mapOf(departmentId to aliases))
    }

    suspend fun renameDepartmentAlias(departmentId: UUID, oldAlias: String, newAlias: String): DepartmentDto =
        DatabaseFactory.adminTransaction("renameDepartmentAlias") {
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
        val aliases = DatabaseFactory.traverseDepartmentAliasesInternal(departmentId)
        rowToDepartmentDto(deptRow, mapOf(departmentId to aliases))
    }

    suspend fun addDepartmentAlias(departmentId: UUID, rawAlias: String): DepartmentDto =
        DatabaseFactory.adminTransaction("addDepartmentAlias") {
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

        DatabaseFactory.appendDepartmentAliasNodeInternal(departmentId, cleanAlias)

        val aliases = DatabaseFactory.traverseDepartmentAliasesInternal(departmentId)
        rowToDepartmentDto(deptRow, mapOf(departmentId to aliases))
    }

    suspend fun removeDepartmentAlias(departmentId: UUID, rawAlias: String): DepartmentDto =
        DatabaseFactory.adminTransaction("removeDepartmentAlias") {
        val deptRow = DepartmentsTable.select { DepartmentsTable.id eq departmentId }.singleOrNull()
            ?: throw IllegalArgumentException("존재하지 않는 학과입니다.")
        val cleanAlias = PasswordSecurity.sanitizeInput(rawAlias).take(100)
        if (cleanAlias.isBlank()) {
            throw IllegalArgumentException("삭제할 별칭을 지정해 주세요.")
        }
        if (!DatabaseFactory.removeDepartmentAliasNodeInternal(departmentId, cleanAlias)) {
            throw IllegalArgumentException("해당 학과에 등록된 별칭을 찾을 수 없습니다: $cleanAlias")
        }
        val aliases = DatabaseFactory.traverseDepartmentAliasesInternal(departmentId)
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

    fun getCurrentSemesterInternal(): String {
        val raw = SystemSettingsTable.select { SystemSettingsTable.settingKey eq "current_semester" }
            .singleOrNull()
            ?.get(SystemSettingsTable.settingValue)
        return SemesterUtils.normalizeSemester(raw, SemesterUtils.DEFAULT_SEMESTER)
    }

    suspend fun getCurrentSemester(): String = dbQuery {
        getCurrentSemesterInternal()
    }

    private fun setCurrentSemesterInternal(semester: String): String {
        val normalized = SemesterUtils.normalizeSemester(semester)
        val exists = SystemSettingsTable.select { SystemSettingsTable.settingKey eq "current_semester" }.count() > 0
        if (exists) {
            SystemSettingsTable.update({ SystemSettingsTable.settingKey eq "current_semester" }) {
                it[settingValue] = normalized
                it[updatedAt] = Instant.now()
            }
        } else {
            SystemSettingsTable.insert {
                it[settingKey] = "current_semester"
                it[settingValue] = normalized
                it[updatedAt] = Instant.now()
            }
        }
        return normalized
    }

    private fun getAvailableSemestersInternal(currentSemester: String): List<String> {
        val fromCourses = CoursesTable.slice(CoursesTable.semester)
            .selectAll()
            .map { SemesterUtils.normalizeSemester(it[CoursesTable.semester]) }
        return (fromCourses + currentSemester)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(SemesterUtils::compareSemesters)
    }

    /**
     * Links `courseId.prevCourseId = prevCourseId` and `prevCourseId.nextCourseId = courseId`,
     * detaching any displaced links and preventing cycles.
     */
    private fun linkCoursesInternal(courseId: UUID, prevCourseId: UUID) {
        if (courseId == prevCourseId) {
            throw IllegalArgumentException("같은 강의를 자기 자신과 연동할 수 없습니다.")
        }
        val curRow = CoursesTable.select { CoursesTable.id eq courseId }.singleOrNull()
            ?: throw IllegalArgumentException("대상 강의를 찾을 수 없습니다.")
        val prevRow = CoursesTable.select { CoursesTable.id eq prevCourseId }.singleOrNull()
            ?: throw IllegalArgumentException("연동할 이전 학기 강의를 찾을 수 없습니다.")

        // Cycle check: ensure courseId is not already an ancestor of prevCourseId (bounded by unique visited DB rows)
        var cursor: UUID? = prevRow[CoursesTable.prevCourseId]
        val ancestorVisited = mutableSetOf(prevCourseId)
        while (cursor != null && ancestorVisited.add(cursor)) {
            if (cursor == courseId) {
                throw IllegalArgumentException("강의 연결 리스트에 순환 참조가 발생하므로 연동할 수 없습니다.")
            }
            cursor = CoursesTable.select { CoursesTable.id eq cursor }
                .singleOrNull()
                ?.get(CoursesTable.prevCourseId)
        }

        // Also walk forward from courseId to find its chain tail and verify prevCourseId is not already a descendant
        var tailId: UUID = courseId
        var forwardCursor: UUID? = curRow[CoursesTable.nextCourseId]
        val descVisited = mutableSetOf(courseId)
        while (forwardCursor != null && descVisited.add(forwardCursor)) {
            if (forwardCursor == prevCourseId) {
                throw IllegalArgumentException("강의 연결 리스트에 순환 참조가 발생하므로 연동할 수 없습니다.")
            }
            tailId = forwardCursor
            forwardCursor = CoursesTable.select { CoursesTable.id eq forwardCursor }
                .singleOrNull()
                ?.get(CoursesTable.nextCourseId)
        }

        // Detach curRow's old prev if different
        val oldPrevId = curRow[CoursesTable.prevCourseId]
        if (oldPrevId != null && oldPrevId != prevCourseId) {
            CoursesTable.update({ (CoursesTable.id eq oldPrevId) and (CoursesTable.nextCourseId eq courseId) }) {
                it[nextCourseId] = null
            }
        }

        // If prevRow already had a next node (oldNextId != courseId), splice it after courseId's chain tail
        // so existing chains remain continuously connected without orphaning downstream courses.
        val oldNextId = prevRow[CoursesTable.nextCourseId]
        if (oldNextId != null && oldNextId != courseId) {
            if (oldNextId !in descVisited && oldNextId !in ancestorVisited) {
                CoursesTable.update({ CoursesTable.id eq tailId }) {
                    it[nextCourseId] = oldNextId
                }
                CoursesTable.update({ CoursesTable.id eq oldNextId }) {
                    it[CoursesTable.prevCourseId] = tailId
                }
            } else {
                CoursesTable.update({ (CoursesTable.id eq oldNextId) and (CoursesTable.prevCourseId eq prevCourseId) }) {
                    it[CoursesTable.prevCourseId] = null
                }
            }
        }

        CoursesTable.update({ CoursesTable.id eq courseId }) {
            it[CoursesTable.prevCourseId] = prevCourseId
        }
        CoursesTable.update({ CoursesTable.id eq prevCourseId }) {
            it[CoursesTable.nextCourseId] = courseId
        }
    }

    private fun unlinkPrevCourseInternal(courseId: UUID) {
        val curRow = CoursesTable.select { CoursesTable.id eq courseId }.singleOrNull() ?: return
        val oldPrevId = curRow[CoursesTable.prevCourseId] ?: return
        CoursesTable.update({ CoursesTable.id eq courseId }) {
            it[prevCourseId] = null
        }
        CoursesTable.update({ (CoursesTable.id eq oldPrevId) and (CoursesTable.nextCourseId eq courseId) }) {
            it[nextCourseId] = null
        }
    }

    /**
     * Finds the chronologically closest earlier-semester course with the same normalized name
     * (that does not already point to a `nextCourseId`, or points to `courseId`) and links them.
     */
    private fun autoLinkCourseToPreviousSemesterInternal(courseId: UUID): Boolean {
        val curRow = CoursesTable.select { CoursesTable.id eq courseId }.singleOrNull() ?: return false
        if (curRow[CoursesTable.prevCourseId] != null) return false

        val curSem = SemesterUtils.normalizeSemester(curRow[CoursesTable.semester])
        val curRank = SemesterUtils.semesterRank(curSem)
        val normName = SemesterUtils.normalizeCourseNameForMatch(curRow[CoursesTable.name])
        if (normName.isEmpty() || curRank <= 0) return false

        val curBaseCode = SemesterUtils.splitCourseCodeAndSection(curRow[CoursesTable.code]).first
        val curCanonDept = DatabaseFactory.resolveCanonicalDepartmentName(curRow[CoursesTable.department].trim())

        val candidates = CoursesTable.select { CoursesTable.id neq courseId }
            .toList()
            .filter { cand ->
                val candRank = SemesterUtils.semesterRank(cand[CoursesTable.semester])
                candRank in 1 until curRank &&
                    SemesterUtils.normalizeCourseNameForMatch(cand[CoursesTable.name]) == normName &&
                    (cand[CoursesTable.nextCourseId] == null || cand[CoursesTable.nextCourseId] == courseId)
            }

        if (candidates.isEmpty()) return false

        val best = candidates.sortedWith(
            compareByDescending<ResultRow> { SemesterUtils.semesterRank(it[CoursesTable.semester]) }
                .thenByDescending {
                    val candCode = SemesterUtils.splitCourseCodeAndSection(it[CoursesTable.code]).first
                    curBaseCode.isNotBlank() && candCode.equals(curBaseCode, ignoreCase = true)
                }
                .thenByDescending {
                    val candDept = DatabaseFactory.resolveCanonicalDepartmentName(it[CoursesTable.department].trim())
                    curCanonDept.isNotBlank() && candDept == curCanonDept
                }
        ).first()

        linkCoursesInternal(courseId, best[CoursesTable.id].value)
        return true
    }

    private fun autoLinkSemesterCoursesInternal(targetSemester: String): Int {
        val normSem = SemesterUtils.normalizeSemester(targetSemester)
        val rows = CoursesTable.select { CoursesTable.semester eq normSem }.toList()
        var count = 0
        for (row in rows) {
            if (row[CoursesTable.prevCourseId] == null) {
                if (autoLinkCourseToPreviousSemesterInternal(row[CoursesTable.id].value)) {
                    count++
                }
            }
        }
        return count
    }

    /**
     * Traverses both `prevCourseId` and `nextCourseId` pointers to return all course IDs
     * in the same linked-list lineage (bounded to prevent infinite loops).
     */
    fun collectLinkedCourseIdsInternal(startCourseId: UUID): Set<UUID> {
        val visited = linkedSetOf(startCourseId)
        val startRow = CoursesTable.select { CoursesTable.id eq startCourseId }.singleOrNull() ?: return visited

        var back = startRow[CoursesTable.prevCourseId]
        var guard = 0
        while (back != null && guard++ < 200 && visited.add(back)) {
            back = CoursesTable.select { CoursesTable.id eq back }.singleOrNull()?.get(CoursesTable.prevCourseId)
        }

        var forward = startRow[CoursesTable.nextCourseId]
        guard = 0
        while (forward != null && guard++ < 200 && visited.add(forward)) {
            forward = CoursesTable.select { CoursesTable.id eq forward }.singleOrNull()?.get(CoursesTable.nextCourseId)
        }
        return visited
    }

    suspend fun linkCourse(courseId: UUID, prevCourseIdRaw: String?): CourseDto =
        DatabaseFactory.adminTransaction("linkCourse") {
        val exists = CoursesTable.select { CoursesTable.id eq courseId }.count() > 0
        if (!exists) throw IllegalArgumentException("존재하지 않는 과목입니다.")
        val cleanPrev = prevCourseIdRaw?.trim().orEmpty()
        if (cleanPrev.isEmpty()) {
            unlinkPrevCourseInternal(courseId)
        } else {
            val prevUuid = runCatching { UUID.fromString(cleanPrev) }.getOrNull()
                ?: throw IllegalArgumentException("유효하지 않은 이전 강의 ID입니다.")
            linkCoursesInternal(courseId, prevUuid)
        }
        getCourseByIdInternal(courseId)!!
    }

    suspend fun rolloverSemester(request: SemesterRolloverRequest): SemesterRolloverResultDto =
        DatabaseFactory.adminTransaction("rolloverSemester") {
        val rawTarget = PasswordSecurity.sanitizeInput(request.targetSemester).trim()
        val parsedTarget = SemesterUtils.parseOrNull(rawTarget)
            ?: throw IllegalArgumentException("올바른 학기 형식을 입력해 주세요. (예: 2027-1, 2026-여름, 2026-2, 2026-겨울)")
        val prevSem = getCurrentSemesterInternal()
        val nextSem = setCurrentSemesterInternal(parsedTarget)
        val linkedCount = if (request.autoLinkSameNameCourses) {
            autoLinkSemesterCoursesInternal(nextSem)
        } else {
            0
        }
        SemesterRolloverResultDto(
            previousSemester = prevSem,
            currentSemester = nextSem,
            autoLinkedCount = linkedCount,
            availableSemesters = getAvailableSemestersInternal(nextSem)
        )
    }

    private fun createProfessorSignupCoursesInternal(
        professorId: UUID,
        professorDept: String,
        inputs: List<ProfessorSignupCourseInput>
    ): List<UUID> {
        if (inputs.isEmpty()) return emptyList()
        val currentSem = getCurrentSemesterInternal()
        val allowedSignupSems = SemesterUtils.allowedProfessorSignupSemesters(currentSem).toSet()
        val createdOrAttached = mutableListOf<UUID>()

        inputs.take(10).forEach { item ->
            val cleanName = PasswordSecurity.sanitizeInput(item.name).take(100).trim()
            if (cleanName.isBlank()) return@forEach

            val rawCode = PasswordSecurity.sanitizeInput(item.code).take(50).trim()
            val (splitBase, splitSec) = SemesterUtils.splitCourseCodeAndSection(rawCode)
            if (splitBase.isBlank()) {
                throw IllegalArgumentException("직접 추가하는 강의의 학수번호를 입력해 주세요.")
            }
            val cleanCode = splitBase
            val cleanSection = SemesterUtils.normalizeSectionLabel(
                PasswordSecurity.sanitizeInput(item.section).takeIf { it.isNotBlank() } ?: splitSec,
                defaultLabel = "미정"
            )
            val cleanSem = SemesterUtils.normalizeSemester(item.semester, currentSem)
            if (cleanSem !in allowedSignupSems) {
                throw IllegalArgumentException("개설 학기는 현재 학기와 바로 다음 학기(계절학기 포함)만 선택할 수 있습니다.")
            }
            val rawDept = PasswordSecurity.sanitizeInput(item.department).trim().ifBlank { professorDept }
            val rawCollege = PasswordSecurity.sanitizeInput(item.college).trim()
            val (_, finalCollege, finalDept) = resolveCourseOrganizationInternal("DEPARTMENT", rawCollege, rawDept)
            val rawCompType = PasswordSecurity.sanitizeInput(item.completionType).take(50).trim()
            val cleanCompType = when (rawCompType) {
                "", "전선" -> "전공선택"
                "전필" -> "전공필수"
                "교선" -> "교양선택"
                "교필" -> "교양필수"
                "기필" -> "기초필수"
                else -> rawCompType
            }
            val cleanCredit = item.credit.coerceIn(1, 6)
            val cleanSchedule = PasswordSecurity.sanitizeInput(item.schedule).take(200)

            // Match existing course in the same semester if same code (when explicitly provided) or same name+dept
            val existingCourse = if (splitBase.isNotBlank()) {
                CoursesTable.select {
                    (CoursesTable.semester eq cleanSem) and (CoursesTable.code.lowerCase() eq splitBase.lowercase())
                }.firstOrNull()
            } else {
                CoursesTable.select {
                    (CoursesTable.semester eq cleanSem) and
                        (CoursesTable.name.lowerCase() eq cleanName.lowercase()) and
                        (CoursesTable.department eq finalDept)
                }.firstOrNull()
            }

            val cId = if (existingCourse != null) {
                existingCourse[CoursesTable.id].value
            } else {
                val newId = UUID.randomUUID()
                CoursesTable.insert {
                    it[id] = newId
                    it[name] = cleanName
                    it[code] = cleanCode
                    it[semester] = cleanSem
                    it[college] = finalCollege
                    it[department] = finalDept
                    it[major] = ""
                    it[completionType] = cleanCompType
                    it[credit] = cleanCredit
                }
                autoLinkCourseToPreviousSemesterInternal(newId)
                newId
            }

            val alreadyInSection = CourseProfessorsTable.select {
                (CourseProfessorsTable.courseId eq cId) and
                    (CourseProfessorsTable.professorId eq professorId) and
                    (CourseProfessorsTable.section eq cleanSection)
            }.count() > 0

            if (!alreadyInSection) {
                CourseProfessorsTable.insert {
                    it[courseId] = cId
                    it[CourseProfessorsTable.professorId] = professorId
                    it[section] = cleanSection
                    it[schedule] = cleanSchedule.ifBlank { "학사 시간표 참조" }
                }
            }
            createdOrAttached.add(cId)
        }
        return createdOrAttached.distinct()
    }

    suspend fun importCoursesFromExcel(
        fileBytes: ByteArray,
        overrideSemester: String? = null,
        autoLinkPrevSemester: Boolean = true,
        setAsCurrentSemester: Boolean = false
    ): ExcelCourseImportResultDto = DatabaseFactory.adminTransaction("importCoursesFromExcel") {
        val parsed = ExcelCourseImporter.parseXlsx(fileBytes)
        val fallbackSem = getCurrentSemesterInternal()
        val targetSemester = if (!overrideSemester.isNullOrBlank()) {
            SemesterUtils.parseOrNull(overrideSemester)
                ?: throw IllegalArgumentException("올바른 학기 형식을 입력해 주세요. (예: 2026-2, 2026-여름, 2026-겨울, 2027-1)")
        } else {
            parsed.detectedSemester ?: fallbackSem
        }

        var createdCourses = 0
        var updatedCourses = 0
        var createdSections = 0

        // Cache registered & placeholder professors by (name, canonicalDept)
        fun resolveOrCreateProfessorForImport(rawProfName: String, dept: String, college: String): UUID {
            val cleanProfName = PasswordSecurity.sanitizeInput(rawProfName).take(80).ifBlank { "미지정" }
            val effectiveDept = when {
                dept.isNotBlank() && dept != "공통" -> dept
                college.isNotBlank() && college != "공통" -> college
                else -> "공통"
            }
            val canonDept = DatabaseFactory.resolveCanonicalDepartmentName(effectiveDept)

            if (cleanProfName != "미지정") {
                val sameNameProfs = UsersTable.select {
                    (UsersTable.role eq UserRole.PROFESSOR.name) and (UsersTable.name eq cleanProfName)
                }.toList()

                val registeredMatches = sameNameProfs.filter { isRegisteredProfessorRow(it) }
                val exactDeptReg = registeredMatches.firstOrNull {
                    DatabaseFactory.resolveCanonicalDepartmentName(it[UsersTable.department].trim()) == canonDept
                }
                if (exactDeptReg != null) return exactDeptReg[UsersTable.id].value
                if (registeredMatches.size == 1 && sameNameProfs.size == 1) {
                    return registeredMatches.first()[UsersTable.id].value
                }

                val placeholderMatch = sameNameProfs.firstOrNull {
                    !isRegisteredProfessorRow(it) &&
                        DatabaseFactory.resolveCanonicalDepartmentName(it[UsersTable.department].trim()) == canonDept
                }
                if (placeholderMatch != null) return placeholderMatch[UsersTable.id].value
            } else {
                val unassigned = UsersTable.select {
                    (UsersTable.role eq UserRole.PROFESSOR.name) and
                        (UsersTable.isDemo eq true) and
                        UsersTable.passwordHash.isNull() and
                        (UsersTable.name eq "미지정")
                }.firstOrNull()
                if (unassigned != null) return unassigned[UsersTable.id].value
            }

            val newProfId = UUID.randomUUID()
            val displayTag = if (cleanProfName == "미지정") {
                "담당교수 미지정"
            } else if (effectiveDept != "공통") {
                "$effectiveDept $cleanProfName 교수"
            } else {
                "$cleanProfName 교수"
            }
            UsersTable.insert {
                it[id] = newProfId
                it[name] = cleanProfName
                it[role] = UserRole.PROFESSOR.name
                it[identifier] = "P_${(1000..9999).random()}"
                it[department] = effectiveDept
                it[UsersTable.displayTag] = displayTag
                it[isDemo] = true
            }
            return newProfId
        }

        // Group sections by baseCode (fallback to courseName if baseCode is blank)
        val grouped = parsed.sections.groupBy { row ->
            row.baseCode.lowercase().ifBlank { "name:${SemesterUtils.normalizeCourseNameForMatch(row.courseName)}" }
        }

        for ((_, secRows) in grouped) {
            val first = secRows.first()
            val rawCollege = first.college.ifBlank { "광운대학교" }
            val cleanCollege = DatabaseFactory.ensureCollegeExistsInternal(rawCollege)
            val rawDept = first.department.trim()
            val cleanDept = if (rawDept.isNotBlank() && rawDept != "공통") {
                val existingDept = DatabaseFactory.resolveDepartmentRow(rawDept)
                if (existingDept != null) {
                    existingDept[DepartmentsTable.name]
                } else {
                    DepartmentsTable.insert {
                        it[id] = UUID.randomUUID()
                        it[name] = rawDept
                        it[college] = cleanCollege
                    }
                    rawDept
                }
            } else {
                rawDept.ifBlank { "공통" }
            }

            val cleanCode = first.baseCode.ifBlank { "KW-${(1000..9999).random()}" }
            val existingCourse = CoursesTable.select {
                (CoursesTable.semester eq targetSemester) and
                    (CoursesTable.code.lowerCase() eq cleanCode.lowercase())
            }.firstOrNull()

            val cId = if (existingCourse != null) {
                val idVal = existingCourse[CoursesTable.id].value
                CoursesTable.update({ CoursesTable.id eq idVal }) {
                    it[name] = first.courseName
                    it[college] = cleanCollege
                    it[department] = cleanDept
                    it[major] = first.major
                    it[completionType] = first.completionType
                    it[credit] = first.credit
                }
                updatedCourses++
                idVal
            } else {
                val newId = UUID.randomUUID()
                CoursesTable.insert {
                    it[id] = newId
                    it[name] = first.courseName
                    it[code] = cleanCode
                    it[semester] = targetSemester
                    it[college] = cleanCollege
                    it[department] = cleanDept
                    it[major] = first.major
                    it[completionType] = first.completionType
                    it[credit] = first.credit
                }
                createdCourses++
                newId
            }

            for (sec in secRows) {
                val profUuid = resolveOrCreateProfessorForImport(sec.professorName, cleanDept, cleanCollege)
                val existingSec = CourseProfessorsTable.innerJoin(
                    UsersTable,
                    { CourseProfessorsTable.professorId },
                    { UsersTable.id }
                ).select {
                    (CourseProfessorsTable.courseId eq cId) and (CourseProfessorsTable.section eq sec.section)
                }.firstOrNull()

                if (existingSec == null) {
                    CourseProfessorsTable.insert {
                        it[courseId] = cId
                        it[professorId] = profUuid
                        it[section] = sec.section
                        it[schedule] = sec.schedule
                    }
                    createdSections++
                } else {
                    val cpId = existingSec[CourseProfessorsTable.id].value
                    val alreadyRealProf = isRegisteredProfessorRow(existingSec)
                    CourseProfessorsTable.update({ CourseProfessorsTable.id eq cpId }) {
                        if (!alreadyRealProf) {
                            it[professorId] = profUuid
                        }
                        it[schedule] = sec.schedule
                    }
                }
            }
        }

        val autoLinked = if (autoLinkPrevSemester) {
            autoLinkSemesterCoursesInternal(targetSemester)
        } else {
            0
        }

        if (setAsCurrentSemester) {
            setCurrentSemesterInternal(targetSemester)
        }

        ExcelCourseImportResultDto(
            semester = targetSemester,
            createdCourses = createdCourses,
            updatedCourses = updatedCourses,
            createdSections = createdSections,
            autoLinkedCourses = autoLinked,
            totalRowsRead = parsed.totalDataRows
        )
    }

    suspend fun createCourse(request: CreateCourseRequest): CourseDto =
        DatabaseFactory.adminTransaction("createCourse") {
        val cleanName = PasswordSecurity.sanitizeInput(request.name).take(100)
        if (cleanName.isBlank()) {
            throw IllegalArgumentException("과목명을 입력해 주세요.")
        }
        val (_, finalCollege, finalDept) = resolveCourseOrganizationInternal(
            request.scopeType,
            request.college,
            request.department
        )
        val rawCode = PasswordSecurity.sanitizeInput(request.code).take(50)
        val (splitBase, splitSec) = SemesterUtils.splitCourseCodeAndSection(rawCode)
        val cleanCode = splitBase.ifBlank { "KW-${(1000..9999).random()}" }
        val cleanSemester = SemesterUtils.normalizeSemester(
            PasswordSecurity.sanitizeInput(request.semester).take(20),
            getCurrentSemesterInternal()
        )
        val cleanSection = SemesterUtils.normalizeSectionLabel(
            request.section.takeIf { it.isNotBlank() } ?: splitSec ?: "01분반"
        )

        val newCourseId = UUID.randomUUID()
        CoursesTable.insert {
            it[id] = newCourseId
            it[name] = cleanName
            it[code] = cleanCode
            it[semester] = cleanSemester
            it[college] = finalCollege
            it[department] = finalDept
            it[major] = PasswordSecurity.sanitizeInput(request.major).take(100)
            it[completionType] = PasswordSecurity.sanitizeInput(request.completionType).take(50).ifBlank { "전공선택" }
            it[credit] = request.credit.coerceIn(1, 6)
        }

        val profId = request.professorId?.trim()?.takeIf { it.isNotBlank() }?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        if (profId != null && UsersTable.select { UsersTable.id eq profId }.count() > 0) {
            CourseProfessorsTable.insert {
                it[courseId] = newCourseId
                it[professorId] = profId
                it[section] = cleanSection
                it[schedule] = PasswordSecurity.sanitizeInput(request.schedule).take(200)
            }
        }

        val explicitPrevUuid = request.prevCourseId?.trim()?.takeIf { it.isNotBlank() }?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        if (explicitPrevUuid != null) {
            linkCoursesInternal(newCourseId, explicitPrevUuid)
        } else if (request.autoLinkPrevSemester) {
            autoLinkCourseToPreviousSemesterInternal(newCourseId)
        }

        getCourseByIdInternal(newCourseId)!!
    }

    suspend fun updateCourse(courseId: UUID, request: UpdateCourseRequest): CourseDto =
        DatabaseFactory.adminTransaction("updateCourse") {
        val existing = CoursesTable.select { CoursesTable.id eq courseId }.singleOrNull()
            ?: throw IllegalArgumentException("존재하지 않는 과목입니다.")
        val oldName = existing[CoursesTable.name]

        val nextName = request.name?.let { PasswordSecurity.sanitizeInput(it).take(100) } ?: existing[CoursesTable.name]
        if (nextName.isBlank()) {
            throw IllegalArgumentException("과목명을 입력해 주세요.")
        }

        val nextCode = request.code?.let {
            val sanitized = PasswordSecurity.sanitizeInput(it).take(50)
            SemesterUtils.splitCourseCodeAndSection(sanitized).first.ifBlank { sanitized }
        } ?: existing[CoursesTable.code]
        if (nextCode.isBlank()) {
            throw IllegalArgumentException("학수번호(코드)를 입력해 주세요.")
        }

        val nextSemester = request.semester?.let {
            val sanitized = PasswordSecurity.sanitizeInput(it).take(20)
            if (sanitized.isBlank()) existing[CoursesTable.semester]
            else SemesterUtils.normalizeSemester(sanitized, existing[CoursesTable.semester])
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
            PasswordSecurity.sanitizeInput(it).take(50).ifBlank { "전공선택" }
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

        if (!nextName.equals(oldName, ignoreCase = true)) {
            DatabaseFactory.removeCourseAliasNodeInternal(courseId, nextName)
            if (request.keepOldNameAsAlias) {
                DatabaseFactory.ensureCourseAliasInternal(courseId, oldName)
            }
        }
        if (request.aliases != null) {
            val cleanAliases = request.aliases
                .map { PasswordSecurity.sanitizeInput(it).take(100) }
                .filter { it.isNotBlank() && !it.equals(nextName, ignoreCase = true) }
                .distinctBy { it.lowercase() }
            DatabaseFactory.replaceCourseAliasesInternal(courseId, cleanAliases)
        }

        if (request.unlinkPrevCourse) {
            unlinkPrevCourseInternal(courseId)
        } else if (!request.prevCourseId.isNullOrBlank()) {
            val prevUuid = runCatching { UUID.fromString(request.prevCourseId.trim()) }.getOrNull()
                ?: throw IllegalArgumentException("유효하지 않은 이전 학기 강의 ID입니다.")
            linkCoursesInternal(courseId, prevUuid)
        }

        getCourseByIdInternal(courseId)!!
    }

    suspend fun addCourseAlias(courseId: UUID, rawAlias: String): CourseDto =
        DatabaseFactory.adminTransaction("addCourseAlias") {
            val courseRow = CoursesTable.select { CoursesTable.id eq courseId }.singleOrNull()
                ?: throw IllegalArgumentException("존재하지 않는 과목입니다.")
            val cleanAlias = PasswordSecurity.sanitizeInput(rawAlias).take(100)
            if (cleanAlias.isBlank()) {
                throw IllegalArgumentException("등록할 과목 별칭을 입력해 주세요.")
            }
            if (cleanAlias.equals(courseRow[CoursesTable.name], ignoreCase = true)) {
                throw IllegalArgumentException("현재 과목명과 동일한 이름은 별칭으로 등록할 수 없습니다.")
            }
            val exists = CourseAliasesTable.select {
                (CourseAliasesTable.courseId eq courseId) and
                    (CourseAliasesTable.aliasName.lowerCase() eq cleanAlias.lowercase())
            }.count() > 0L
            if (exists) {
                throw IllegalArgumentException("이미 등록된 과목 별칭입니다: $cleanAlias")
            }
            val currentCount = CourseAliasesTable.select { CourseAliasesTable.courseId eq courseId }.count()
            if (currentCount >= 50L) {
                throw IllegalArgumentException("과목 별칭은 최대 50개까지 등록할 수 있습니다.")
            }
            DatabaseFactory.appendCourseAliasNodeInternal(courseId, cleanAlias)
            getCourseByIdInternal(courseId)!!
        }

    suspend fun renameCourseAlias(courseId: UUID, oldAlias: String, newAlias: String): CourseDto =
        DatabaseFactory.adminTransaction("renameCourseAlias") {
            val courseRow = CoursesTable.select { CoursesTable.id eq courseId }.singleOrNull()
                ?: throw IllegalArgumentException("존재하지 않는 과목입니다.")
            val cleanOld = PasswordSecurity.sanitizeInput(oldAlias).take(100)
            val cleanNew = PasswordSecurity.sanitizeInput(newAlias).take(100)
            if (cleanOld.isBlank() || cleanNew.isBlank()) {
                throw IllegalArgumentException("기존 별칭과 새 별칭을 모두 입력해 주세요.")
            }
            if (cleanNew.equals(courseRow[CoursesTable.name], ignoreCase = true)) {
                throw IllegalArgumentException("현재 과목명과 동일한 이름은 별칭으로 변경할 수 없습니다.")
            }
            val existingRow = CourseAliasesTable.select {
                (CourseAliasesTable.courseId eq courseId) and
                    (CourseAliasesTable.aliasName.lowerCase() eq cleanOld.lowercase())
            }.firstOrNull() ?: throw IllegalArgumentException("변경할 기존 별칭을 찾을 수 없습니다: $cleanOld")

            val dup = CourseAliasesTable.select {
                (CourseAliasesTable.courseId eq courseId) and
                    (CourseAliasesTable.aliasName.lowerCase() eq cleanNew.lowercase())
            }.firstOrNull()
            if (dup != null && dup[CourseAliasesTable.id] != existingRow[CourseAliasesTable.id]) {
                throw IllegalArgumentException("이미 등록된 과목 별칭입니다: $cleanNew")
            }
            CourseAliasesTable.update({ CourseAliasesTable.id eq existingRow[CourseAliasesTable.id] }) {
                it[aliasName] = cleanNew
            }
            getCourseByIdInternal(courseId)!!
        }

    suspend fun removeCourseAlias(courseId: UUID, rawAlias: String): CourseDto =
        DatabaseFactory.adminTransaction("removeCourseAlias") {
            CoursesTable.select { CoursesTable.id eq courseId }.singleOrNull()
                ?: throw IllegalArgumentException("존재하지 않는 과목입니다.")
            val cleanAlias = PasswordSecurity.sanitizeInput(rawAlias).take(100)
            if (cleanAlias.isBlank()) {
                throw IllegalArgumentException("삭제할 별칭을 지정해 주세요.")
            }
            if (!DatabaseFactory.removeCourseAliasNodeInternal(courseId, cleanAlias)) {
                throw IllegalArgumentException("해당 과목에 등록된 별칭을 찾을 수 없습니다: $cleanAlias")
            }
            getCourseByIdInternal(courseId)!!
        }

    suspend fun deleteCourse(courseId: UUID, deletedBy: UUID? = null): Pair<Int, List<String>> =
        DatabaseFactory.adminTransaction("deleteCourse") {
        val existing = CoursesTable.select { CoursesTable.id eq courseId }.singleOrNull()
            ?: throw IllegalArgumentException("존재하지 않는 과목입니다.")

        // Archive full course + children snapshot into 30-day restorable trash bin before removing live rows
        AdminTrashService.archiveCourseInternal(courseId, deletedBy)

        // Splice doubly-linked list neighbors together before deleting
        val prevId = existing[CoursesTable.prevCourseId]
        val nextId = existing[CoursesTable.nextCourseId]
        if (prevId != null) {
            CoursesTable.update({ CoursesTable.id eq prevId }) {
                it[nextCourseId] = nextId
            }
        }
        if (nextId != null) {
            CoursesTable.update({ CoursesTable.id eq nextId }) {
                it[prevCourseId] = prevId
            }
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
        CourseAliasesTable.deleteWhere { CourseAliasesTable.courseId eq courseId }
        CoursesTable.deleteWhere { CoursesTable.id eq courseId }

        // Physical attachment files are preserved until permanently purged from trash (30-day retention)
        questionIds.size to emptyList()
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
            .sortedWith(
                compareByDescending<ResultRow> { isRegisteredProfessorRow(it) }
                    .thenBy { it[CourseProfessorsTable.section] }
            )
            .map { pRow ->
                ProfessorSimpleDto(
                    id = pRow[UsersTable.id].value.toString(),
                    name = pRow[UsersTable.name],
                    department = pRow[UsersTable.department],
                    section = pRow[CourseProfessorsTable.section],
                    schedule = pRow[CourseProfessorsTable.schedule],
                    isRegistered = isRegisteredProfessorRow(pRow),
                    courseProfessorId = pRow[CourseProfessorsTable.id].value.toString()
                )
            }

    private fun normalizeCourseSelections(
        rawCourseIds: List<String>,
        rawSelections: List<CourseSelectionItemDto>
    ): LinkedHashMap<UUID, MutableList<UUID>> {
        val result = LinkedHashMap<UUID, MutableList<UUID>>()

        fun parseCourseUuid(raw: String): UUID? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val cand = runCatching { UUID.fromString(trimmed) }.getOrNull()
                ?: com.gonghak.routes.DEMO_COURSE_ID_MAP[trimmed.lowercase()]
                ?: return null
            return if (CoursesTable.select { CoursesTable.id eq cand }.count() > 0) cand else null
        }

        for (sel in rawSelections) {
            val cId = parseCourseUuid(sel.courseId) ?: continue
            val list = result.getOrPut(cId) { mutableListOf() }
            val cpUuid = sel.courseProfessorId?.trim()?.takeIf { it.isNotBlank() }?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
            }
            if (cpUuid != null && cpUuid !in list) {
                val validOnCourse = CourseProfessorsTable.select {
                    (CourseProfessorsTable.id eq cpUuid) and (CourseProfessorsTable.courseId eq cId)
                }.count() > 0
                if (validOnCourse) list.add(cpUuid)
            }
        }

        for (rawId in rawCourseIds) {
            val cId = parseCourseUuid(rawId) ?: continue
            result.getOrPut(cId) { mutableListOf() }
        }
        return result
    }

    private fun resolveStudentSectionEnrollment(
        courseId: UUID,
        requestedCourseProfessorId: UUID?
    ): Pair<UUID?, UUID?> {
        if (requestedCourseProfessorId != null) {
            val exact = CourseProfessorsTable.select {
                (CourseProfessorsTable.id eq requestedCourseProfessorId) and
                    (CourseProfessorsTable.courseId eq courseId)
            }.singleOrNull()
            if (exact != null) {
                return exact[CourseProfessorsTable.id].value to exact[CourseProfessorsTable.professorId]
            }
        }
        val rows = CourseProfessorsTable.innerJoin(UsersTable, { CourseProfessorsTable.professorId }, { UsersTable.id })
            .select { CourseProfessorsTable.courseId eq courseId }
            .sortedBy { it[CourseProfessorsTable.section] }
            .toList()
        val chosen = rows.firstOrNull { isRegisteredProfessorRow(it) } ?: rows.firstOrNull()
        return if (chosen != null) {
            chosen[CourseProfessorsTable.id].value to chosen[UsersTable.id].value
        } else {
            null to null
        }
    }

    private data class LinkedCourseMeta(
        val prevId: String? = null,
        val prevName: String? = null,
        val prevSemester: String? = null,
        val nextId: String? = null,
        val nextName: String? = null,
        val nextSemester: String? = null
    )

    private fun resolveLinkedCourseMeta(courseRow: ResultRow): LinkedCourseMeta {
        val prevUuid = courseRow[CoursesTable.prevCourseId]
        val nextUuid = courseRow[CoursesTable.nextCourseId]
        val prevRow = prevUuid?.let { idVal -> CoursesTable.select { CoursesTable.id eq idVal }.singleOrNull() }
        val nextRow = nextUuid?.let { idVal -> CoursesTable.select { CoursesTable.id eq idVal }.singleOrNull() }
        return LinkedCourseMeta(
            prevId = prevRow?.get(CoursesTable.id)?.value?.toString(),
            prevName = prevRow?.get(CoursesTable.name),
            prevSemester = prevRow?.get(CoursesTable.semester),
            nextId = nextRow?.get(CoursesTable.id)?.value?.toString(),
            nextName = nextRow?.get(CoursesTable.name),
            nextSemester = nextRow?.get(CoursesTable.semester)
        )
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
                val storedCpId = row[EnrollmentsTable.courseProfessorId]
                val assignedProfId = row[EnrollmentsTable.assignedProfessorId]
                val profList = loadCourseProfessorDtos(cId)
                val linkedMeta = resolveLinkedCourseMeta(row)

                val matchedBySection = storedCpId?.let { cpTarget ->
                    profList.firstOrNull { it.courseProfessorId == cpTarget.toString() }
                }
                val matchedByProf = assignedProfId?.let { targetId ->
                    profList.firstOrNull { it.id == targetId.toString() }
                }
                // Preserve exact section (`courseProfessorId`) when explicitly stored so homonyms / multi-section enrollments never cross over!
                val effectiveProf = when {
                    matchedBySection != null -> matchedBySection
                    matchedByProf != null && matchedByProf.isRegistered -> matchedByProf
                    else -> profList.firstOrNull { it.isRegistered } ?: matchedByProf ?: profList.firstOrNull()
                }
                val effectiveProfId = effectiveProf?.id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
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
                    assignedProfessorName = effectiveProf?.name,
                    assignedProfessorRegistered = effectiveProf?.isRegistered ?: true,
                    assignedCourseProfessorId = effectiveProf?.courseProfessorId,
                    assignedSection = effectiveProf?.section,
                    prevCourseId = linkedMeta.prevId,
                    prevCourseName = linkedMeta.prevName,
                    prevCourseSemester = linkedMeta.prevSemester,
                    nextCourseId = linkedMeta.nextId,
                    nextCourseName = linkedMeta.nextName,
                    nextCourseSemester = linkedMeta.nextSemester
                )
            }
        } else {
            val teachingRows = CourseProfessorsTable.innerJoin(CoursesTable, { CourseProfessorsTable.courseId }, { CoursesTable.id })
                .select { CourseProfessorsTable.professorId eq userId }
                .orderBy(CourseProfessorsTable.section to SortOrder.ASC)
                .toList()

            val groupedByCourse = teachingRows.groupBy { it[CourseProfessorsTable.courseId] }
            return groupedByCourse.map { (cId, rowsForCourse) ->
                val row = rowsForCourse.first()
                val profList = loadCourseProfessorDtos(cId)
                val myCategories = getProfessorCategoriesInternal(userId, cId).filter { it.isEnabled }.map { it.category }
                val myCpIds = rowsForCourse.map { it[CourseProfessorsTable.id].value.toString() }
                val mySecs = rowsForCourse.map { it[CourseProfessorsTable.section] }.distinct()
                val linkedMeta = resolveLinkedCourseMeta(row)

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
                    assignedProfessorRegistered = isRegisteredProfessorRow(user),
                    assignedCourseProfessorId = myCpIds.firstOrNull(),
                    assignedSection = mySecs.joinToString(", ").ifBlank { null },
                    myCourseProfessorIds = myCpIds,
                    mySections = mySecs,
                    prevCourseId = linkedMeta.prevId,
                    prevCourseName = linkedMeta.prevName,
                    prevCourseSemester = linkedMeta.prevSemester,
                    nextCourseId = linkedMeta.nextId,
                    nextCourseName = linkedMeta.nextName,
                    nextCourseSemester = linkedMeta.nextSemester
                )
            }
        }
    }

    private fun claimPlaceholderSectionInternal(
        courseId: UUID,
        cpId: UUID,
        oldPlaceholderProfId: UUID,
        newProfessorId: UUID
    ) {
        CourseProfessorsTable.update({ CourseProfessorsTable.id eq cpId }) {
            it[CourseProfessorsTable.professorId] = newProfessorId
        }
        // Reassign enrollments & questions scoped strictly to THIS section (`courseProfessorId == cpId`),
        // or legacy rows on THIS course with null `courseProfessorId` pointing to `oldPlaceholderProfId`.
        EnrollmentsTable.update({
            (EnrollmentsTable.courseId eq courseId) and (
                (EnrollmentsTable.courseProfessorId eq cpId) or
                    (EnrollmentsTable.courseProfessorId.isNull() and (EnrollmentsTable.assignedProfessorId eq oldPlaceholderProfId))
                )
        }) {
            it[assignedProfessorId] = newProfessorId
            it[courseProfessorId] = cpId
        }
        QuestionsTable.update({
            (QuestionsTable.courseId eq courseId) and (
                (QuestionsTable.courseProfessorId eq cpId) or
                    (QuestionsTable.courseProfessorId.isNull() and (QuestionsTable.assignedProfessorId eq oldPlaceholderProfId))
                )
        }) {
            it[assignedProfessorId] = newProfessorId
            it[courseProfessorId] = cpId
        }
    }

    private fun assignProfessorToCoursesInternal(
        professorId: UUID,
        courseUuids: List<UUID>,
        explicitSelections: Map<UUID, List<UUID>> = emptyMap()
    ) {
        val profRow = UsersTable.select { UsersTable.id eq professorId }.singleOrNull()
        val profName = profRow?.get(UsersTable.name)?.trim().orEmpty()
        val profDept = profRow?.get(UsersTable.department)?.trim().orEmpty()
        val profCanonDept = DatabaseFactory.resolveCanonicalDepartmentName(profDept)

        courseUuids.forEach { cId ->
            val requestedCpIds = explicitSelections[cId].orEmpty()

            if (requestedCpIds.isNotEmpty()) {
                // Remove sections of this course taught by this professor that were unchecked
                CourseProfessorsTable.deleteWhere {
                    (CourseProfessorsTable.courseId eq cId) and
                        (CourseProfessorsTable.professorId eq professorId) and
                        (CourseProfessorsTable.id notInList requestedCpIds)
                }

                for (cpUuid in requestedCpIds) {
                    val cpRow = CourseProfessorsTable.innerJoin(UsersTable, { CourseProfessorsTable.professorId }, { UsersTable.id })
                        .select { (CourseProfessorsTable.id eq cpUuid) and (CourseProfessorsTable.courseId eq cId) }
                        .singleOrNull() ?: continue
                    val currentOwner = cpRow[CourseProfessorsTable.professorId]
                    if (currentOwner == professorId) continue
                    // Allow taking over placeholder sections only if placeholder is '미지정' or matches professor's own name
                    if (!isRegisteredProfessorRow(cpRow)) {
                        val placeholderName = cpRow[UsersTable.name].trim()
                        if (placeholderName == "미지정" || (profName.isNotBlank() && placeholderName == profName)) {
                            claimPlaceholderSectionInternal(cId, cpUuid, currentOwner, professorId)
                        }
                    }
                }
            }

            val alreadyAssigned = CourseProfessorsTable.select {
                (CourseProfessorsTable.courseId eq cId) and (CourseProfessorsTable.professorId eq professorId)
            }.count() > 0

            if (!alreadyAssigned) {
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

                // Tie-break homonyms by department; if all matching placeholders belong to the same department (e.g. prof teaches 01분반 & 02분반), claim all of their sections!
                val exactDeptPlaceholders = matchingPlaceholders.filter {
                    DatabaseFactory.resolveCanonicalDepartmentName(it[UsersTable.department].trim()) == profCanonDept
                }
                val targetsToClaim = when {
                    exactDeptPlaceholders.isNotEmpty() -> exactDeptPlaceholders
                    matchingPlaceholders.isNotEmpty() -> listOf(matchingPlaceholders.first())
                    else -> emptyList()
                }

                if (targetsToClaim.isNotEmpty()) {
                    for (ph in targetsToClaim) {
                        claimPlaceholderSectionInternal(
                            courseId = cId,
                            cpId = ph[CourseProfessorsTable.id].value,
                            oldPlaceholderProfId = ph[CourseProfessorsTable.professorId],
                            newProfessorId = professorId
                        )
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

            val firstOwnedSection = CourseProfessorsTable.select {
                (CourseProfessorsTable.courseId eq cId) and (CourseProfessorsTable.professorId eq professorId)
            }.firstOrNull()
            val validProfIds = CourseProfessorsTable.select { CourseProfessorsTable.courseId eq cId }
                .map { it[CourseProfessorsTable.professorId] }
            EnrollmentsTable.update({
                (EnrollmentsTable.courseId eq cId) and
                    (EnrollmentsTable.assignedProfessorId.isNull() or (EnrollmentsTable.assignedProfessorId notInList validProfIds))
            }) {
                it[assignedProfessorId] = professorId
                if (firstOwnedSection != null) {
                    it[courseProfessorId] = firstOwnedSection[CourseProfessorsTable.id].value
                }
            }
        }
    }

    suspend fun updateUserCourses(
        userId: UUID,
        rawCourseIds: List<String>,
        rawSelections: List<CourseSelectionItemDto> = emptyList()
    ): List<CourseDto> = dbQuery {
        val user = UsersTable.select { UsersTable.id eq userId }.singleOrNull()
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다.")

        val explicitSelections = normalizeCourseSelections(rawCourseIds, rawSelections)
        val validCourseUuids = explicitSelections.keys.toList()

        if (validCourseUuids.isEmpty()) {
            throw IllegalArgumentException("최소 1개 이상의 과목을 선택해 주세요.")
        }

        val role = runCatching { UserRole.valueOf(user[UsersTable.role]) }.getOrDefault(UserRole.STUDENT)
        if (role == UserRole.PROFESSOR) {
            CourseProfessorsTable.deleteWhere {
                (CourseProfessorsTable.professorId eq userId) and
                    (CourseProfessorsTable.courseId notInList validCourseUuids)
            }
            assignProfessorToCoursesInternal(userId, validCourseUuids, explicitSelections)
        } else {
            EnrollmentsTable.deleteWhere {
                (EnrollmentsTable.studentId eq userId) and
                    (EnrollmentsTable.courseId notInList validCourseUuids)
            }
            val existingEnrollments = EnrollmentsTable.select { EnrollmentsTable.studentId eq userId }
                .associateBy { it[EnrollmentsTable.courseId] }

            validCourseUuids.forEach { cId ->
                val requestedCpId = explicitSelections[cId]?.firstOrNull()
                val existingRow = existingEnrollments[cId]
                if (existingRow == null) {
                    val (resolvedCpId, resolvedProfId) = resolveStudentSectionEnrollment(cId, requestedCpId)
                    EnrollmentsTable.insert {
                        it[courseId] = cId
                        it[studentId] = userId
                        it[assignedProfessorId] = resolvedProfId
                        it[courseProfessorId] = resolvedCpId
                    }
                } else if (requestedCpId != null && existingRow[EnrollmentsTable.courseProfessorId] != requestedCpId) {
                    val (resolvedCpId, resolvedProfId) = resolveStudentSectionEnrollment(cId, requestedCpId)
                    EnrollmentsTable.update({ EnrollmentsTable.id eq existingRow[EnrollmentsTable.id] }) {
                        it[assignedProfessorId] = resolvedProfId
                        it[courseProfessorId] = resolvedCpId
                    }
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
        semester: String? = null,
        page: Int = 1,
        pageSize: Int = 50
    ): CourseListResponse = dbQuery {
        val conditions = mutableListOf<Op<Boolean>>()

        if (!semester.isNullOrBlank() && !semester.equals("ALL", ignoreCase = true)) {
            val normSem = SemesterUtils.normalizeSemester(semester)
            conditions.add(CoursesTable.semester eq normSem)
        }
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

                val matchedAliasCourseIds = if (q.isNotEmpty()) {
                    CourseAliasesTable.select { CourseAliasesTable.aliasName.lowerCase() like "%$q%" }
                        .map { it[CourseAliasesTable.courseId] }
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
                val courseAliasCondition: Op<Boolean>? = if (matchedAliasCourseIds.isNotEmpty()) {
                    CoursesTable.id inList matchedAliasCourseIds
                } else {
                    null
                }

                listOfNotNull(textCondition, profCondition, courseAliasCondition)
                    .reduceOrNull { acc, op -> acc or op }
                    ?.let { conditions.add(it) }
            }
        }

        val finalCondition = if (conditions.isNotEmpty()) conditions.reduce { acc, op -> acc and op } else null
        val total = if (finalCondition != null) CoursesTable.select(finalCondition).count() else CoursesTable.selectAll().count()

        val safePage = page.coerceIn(1, 10_000)
        val safePageSize = pageSize.coerceIn(1, 5000)
        val offset = ((safePage - 1).toLong() * safePageSize.toLong())
        val queryObj = if (finalCondition != null) CoursesTable.select(finalCondition) else CoursesTable.selectAll()
        val rows = queryObj
            .orderBy(CoursesTable.name to SortOrder.ASC)
            .limit(safePageSize, offset)
            .toList()

        val pageCourseIds = rows.map { it[CoursesTable.id].value }
        val profsByCourseId: Map<UUID, List<ProfessorSimpleDto>> = if (pageCourseIds.isNotEmpty()) {
            CourseProfessorsTable.innerJoin(UsersTable, { CourseProfessorsTable.professorId }, { UsersTable.id })
                .select { CourseProfessorsTable.courseId inList pageCourseIds }
                .groupBy { it[CourseProfessorsTable.courseId] }
                .mapValues { (_, pRows) ->
                    pRows.sortedWith(
                        compareByDescending<ResultRow> { isRegisteredProfessorRow(it) }
                            .thenBy { it[CourseProfessorsTable.section] }
                    ).map { pRow ->
                        ProfessorSimpleDto(
                            id = pRow[UsersTable.id].value.toString(),
                            name = pRow[UsersTable.name],
                            department = pRow[UsersTable.department],
                            section = pRow[CourseProfessorsTable.section],
                            schedule = pRow[CourseProfessorsTable.schedule],
                            isRegistered = isRegisteredProfessorRow(pRow),
                            courseProfessorId = pRow[CourseProfessorsTable.id].value.toString()
                        )
                    }
                }
        } else {
            emptyMap()
        }

        val linkedNeighborIds = rows.flatMap { listOfNotNull(it[CoursesTable.prevCourseId], it[CoursesTable.nextCourseId]) }.distinct()
        val neighborInfoMap: Map<UUID, Pair<String, String>> = if (linkedNeighborIds.isNotEmpty()) {
            CoursesTable.select { CoursesTable.id inList linkedNeighborIds }
                .associate { it[CoursesTable.id].value to (it[CoursesTable.name] to it[CoursesTable.semester]) }
        } else {
            emptyMap()
        }
        val aliasesByCourseId = DatabaseFactory.loadCourseAliasesLinkedMap(pageCourseIds)

        val defaultCategories = listOf(QuestionCategory.GRADE, QuestionCategory.ASSIGNMENT, QuestionCategory.LECTURE)
        val courseDtos = rows.map { row ->
            val cId = row[CoursesTable.id].value
            val profList = profsByCourseId[cId].orEmpty()
            val primaryProf = profList.firstOrNull()
            val prevId = row[CoursesTable.prevCourseId]
            val nextId = row[CoursesTable.nextCourseId]
            val prevMeta = prevId?.let { neighborInfoMap[it] }
            val nextMeta = nextId?.let { neighborInfoMap[it] }
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
                availableCategories = defaultCategories,
                assignedProfessorId = primaryProf?.id,
                assignedProfessorName = primaryProf?.name,
                assignedProfessorRegistered = primaryProf?.isRegistered ?: true,
                assignedCourseProfessorId = primaryProf?.courseProfessorId,
                assignedSection = primaryProf?.section,
                prevCourseId = prevId?.toString(),
                prevCourseName = prevMeta?.first,
                prevCourseSemester = prevMeta?.second,
                nextCourseId = nextId?.toString(),
                nextCourseName = nextMeta?.first,
                nextCourseSemester = nextMeta?.second,
                aliases = aliasesByCourseId[cId].orEmpty()
            )
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
        val linkedMeta = resolveLinkedCourseMeta(row)

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
            assignedProfessorRegistered = primaryProf?.isRegistered ?: true,
            assignedCourseProfessorId = primaryProf?.courseProfessorId,
            assignedSection = primaryProf?.section,
            prevCourseId = linkedMeta.prevId,
            prevCourseName = linkedMeta.prevName,
            prevCourseSemester = linkedMeta.prevSemester,
            nextCourseId = linkedMeta.nextId,
            nextCourseName = linkedMeta.nextName,
            nextCourseSemester = linkedMeta.nextSemester,
            aliases = DatabaseFactory.traverseCourseAliasesInternal(courseId)
        )
    }

    suspend fun getCoursesSummary(): CourseSummaryDto = dbQuery {
        val currentSem = getCurrentSemesterInternal()
        val availableSems = getAvailableSemestersInternal(currentSem)
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
            title = SemesterUtils.formatTimetableTitle(currentSem),
            semester = currentSem,
            currentSemester = currentSem,
            availableSemesters = availableSems,
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
