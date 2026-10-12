package com.gonghak.service

import com.gonghak.config.DatabaseFactory.dbQuery
import com.gonghak.dto.*
import com.gonghak.models.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import java.util.UUID

class UserService {

    suspend fun getAllDemoUsers(): List<UserDto> = dbQuery {
        UsersTable.select { UsersTable.isDemo eq true }
            .orderBy(UsersTable.role to SortOrder.ASC, UsersTable.name to SortOrder.ASC)
            .map {
                UserDto(
                    id = it[UsersTable.id].value.toString(),
                    name = it[UsersTable.name],
                    role = UserRole.valueOf(it[UsersTable.role]),
                    identifier = it[UsersTable.identifier],
                    department = it[UsersTable.department],
                    displayTag = it[UsersTable.displayTag],
                    isDemo = it[UsersTable.isDemo]
                )
            }
    }

    suspend fun getUserById(userId: UUID): UserDto? = dbQuery {
        UsersTable.select { UsersTable.id eq userId }
            .singleOrNull()
            ?.let {
                UserDto(
                    id = it[UsersTable.id].value.toString(),
                    name = it[UsersTable.name],
                    role = UserRole.valueOf(it[UsersTable.role]),
                    identifier = it[UsersTable.identifier],
                    department = it[UsersTable.department],
                    displayTag = it[UsersTable.displayTag],
                    isDemo = it[UsersTable.isDemo]
                )
            }
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
            UserSettingsTable.insert {
                it[UserSettingsTable.userId] = userId
                it[themeMode] = "system"
                it[fontSize] = "medium"
            }
            UserSettingDto(
                userId = userId.toString(),
                themeMode = "system",
                fontSize = "medium"
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
        } else {
            UserSettingsTable.insert {
                it[UserSettingsTable.userId] = userId
                it[themeMode] = request.themeMode ?: "system"
                it[fontSize] = request.fontSize ?: "medium"
            }
        }
        getUserSettingInternal(userId)
    }

    suspend fun getCoursesForUser(userId: UUID): List<CourseDto> = dbQuery {
        val user = UsersTable.select { UsersTable.id eq userId }.singleOrNull()
            ?: return@dbQuery emptyList()

        val role = UserRole.valueOf(user[UsersTable.role])

        if (role == UserRole.STUDENT) {
            // Get enrolled courses
            val enrollments = EnrollmentsTable.innerJoin(CoursesTable, { EnrollmentsTable.courseId }, { CoursesTable.id })
                .select { EnrollmentsTable.studentId eq userId }
                .toList()

            enrollments.map { row ->
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

                // Enabled categories by assigned professor
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
            // Professor: get teaching courses
            val teachingRows = CourseProfessorsTable.innerJoin(CoursesTable, { CourseProfessorsTable.courseId }, { CoursesTable.id })
                .select { CourseProfessorsTable.professorId eq userId }
                .toList()

            teachingRows.map { row ->
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
            val cId = row[CoursesTable.id].value
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
                availableCategories = listOf(QuestionCategory.GRADE, QuestionCategory.ASSIGNMENT, QuestionCategory.LECTURE)
            )
        }

        CourseListResponse(
            total = total,
            page = page,
            pageSize = pageSize,
            courses = courseDtos
        )
    }

    suspend fun getCourseById(courseId: UUID): CourseDto? = dbQuery {
        val row = CoursesTable.select { CoursesTable.id eq courseId }.singleOrNull() ?: return@dbQuery null
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

        CourseDto(
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

        CourseSummaryDto(
            title = "2026학년도 2학기 개설과목 및 강의시간표",
            semester = "2026-2",
            totalCourses = totalCourses,
            totalSections = totalSections,
            totalProfessors = totalProfessors,
            colleges = colleges
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
