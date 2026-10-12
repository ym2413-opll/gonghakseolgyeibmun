package com.gonghak.service

import com.gonghak.config.DatabaseFactory.dbQuery
import com.gonghak.dto.*
import com.gonghak.models.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import java.util.UUID

class QuestionService(
    private val similarityService: SimilarityService,
    private val notificationService: NotificationService,
    private val aiRefineService: AiRefineService
) {

    suspend fun createQuestion(
        currentUserId: UUID,
        request: CreateQuestionRequest
    ): QuestionDetailDto = dbQuery {
        val courseUUID = com.gonghak.routes.parseCourseId(request.courseId)

        // 1. Validate Category Rules
        if (request.category == QuestionCategory.GRADE && request.isAnonymous) {
            throw IllegalArgumentException("성적 카테고리 질문은 익명으로 작성할 수 없습니다.")
        }

        // 2. Identify student and assigned professor
        val enrollment = EnrollmentsTable.select {
            (EnrollmentsTable.studentId eq currentUserId) and (EnrollmentsTable.courseId eq courseUUID)
        }.singleOrNull()

        val assignedProfId = enrollment?.get(EnrollmentsTable.assignedProfessorId) ?: run {
            CourseProfessorsTable.select { CourseProfessorsTable.courseId eq courseUUID }
                .firstOrNull()?.get(CourseProfessorsTable.professorId)
                ?: UUID.fromString("22222222-2222-2222-2222-222222222201")
        }

        // 3. Validate if professor has enabled this category
        val categorySetting = ProfessorCategorySettingsTable.select {
            (ProfessorCategorySettingsTable.professorId eq assignedProfId) and
                    (ProfessorCategorySettingsTable.courseId eq courseUUID) and
                    (ProfessorCategorySettingsTable.category eq request.category.name)
        }.singleOrNull()

        if (categorySetting != null && !categorySetting[ProfessorCategorySettingsTable.isEnabled]) {
            throw IllegalStateException("담당 교수님에 의해 '${request.category}' 카테고리 질문 접수가 비활성화되어 있습니다.")
        }

        val course = CoursesTable.select { CoursesTable.id eq courseUUID }.single()
        val courseName = course[CoursesTable.name]

        // 4. Calculate refined content if not provided or refine using AI service
        val effectiveRaw = request.effectiveRawContent
        val finalRefinedContent = if (!request.refinedContent.isNullOrBlank()) {
            request.refinedContent!!
        } else if (!request.content.isNullOrBlank()) {
            request.content!!
        } else {
            effectiveRaw
        }

        // 5. Compute Word2Vec vector for question
        val vector = similarityService.computeVector("${request.title} $finalRefinedContent")
        val vectorJson = similarityService.serializeVector(vector)

        val qId = UUID.randomUUID()
        val now = Instant.now()

        QuestionsTable.insert {
            it[id] = qId
            it[courseId] = courseUUID
            it[studentId] = currentUserId
            it[assignedProfessorId] = assignedProfId
            it[category] = request.category.name
            it[title] = request.title
            it[rawContent] = effectiveRaw
            it[refinedContent] = finalRefinedContent
            it[isAnonymous] = request.isAnonymous
            it[vectorData] = vectorJson
            it[status] = QuestionStatus.OPEN.name
            it[createdAt] = now
        }

        // 6. Trigger notifications
        notificationService.notifyNewQuestion(
            assignedProfessorId = assignedProfId,
            courseId = courseUUID,
            courseName = courseName,
            questionId = qId,
            questionTitle = request.title,
            category = request.category
        )

        // Return details
        getQuestionDetailInternal(qId, currentUserId)
    }

    suspend fun getQuestions(
        currentUserId: UUID,
        courseIdFilter: UUID? = null,
        categoryFilter: QuestionCategory? = null
    ): List<QuestionListItemDto> = dbQuery {
        val join = QuestionsTable
            .innerJoin(CoursesTable, { QuestionsTable.courseId }, { CoursesTable.id })
            .innerJoin(UsersTable, { QuestionsTable.studentId }, { UsersTable.id })

        val query = join.selectAll()

        courseIdFilter?.let { query.andWhere { QuestionsTable.courseId eq it } }
        categoryFilter?.let { query.andWhere { QuestionsTable.category eq it.name } }

        query.orderBy(QuestionsTable.createdAt to SortOrder.DESC)

        val profUsers = UsersTable.select { UsersTable.role eq UserRole.PROFESSOR.name }
            .associate { it[UsersTable.id].value to it[UsersTable.name] }

        query.map { row ->
            val qId = row[QuestionsTable.id].value
            val authorId = row[QuestionsTable.studentId]
            val isMyQuestion = (authorId == currentUserId)
            val isAnonymous = row[QuestionsTable.isAnonymous]
            val courseName = row[CoursesTable.name]
            val authorDisplayTag = row[UsersTable.displayTag]
            val studentName = row[UsersTable.name]

            val authorDisplayName = when {
                isMyQuestion -> "$authorDisplayTag (나)"
                isAnonymous -> "$courseName 수강생"
                else -> authorDisplayTag
            }

            val assignedProfId = row[QuestionsTable.assignedProfessorId]
            val rawAnswers = AnswersTable.select { AnswersTable.questionId eq qId }
                .orderBy(AnswersTable.createdAt to SortOrder.ASC)
                .toList()
            val hasAnswers = rawAnswers.isNotEmpty()
            val isAnsweredByAssignedProfessor = rawAnswers.any { it[AnswersTable.professorId] == assignedProfId }

            val rawContent = row[QuestionsTable.rawContent]
            val refinedContent = row[QuestionsTable.refinedContent]
            val displayContent = refinedContent.ifBlank { rawContent }

            val answerDtos = rawAnswers.map { aRow ->
                val aProfId = aRow[AnswersTable.professorId]
                val aProfName = profUsers[aProfId] ?: "교수"
                val isAssigned = aRow[AnswersTable.isAssignedProfessor]
                AnswerDto(
                    id = aRow[AnswersTable.id].value.toString(),
                    questionId = qId.toString(),
                    professorId = aProfId.toString(),
                    professorName = aProfName,
                    professorDepartment = "",
                    authorId = aProfId.toString(),
                    authorName = aProfName,
                    authorTitle = if (isAssigned) "담당교수" else "같은 과목 교수 (공동 답변)",
                    isPrimaryProf = isAssigned,
                    content = aRow[AnswersTable.content],
                    isAssignedProfessor = isAssigned,
                    createdAt = aRow[AnswersTable.createdAt].toString()
                )
            }

            QuestionListItemDto(
                id = qId.toString(),
                courseId = row[QuestionsTable.courseId].toString(),
                courseName = courseName,
                profId = assignedProfId.toString(),
                profName = profUsers[assignedProfId] ?: "담당교수",
                category = QuestionCategory.valueOf(row[QuestionsTable.category]),
                title = row[QuestionsTable.title],
                content = displayContent,
                originalContent = rawContent,
                studentId = authorId.toString(),
                studentTag = authorDisplayTag,
                studentName = studentName,
                authorDisplayName = authorDisplayName,
                isAnonymous = isAnonymous,
                isMyQuestion = isMyQuestion,
                status = QuestionStatus.valueOf(row[QuestionsTable.status]),
                hasAnswers = hasAnswers,
                isAnsweredByAssignedProfessor = isAnsweredByAssignedProfessor,
                answers = answerDtos,
                createdAt = row[QuestionsTable.createdAt].toString()
            )
        }
    }

    suspend fun getQuestionDetail(questionId: UUID, currentUserId: UUID): QuestionDetailDto = dbQuery {
        getQuestionDetailInternal(questionId, currentUserId)
    }

    private fun getQuestionDetailInternal(questionId: UUID, currentUserId: UUID): QuestionDetailDto {
        val qRow = QuestionsTable.select { QuestionsTable.id eq questionId }.singleOrNull()
            ?: throw IllegalArgumentException("질문을 찾을 수 없습니다.")

        val course = CoursesTable.select { CoursesTable.id eq qRow[QuestionsTable.courseId] }.single()
        val author = UsersTable.select { UsersTable.id eq qRow[QuestionsTable.studentId] }.single()
        val assignedProf = UsersTable.select { UsersTable.id eq qRow[QuestionsTable.assignedProfessorId] }.single()
        val currentUser = UsersTable.select { UsersTable.id eq currentUserId }.single()

        val currentUserRole = UserRole.valueOf(currentUser[UsersTable.role])
        val isMyQuestion = (qRow[QuestionsTable.studentId] == currentUserId)
        val isAnonymous = qRow[QuestionsTable.isAnonymous]
        val courseName = course[CoursesTable.name]

        val authorDisplayName = when {
            isMyQuestion -> "${author[UsersTable.displayTag]} (나)"
            isAnonymous -> "$courseName 수강생"
            else -> author[UsersTable.displayTag]
        }

        // Answers
        val answersList = AnswersTable.innerJoin(UsersTable, { AnswersTable.professorId }, { UsersTable.id })
            .select { AnswersTable.questionId eq questionId }
            .orderBy(AnswersTable.createdAt to SortOrder.ASC)
            .map { aRow ->
                AnswerDto(
                    id = aRow[AnswersTable.id].value.toString(),
                    questionId = questionId.toString(),
                    professorId = aRow[AnswersTable.professorId].toString(),
                    professorName = aRow[UsersTable.name],
                    professorDepartment = aRow[UsersTable.department],
                    content = aRow[AnswersTable.content],
                    isAssignedProfessor = aRow[AnswersTable.isAssignedProfessor],
                    createdAt = aRow[AnswersTable.createdAt].toString()
                )
            }

        val assignedProfId = qRow[QuestionsTable.assignedProfessorId]
        val isAnsweredByAssigned = answersList.any { it.isAssignedProfessor }
        val category = QuestionCategory.valueOf(qRow[QuestionsTable.category])

        // Determine if current user can answer:
        val canAnswer = calculateCanAnswer(
            currentUserRole = currentUserRole,
            currentUserId = currentUserId,
            assignedProfessorId = assignedProfId,
            courseId = qRow[QuestionsTable.courseId],
            category = category,
            isAnsweredByAssignedProfessor = isAnsweredByAssigned
        )

        return QuestionDetailDto(
            id = questionId.toString(),
            courseId = qRow[QuestionsTable.courseId].toString(),
            courseName = courseName,
            profId = assignedProfId.toString(),
            profName = assignedProf[UsersTable.name],
            category = category,
            title = qRow[QuestionsTable.title],
            content = qRow[QuestionsTable.refinedContent].ifBlank { qRow[QuestionsTable.rawContent] },
            originalContent = qRow[QuestionsTable.rawContent],
            rawContent = qRow[QuestionsTable.rawContent],
            refinedContent = qRow[QuestionsTable.refinedContent],
            studentId = author[UsersTable.id].value.toString(),
            studentTag = author[UsersTable.displayTag],
            studentName = author[UsersTable.name],
            authorDisplayName = authorDisplayName,
            isAnonymous = isAnonymous,
            isMyQuestion = isMyQuestion,
            assignedProfessorId = assignedProfId.toString(),
            assignedProfessorName = assignedProf[UsersTable.name],
            isAnsweredByAssignedProfessor = isAnsweredByAssigned,
            canAnswer = canAnswer,
            answers = answersList,
            createdAt = qRow[QuestionsTable.createdAt].toString()
        )
    }

    suspend fun updateQuestion(
        questionId: UUID,
        currentUserId: UUID,
        request: UpdateQuestionRequest
    ): QuestionDetailDto = dbQuery {
        val qRow = QuestionsTable.select { QuestionsTable.id eq questionId }.singleOrNull()
            ?: throw IllegalArgumentException("질문을 찾을 수 없습니다.")

        val authorId = qRow[QuestionsTable.studentId]
        if (authorId != currentUserId) {
            throw IllegalStateException("질문 작성자만 수정할 수 있습니다.")
        }

        if (request.category == QuestionCategory.GRADE && (request.isAnonymous == true || (request.isAnonymous == null && qRow[QuestionsTable.isAnonymous]))) {
            throw IllegalArgumentException("성적 카테고리 질문은 익명으로 작성할 수 없습니다.")
        }

        QuestionsTable.update({ QuestionsTable.id eq questionId }) {
            request.title?.takeIf { it.isNotBlank() }?.let { v -> it[title] = v }
            request.category?.let { v -> it[category] = v.name }
            request.isAnonymous?.let { v -> it[isAnonymous] = v }
            request.rawContent?.takeIf { it.isNotBlank() }?.let { v -> it[rawContent] = v }
            request.originalContent?.takeIf { it.isNotBlank() }?.let { v -> it[rawContent] = v }
            request.refinedContent?.takeIf { it.isNotBlank() }?.let { v -> it[refinedContent] = v }
            request.content?.takeIf { it.isNotBlank() }?.let { v -> it[refinedContent] = v }
        }

        // Recompute vector if title or content changed
        val updatedRow = QuestionsTable.select { QuestionsTable.id eq questionId }.single()
        val newText = "${updatedRow[QuestionsTable.title]} ${updatedRow[QuestionsTable.refinedContent]}"
        val newVector = similarityService.computeVector(newText)
        val vectorJson = similarityService.serializeVector(newVector)
        QuestionsTable.update({ QuestionsTable.id eq questionId }) {
            it[vectorData] = vectorJson
        }

        getQuestionDetailInternal(questionId, currentUserId)
    }

    suspend fun createAnswer(
        questionId: UUID,
        professorId: UUID,
        request: CreateAnswerRequest
    ): AnswerDto = dbQuery {
        val qRow = QuestionsTable.select { QuestionsTable.id eq questionId }.singleOrNull()
            ?: throw IllegalArgumentException("질문을 찾을 수 없습니다.")

        val profUser = UsersTable.select { UsersTable.id eq professorId }.singleOrNull()
            ?: throw IllegalArgumentException("교수 계정을 찾을 수 없습니다.")

        if (UserRole.valueOf(profUser[UsersTable.role]) != UserRole.PROFESSOR) {
            throw IllegalArgumentException("교수 계정만 답변을 등록할 수 있습니다.")
        }

        val courseId = qRow[QuestionsTable.courseId]
        val assignedProfId = qRow[QuestionsTable.assignedProfessorId]
        val category = QuestionCategory.valueOf(qRow[QuestionsTable.category])

        // Check if assigned professor already answered
        val existingAnswers = AnswersTable.select { AnswersTable.questionId eq questionId }.toList()
        val isAssignedProfAnswered = existingAnswers.any { it[AnswersTable.isAssignedProfessor] }

        val canAnswer = calculateCanAnswer(
            currentUserRole = UserRole.PROFESSOR,
            currentUserId = professorId,
            assignedProfessorId = assignedProfId,
            courseId = courseId,
            category = category,
            isAnsweredByAssignedProfessor = isAssignedProfAnswered
        )

        if (!canAnswer) {
            if (isAssignedProfAnswered) {
                throw IllegalStateException("질문자의 담당교수가 이미 답변을 완료하여 추가 답변이 제한됩니다.")
            } else if (category != QuestionCategory.LECTURE && professorId != assignedProfId) {
                throw IllegalStateException("'$category' 카테고리는 질문자의 담당 교수만 답변을 등록할 수 있습니다.")
            } else {
                throw IllegalStateException("답변 권한이 없습니다.")
            }
        }

        val isAssigned = (professorId == assignedProfId)
        val ansId = UUID.randomUUID()
        val now = Instant.now()

        AnswersTable.insert {
            it[id] = ansId
            it[AnswersTable.questionId] = questionId
            it[AnswersTable.professorId] = professorId
            it[content] = request.content
            it[isAssignedProfessor] = isAssigned
            it[createdAt] = now
        }

        // Update question status
        QuestionsTable.update({ QuestionsTable.id eq questionId }) {
            it[status] = QuestionStatus.ANSWERED.name
        }

        val course = CoursesTable.select { CoursesTable.id eq courseId }.single()

        // Notify student
        notificationService.notifyNewAnswer(
            studentId = qRow[QuestionsTable.studentId],
            courseName = course[CoursesTable.name],
            questionId = questionId,
            questionTitle = qRow[QuestionsTable.title],
            professorName = profUser[UsersTable.name]
        )

        AnswerDto(
            id = ansId.toString(),
            questionId = questionId.toString(),
            professorId = professorId.toString(),
            professorName = profUser[UsersTable.name],
            professorDepartment = profUser[UsersTable.department],
            content = request.content,
            isAssignedProfessor = isAssigned,
            createdAt = now.toString()
        )
    }

    suspend fun getSimilarQuestions(
        courseId: UUID,
        category: QuestionCategory,
        queryText: String,
        excludeQuestionId: UUID? = null,
        limit: Int = 3
    ): List<SimilarQuestionDto> = dbQuery {
        // Grade questions cannot use similar Q&A
        if (category == QuestionCategory.GRADE) {
            return@dbQuery emptyList()
        }

        val queryVec = similarityService.computeVector(queryText)

        val questions = QuestionsTable.select {
            (QuestionsTable.courseId eq courseId) and
                    (QuestionsTable.category eq category.name)
        }.toList()

        val results = mutableListOf<SimilarQuestionDto>()

        for (row in questions) {
            val qId = row[QuestionsTable.id].value
            if (excludeQuestionId != null && qId == excludeQuestionId) continue

            val vecJson = row[QuestionsTable.vectorData]
            val docVec = similarityService.deserializeVector(vecJson)
            val sim = similarityService.cosineSimilarity(queryVec, docVec)

            val answers = AnswersTable.select { AnswersTable.questionId eq qId }
                .orderBy(AnswersTable.createdAt to SortOrder.ASC)
                .toList()

            val topAnswer = answers.firstOrNull()?.get(AnswersTable.content)
            val snippet = row[QuestionsTable.refinedContent].take(100)

            val answerDtos = answers.map { aRow ->
                AnswerDto(
                    id = aRow[AnswersTable.id].value.toString(),
                    questionId = qId.toString(),
                    professorId = aRow[AnswersTable.professorId].toString(),
                    professorName = "",
                    professorDepartment = "",
                    content = aRow[AnswersTable.content],
                    isAssignedProfessor = aRow[AnswersTable.isAssignedProfessor],
                    createdAt = aRow[AnswersTable.createdAt].toString()
                )
            }

            results.add(
                SimilarQuestionDto(
                    id = qId.toString(),
                    title = row[QuestionsTable.title],
                    snippet = snippet,
                    content = snippet,
                    category = category,
                    similarity = sim,
                    similarityScore = (sim * 100).toInt(),
                    answerCount = answers.size,
                    topAnswerSnippet = topAnswer?.take(150),
                    answers = answerDtos
                )
            )
        }

        results.sortedByDescending { it.similarity }.take(limit)
    }

    private fun calculateCanAnswer(
        currentUserRole: UserRole,
        currentUserId: UUID,
        assignedProfessorId: UUID,
        courseId: UUID,
        category: QuestionCategory,
        isAnsweredByAssignedProfessor: Boolean
    ): Boolean {
        if (currentUserRole != UserRole.PROFESSOR) return false

        // Check if this professor teaches this course
        val teachesCourse = CourseProfessorsTable.select {
            (CourseProfessorsTable.courseId eq courseId) and (CourseProfessorsTable.professorId eq currentUserId)
        }.count() > 0

        if (!teachesCourse) return false

        // Rule: "질문자의 담당교수가 답변을 남긴 경우 다른 교수는 답변 불가능."
        if (isAnsweredByAssignedProfessor && currentUserId != assignedProfessorId) {
            return false
        }

        val isAssignedProf = (currentUserId == assignedProfessorId)

        return when (category) {
            QuestionCategory.GRADE, QuestionCategory.ASSIGNMENT -> {
                // Rule: "같은 과목 다른 교수 기능 사용 불가. 오직 담당교수만 가능."
                isAssignedProf
            }
            QuestionCategory.LECTURE -> {
                // Rule: "같은 과목 다른 교수 기능 가능. 담당교수가 남기지 않았다면 다른 교수도 답변 가능."
                true
            }
        }
    }
}
