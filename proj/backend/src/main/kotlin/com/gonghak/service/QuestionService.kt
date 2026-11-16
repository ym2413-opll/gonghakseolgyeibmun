package com.gonghak.service

import com.gonghak.config.DatabaseFactory.dbQuery
import com.gonghak.config.DatabaseFactory.dbQueryWithRetry
import com.gonghak.dto.*
import com.gonghak.models.*
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class QuestionService(
    private val similarityService: SimilarityService,
    private val notificationService: NotificationService,
    private val aiRefineService: AiRefineService,
    private val workerManager: BackgroundWorkerManager = BackgroundWorkerManager(),
    private val attachmentService: AttachmentService? = null
) {
    private data class CreatedQuestionSideEffects(
        val detail: QuestionDetailDto,
        val questionId: UUID,
        val assignedProfId: UUID,
        val courseId: UUID,
        val courseName: String,
        val title: String,
        val contentForMail: String,
        val authorDisplayForMail: String,
        val vectorSourceText: String
    )

    private data class CreatedAnswerResult(
        val dto: AnswerDto,
        val studentId: UUID,
        val assignedProfId: UUID,
        val participatingProfIds: List<UUID>,
        val courseName: String,
        val questionTitle: String,
        val professorName: String,
        val isProfessorAuthor: Boolean = true
    )

    private data class CreatedFollowUpResult(
        val dto: AnswerDto,
        val assignedProfId: UUID,
        val participatingProfIds: List<UUID>,
        val courseName: String,
        val questionTitle: String,
        val category: QuestionCategory,
        val authorDisplay: String
    )

    private data class AuthorDeleteResult(
        val attachmentNames: List<String>,
        val assignedProfId: UUID,
        val participatingProfIds: List<UUID>,
        val courseName: String,
        val questionTitle: String
    )

    private data class AuthorSummary(
        val name: String,
        val role: String,
        val displayTag: String,
        val department: String,
        val isAdmin: Boolean = false
    )

    companion object {
        const val MAX_TITLE_LENGTH = 180
        const val MAX_CONTENT_LENGTH = 20_000
    }

    private fun findParticipatingProfessorIds(questionId: UUID): List<UUID> =
        AnswersTable.innerJoin(UsersTable, { AnswersTable.professorId }, { UsersTable.id })
            .slice(AnswersTable.professorId)
            .select {
                (AnswersTable.questionId eq questionId) and
                    (AnswersTable.isHidden eq false) and
                    (UsersTable.role eq UserRole.PROFESSOR.name)
            }
            .withDistinct()
            .map { it[AnswersTable.professorId] }

    private fun validateContentLength(text: String?, label: String) {
        if (text != null && text.length > MAX_CONTENT_LENGTH) {
            throw IllegalArgumentException("${label}은(는) 최대 ${MAX_CONTENT_LENGTH}자까지 입력할 수 있습니다.")
        }
    }

    private data class SimilarQuestionCandidate(
        val questionId: UUID,
        val title: String,
        val refinedContent: String,
        val answerDtos: List<AnswerDto>
    )
    private val banDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.of("Asia/Seoul"))

    suspend fun createQuestion(
        currentUserId: UUID,
        request: CreateQuestionRequest
    ): QuestionDetailDto {
        val created = dbQuery {
            // 0. Check User Existence, Role, Withdrawal & Question Restriction (Ban)
            val studentUser = UsersTable.select { UsersTable.id eq currentUserId }.singleOrNull()
                ?: throw IllegalStateException("유효한 사용자 계정을 찾을 수 없습니다.")
            val creatorRole = runCatching { UserRole.valueOf(studentUser[UsersTable.role]) }
                .getOrDefault(UserRole.STUDENT)
            if (creatorRole != UserRole.STUDENT) {
                throw IllegalStateException("학생 계정만 질문을 작성할 수 있습니다.")
            }
            if (studentUser[UsersTable.isWithdrawn]) {
                throw IllegalStateException("탈퇴 처리된 계정입니다. 질문을 작성할 수 없습니다.")
            }
            val banUntil = studentUser[UsersTable.questionBanUntil]
            if (banUntil != null && banUntil.isAfter(Instant.now())) {
                val formattedUntil = banDateFormatter.format(banUntil)
                throw IllegalStateException("관리자에 의해 ${formattedUntil}까지 질문 작성이 제한된 계정입니다.")
            }

            val parsedCourseUUID = request.courseId?.takeIf { it.isNotBlank() }?.let {
                com.gonghak.routes.parseCourseIdStrict(it)
            }
            val courseRow = if (parsedCourseUUID != null) {
                CoursesTable.select { CoursesTable.id eq parsedCourseUUID }.singleOrNull()
            } else {
                request.courseName?.takeIf { it.isNotBlank() }?.let { cName ->
                    CoursesTable.select { CoursesTable.name eq cName }.firstOrNull()
                }
            } ?: throw IllegalArgumentException("요청하신 강의 정보를 찾을 수 없습니다.")

            val courseUUID = courseRow[CoursesTable.id].value
            val courseName = courseRow[CoursesTable.name]

            // 1. Validate Category Rules
            if (request.category == QuestionCategory.GRADE && request.isAnonymous) {
                throw IllegalArgumentException("성적 카테고리 질문은 익명으로 작성할 수 없습니다.")
            }

            // 2. Identify student and assigned professor
            val enrollment = EnrollmentsTable.select {
                (EnrollmentsTable.studentId eq currentUserId) and (EnrollmentsTable.courseId eq courseUUID)
            }.singleOrNull()

            val requestedProfUUID = request.profId?.takeIf { it.isNotBlank() }?.let { rawProfId ->
                val pId = com.gonghak.routes.parseUserIdOrNull(rawProfId)
                    ?: throw IllegalArgumentException("유효하지 않은 교수 ID입니다.")
                val isTeachingCourse = CourseProfessorsTable.innerJoin(
                    UsersTable, { CourseProfessorsTable.professorId }, { UsersTable.id }
                ).select {
                    (CourseProfessorsTable.courseId eq courseUUID) and
                        (CourseProfessorsTable.professorId eq pId) and
                        (UsersTable.role eq UserRole.PROFESSOR.name)
                }.count() > 0
                if (!isTeachingCourse) {
                    throw IllegalArgumentException("해당 교과목에 배정된 담당 교수가 아닙니다.")
                }
                pId
            }

            val courseProfs = CourseProfessorsTable.innerJoin(UsersTable, { CourseProfessorsTable.professorId }, { UsersTable.id })
                .select { CourseProfessorsTable.courseId eq courseUUID }
                .toList()
            val enrolledProfId = enrollment?.get(EnrollmentsTable.assignedProfessorId)
            val enrolledIsRegistered = enrolledProfId?.let { eId ->
                courseProfs.any {
                    it[UsersTable.id].value == eId &&
                        !it[UsersTable.isDemo] &&
                        !it[UsersTable.passwordHash].isNullOrBlank()
                }
            } ?: false
            val firstRegisteredProfId = courseProfs.firstOrNull {
                !it[UsersTable.isDemo] && !it[UsersTable.passwordHash].isNullOrBlank()
            }?.get(CourseProfessorsTable.professorId)

            val assignedProfId = when {
                enrolledProfId != null && enrolledIsRegistered -> enrolledProfId
                requestedProfUUID != null -> requestedProfUUID
                firstRegisteredProfId != null -> firstRegisteredProfId
                enrolledProfId != null -> enrolledProfId
                else -> courseProfs.firstOrNull()?.get(CourseProfessorsTable.professorId)
            } ?: throw IllegalArgumentException("아직 해당 교과목의 담당 교수가 배정되지 않았습니다.")

            // 3. Validate if professor has enabled this category
            val categorySetting = ProfessorCategorySettingsTable.select {
                (ProfessorCategorySettingsTable.professorId eq assignedProfId) and
                        (ProfessorCategorySettingsTable.courseId eq courseUUID) and
                        (ProfessorCategorySettingsTable.category eq request.category.name)
            }.singleOrNull()

            if (categorySetting != null && !categorySetting[ProfessorCategorySettingsTable.isEnabled]) {
                throw IllegalStateException("담당 교수님에 의해 '${request.category}' 카테고리 질문 접수가 비활성화되어 있습니다.")
            }

            // 4. Calculate refined content if not provided or refine using AI service
            val effectiveRaw = request.effectiveRawContent
            val finalRefinedContent = if (!request.refinedContent.isNullOrBlank()) {
                request.refinedContent!!
            } else if (!request.content.isNullOrBlank()) {
                request.content!!
            } else {
                effectiveRaw
            }
            validateContentLength(effectiveRaw, "질문 원문")
            validateContentLength(finalRefinedContent, "질문 본문")

            val effectiveTitle = if (!request.title.isNullOrBlank()) {
                val trimmedTitle = request.title!!.trim()
                if (trimmedTitle.length > MAX_TITLE_LENGTH) {
                    throw IllegalArgumentException("제목은 최대 ${MAX_TITLE_LENGTH}자까지 입력할 수 있습니다.")
                }
                trimmedTitle
            } else {
                generateSummaryTitle(finalRefinedContent.ifBlank { effectiveRaw }).take(MAX_TITLE_LENGTH)
            }

            val qId = UUID.randomUUID()
            val now = Instant.now()

            QuestionsTable.insert {
                it[id] = qId
                it[courseId] = courseUUID
                it[studentId] = currentUserId
                it[assignedProfessorId] = assignedProfId
                it[category] = request.category.name
                it[title] = effectiveTitle
                it[rawContent] = effectiveRaw
                it[refinedContent] = finalRefinedContent
                it[isAnonymous] = request.isAnonymous
                it[isHidden] = false
                it[isThreadOpen] = false
                it[lastSequenceNumber] = 1
                it[vectorData] = "[]"
                it[status] = QuestionStatus.OPEN.name
                it[createdAt] = now
            }

            // Link attachments if provided (must belong to currentUserId and not already linked)
            if (request.attachmentIds.isNotEmpty()) {
                val validUuids = request.attachmentIds.mapNotNull {
                    try { UUID.fromString(it) } catch (_: Exception) { null }
                }
                if (validUuids.isNotEmpty()) {
                    AttachmentsTable.update({
                        (AttachmentsTable.id inList validUuids) and
                            (AttachmentsTable.uploaderId eq currentUserId) and
                            AttachmentsTable.questionId.isNull()
                    }) {
                        it[AttachmentsTable.questionId] = qId
                    }
                }
            }

            val authorDisplayForMail = if (request.isAnonymous) {
                "$courseName 수강생 (익명)"
            } else {
                studentUser[UsersTable.displayTag].ifBlank { request.studentTag ?: "수강생" }
            }

            CreatedQuestionSideEffects(
                detail = getQuestionDetailInternal(qId, currentUserId),
                questionId = qId,
                assignedProfId = assignedProfId,
                courseId = courseUUID,
                courseName = courseName,
                title = effectiveTitle,
                contentForMail = finalRefinedContent.ifBlank { effectiveRaw },
                authorDisplayForMail = authorDisplayForMail,
                vectorSourceText = "$effectiveTitle $finalRefinedContent"
            )
        }

        // 5. Offload embedding vector indexing to dedicated similarity worker thread pool
        workerManager.launchSimilarityTask("index-question-${created.questionId}") {
            val vector = similarityService.computeVector(created.vectorSourceText)
            val vectorJson = similarityService.serializeVector(vector)
            dbQuery(workerManager.similarityDispatcher) {
                QuestionsTable.update({ QuestionsTable.id eq created.questionId }) {
                    it[vectorData] = vectorJson
                }
            }
        }

        // 6. Trigger notifications on dedicated notification worker pool & email sending asynchronously on email worker pool
        notificationService.notifyNewQuestion(
            assignedProfessorId = created.assignedProfId,
            courseId = created.courseId,
            courseName = created.courseName,
            questionId = created.questionId,
            questionTitle = created.title,
            category = request.category,
            questionContent = created.contentForMail,
            authorDisplay = created.authorDisplayForMail
        )

        return created.detail
    }

    suspend fun getQuestions(
        currentUserId: UUID,
        courseIdFilter: UUID? = null,
        categoryFilter: QuestionCategory? = null
    ): List<QuestionListItemDto> = dbQuery {
        val currentUserRow = UsersTable.select { UsersTable.id eq currentUserId }.singleOrNull()
        val currentUserRole = currentUserRow?.let {
            runCatching { UserRole.valueOf(it[UsersTable.role]) }.getOrDefault(UserRole.STUDENT)
        } ?: UserRole.STUDENT
        val isProfessor = currentUserRole == UserRole.PROFESSOR
        val isAdmin = currentUserRow?.let {
            it[UsersTable.isAdmin] || it[UsersTable.role] == UserRole.ADMIN.name
        } ?: false

        val join = QuestionsTable
            .innerJoin(CoursesTable, { QuestionsTable.courseId }, { CoursesTable.id })
            .innerJoin(UsersTable, { QuestionsTable.studentId }, { UsersTable.id })

        val query = join.selectAll()

        if (!isAdmin && !isProfessor) {
            query.andWhere { QuestionsTable.isDeletedByAuthor eq false }
        }
        if (!isAdmin) {
            query.andWhere {
                (QuestionsTable.category neq QuestionCategory.GRADE.name) or
                    (QuestionsTable.studentId eq currentUserId) or
                    (QuestionsTable.assignedProfessorId eq currentUserId)
            }
        }
        courseIdFilter?.let { query.andWhere { QuestionsTable.courseId eq it } }
        categoryFilter?.let { query.andWhere { QuestionsTable.category eq it.name } }

        query.orderBy(QuestionsTable.createdAt to SortOrder.DESC)

        val allAttachments = AttachmentsTable.selectAll().toList()
        val attachmentsByQuestionId = allAttachments.groupBy { it[AttachmentsTable.questionId] }

        val allUsersMap = UsersTable.selectAll().associate { uRow ->
            uRow[UsersTable.id].value to AuthorSummary(
                name = uRow[UsersTable.name],
                role = uRow[UsersTable.role],
                displayTag = uRow[UsersTable.displayTag],
                department = uRow[UsersTable.department],
                isAdmin = uRow[UsersTable.isAdmin] || uRow[UsersTable.role] == UserRole.ADMIN.name
            )
        }

        query.map { row ->
            val qId = row[QuestionsTable.id].value
            val authorId = row[QuestionsTable.studentId]
            val authorIsAdmin = allUsersMap[authorId]?.isAdmin ?: false
            val isMyQuestion = (authorId == currentUserId)
            val isAnonymous = row[QuestionsTable.isAnonymous]
            val isQuestionHidden = row[QuestionsTable.isHidden]
            val isThreadOpen = row[QuestionsTable.isThreadOpen]
            val lastSequenceNumber = row[QuestionsTable.lastSequenceNumber]
            val isQuestionDeleted = row[QuestionsTable.isDeletedByAuthor]
            val deletedAtStr = row[QuestionsTable.deletedAt]?.toString()
            val courseName = row[CoursesTable.name]
            val authorDisplayTag = row[UsersTable.displayTag]
            val studentName = row[UsersTable.name]
            val studentIdentifier = row[UsersTable.identifier]

            val authorDisplayName = when {
                isMyQuestion -> "$authorDisplayTag (나)"
                isAnonymous -> "$courseName 수강생"
                else -> authorDisplayTag
            }

            val assignedProfId = row[QuestionsTable.assignedProfessorId]
            val isAssignedProfViewer = (currentUserId == assignedProfId)
            val rawAnswers = AnswersTable.select { AnswersTable.questionId eq qId }
                .orderBy(AnswersTable.sequenceNumber to SortOrder.ASC, AnswersTable.createdAt to SortOrder.ASC)
                .toList()

            val rawContent = row[QuestionsTable.rawContent]
            val refinedContent = row[QuestionsTable.refinedContent]
            val actualContent = refinedContent.ifBlank { rawContent }
            val actualTitle = row[QuestionsTable.title]

            val maskedForHidden = isQuestionHidden && !isAdmin
            val displayTitle = when {
                maskedForHidden -> "관리자에 의해 가려진 질문입니다."
                isQuestionDeleted -> "[작성자 삭제] $actualTitle"
                else -> actualTitle
            }
            val displayContent = when {
                isQuestionDeleted -> "질문 작성자에 의해 삭제된 게시물입니다."
                maskedForHidden -> "관리자에 의해 가려진 게시물입니다."
                else -> actualContent
            }
            val displayOrigContent = when {
                isQuestionDeleted || isProfessor || maskedForHidden || (!isMyQuestion && !isAdmin) -> ""
                else -> rawContent
            }

            val answerDtos = rawAnswers.map { aRow ->
                val aAuthorId = aRow[AnswersTable.professorId]
                val authorSummary = allUsersMap[aAuthorId] ?: AuthorSummary(
                    name = "교수",
                    role = UserRole.PROFESSOR.name,
                    displayTag = "수강생",
                    department = ""
                )
                buildAnswerDto(
                    answerId = aRow[AnswersTable.id].value,
                    questionId = qId,
                    questionStudentId = authorId,
                    authorUuid = aAuthorId,
                    authorSummary = authorSummary,
                    rawContent = aRow[AnswersTable.content],
                    originalDraftContent = aRow[AnswersTable.rawContent],
                    isAssignedProfessor = aRow[AnswersTable.isAssignedProfessor],
                    isAnsHidden = aRow[AnswersTable.isHidden],
                    sequenceNumber = aRow[AnswersTable.sequenceNumber],
                    createdAt = aRow[AnswersTable.createdAt].toString(),
                    courseName = courseName,
                    isQuestionAnonymous = isAnonymous,
                    currentUserId = currentUserId,
                    isMyQuestion = isMyQuestion,
                    isAdminViewer = isAdmin,
                    isProfessorViewer = isProfessor,
                    isAssignedProfViewer = isAssignedProfViewer,
                    isThreadOpen = isThreadOpen,
                    isQuestionDeleted = isQuestionDeleted,
                    isQuestionHidden = isQuestionHidden,
                    hideOriginalDraft = isProfessor
                )
            }

            val profAnswers = answerDtos.filter { it.authorRole == UserRole.PROFESSOR.name || it.isAdminAuthor }
            val hasAnswers = profAnswers.isNotEmpty()
            val isAnsweredByAssignedProfessor = answerDtos.any { it.authorRole == UserRole.PROFESSOR.name && it.isAssignedProfessor }

            val visibleAttachments = if (maskedForHidden || isQuestionDeleted) {
                emptyList()
            } else {
                attachmentsByQuestionId[qId]?.map { attRow ->
                    val attIdStr = attRow[AttachmentsTable.id].value.toString()
                    AttachmentDto(
                        id = attIdStr,
                        questionId = qId.toString(),
                        originalName = attRow[AttachmentsTable.originalName],
                        savedName = attRow[AttachmentsTable.savedName],
                        fileSize = attRow[AttachmentsTable.fileSize],
                        originalSize = attRow[AttachmentsTable.originalSize],
                        mimeType = attRow[AttachmentsTable.mimeType],
                        isImage = attRow[AttachmentsTable.isImage],
                        isCompressed = attRow[AttachmentsTable.isCompressed],
                        compressionRatio = attRow[AttachmentsTable.compressionRatio],
                        url = "/api/attachments/$attIdStr",
                        downloadUrl = "/api/attachments/$attIdStr/download",
                        createdAt = attRow[AttachmentsTable.createdAt].toString()
                    )
                } ?: emptyList()
            }

            QuestionListItemDto(
                id = qId.toString(),
                courseId = row[QuestionsTable.courseId].toString(),
                courseName = courseName,
                profId = assignedProfId.toString(),
                profName = allUsersMap[assignedProfId]?.name ?: "담당교수",
                category = QuestionCategory.valueOf(row[QuestionsTable.category]),
                title = displayTitle,
                content = displayContent,
                originalContent = displayOrigContent,
                studentId = if (isAnonymous && !isMyQuestion && !isAdmin) null else authorId.toString(),
                studentTag = if (isAnonymous && !isMyQuestion && !isAdmin) "$courseName 수강생" else authorDisplayTag,
                studentName = if (isAnonymous && !isMyQuestion && !isAdmin) "익명 수강생" else studentName,
                realStudentId = if (isAdmin) authorId.toString() else null,
                realStudentName = if (isAdmin) studentName else null,
                realStudentTag = if (isAdmin) authorDisplayTag else null,
                realStudentIdentifier = if (isAdmin) studentIdentifier else null,
                authorDisplayName = authorDisplayName,
                isAnonymous = isAnonymous,
                isMyQuestion = isMyQuestion,
                isHidden = isQuestionHidden,
                isThreadOpen = isThreadOpen,
                lastSequenceNumber = lastSequenceNumber,
                isAdminAuthor = authorIsAdmin,
                isDeleted = isQuestionDeleted,
                deletedAt = deletedAtStr,
                canDelete = !isQuestionDeleted && isMyQuestion && currentUserRole == UserRole.STUDENT,
                status = QuestionStatus.valueOf(row[QuestionsTable.status]),
                hasAnswers = hasAnswers,
                isAnsweredByAssignedProfessor = isAnsweredByAssignedProfessor,
                answers = answerDtos,
                attachmentCount = visibleAttachments.size,
                attachments = visibleAttachments,
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

        val course = CoursesTable.select { CoursesTable.id eq qRow[QuestionsTable.courseId] }.singleOrNull()
        val author = UsersTable.select { UsersTable.id eq qRow[QuestionsTable.studentId] }.singleOrNull()
        val assignedProf = UsersTable.select { UsersTable.id eq qRow[QuestionsTable.assignedProfessorId] }.singleOrNull()
        val currentUser = UsersTable.select { UsersTable.id eq currentUserId }.singleOrNull()

        val currentUserRole = currentUser?.let { runCatching { UserRole.valueOf(it[UsersTable.role]) }.getOrDefault(UserRole.STUDENT) } ?: UserRole.STUDENT
        val isProfessor = currentUserRole == UserRole.PROFESSOR
        val isAdmin = currentUser?.let { it[UsersTable.isAdmin] || currentUserRole == UserRole.ADMIN } ?: false
        val isMyQuestion = (qRow[QuestionsTable.studentId] == currentUserId)
        val isAnonymous = qRow[QuestionsTable.isAnonymous]
        val isQuestionHidden = qRow[QuestionsTable.isHidden]
        val isThreadOpen = qRow[QuestionsTable.isThreadOpen]
        val lastSequenceNumber = qRow[QuestionsTable.lastSequenceNumber]
        val isQuestionDeleted = qRow[QuestionsTable.isDeletedByAuthor]
        val deletedAtStr = qRow[QuestionsTable.deletedAt]?.toString()

        if (isQuestionDeleted && !isProfessor && !isAdmin) {
            throw IllegalArgumentException("작성자에 의해 삭제된 질문입니다.")
        }

        val category = QuestionCategory.valueOf(qRow[QuestionsTable.category])
        val assignedProfId = qRow[QuestionsTable.assignedProfessorId]
        if (category == QuestionCategory.GRADE && !isAdmin && !isMyQuestion && currentUserId != assignedProfId) {
            throw IllegalStateException("성적 카테고리 질문은 작성자와 담당교수만 열람할 수 있습니다.")
        }

        val courseName = course?.get(CoursesTable.name) ?: "강의"
        val authorTag = author?.get(UsersTable.displayTag) ?: "수강생"
        val authorName = author?.get(UsersTable.name) ?: "학생"
        val authorIdentifier = author?.get(UsersTable.identifier) ?: ""
        val authorIsAdmin = author?.let { it[UsersTable.isAdmin] || it[UsersTable.role] == UserRole.ADMIN.name } ?: false
        val isAssignedProfViewer = (currentUserId == assignedProfId)
        val assignedProfName = assignedProf?.get(UsersTable.name) ?: "담당교수"

        val authorDisplayName = when {
            isMyQuestion -> "$authorTag (나)"
            isAnonymous -> "$courseName 수강생"
            else -> authorTag
        }

        // Answers & Follow-up questions
        val answersList = AnswersTable.innerJoin(UsersTable, { AnswersTable.professorId }, { UsersTable.id })
            .select { AnswersTable.questionId eq questionId }
            .orderBy(AnswersTable.sequenceNumber to SortOrder.ASC, AnswersTable.createdAt to SortOrder.ASC)
            .map { aRow ->
                buildAnswerDto(
                    answerId = aRow[AnswersTable.id].value,
                    questionId = questionId,
                    questionStudentId = qRow[QuestionsTable.studentId],
                    authorUuid = aRow[AnswersTable.professorId],
                    authorSummary = AuthorSummary(
                        name = aRow[UsersTable.name],
                        role = aRow[UsersTable.role],
                        displayTag = aRow[UsersTable.displayTag],
                        department = aRow[UsersTable.department],
                        isAdmin = aRow[UsersTable.isAdmin] || aRow[UsersTable.role] == UserRole.ADMIN.name
                    ),
                    rawContent = aRow[AnswersTable.content],
                    originalDraftContent = aRow[AnswersTable.rawContent],
                    isAssignedProfessor = aRow[AnswersTable.isAssignedProfessor],
                    isAnsHidden = aRow[AnswersTable.isHidden],
                    sequenceNumber = aRow[AnswersTable.sequenceNumber],
                    createdAt = aRow[AnswersTable.createdAt].toString(),
                    courseName = courseName,
                    isQuestionAnonymous = isAnonymous,
                    currentUserId = currentUserId,
                    isMyQuestion = isMyQuestion,
                    isAdminViewer = isAdmin,
                    isProfessorViewer = isProfessor,
                    isAssignedProfViewer = isAssignedProfViewer,
                    isThreadOpen = isThreadOpen,
                    isQuestionDeleted = isQuestionDeleted,
                    isQuestionHidden = isQuestionHidden,
                    hideOriginalDraft = isProfessor
                )
            }

        val profAnswers = answersList.filter { it.authorRole == UserRole.PROFESSOR.name || it.isAdminAuthor }
        val isAnsweredByAssigned = answersList.any { it.authorRole == UserRole.PROFESSOR.name && it.isAssignedProfessor }

        val canAnswer = !isQuestionDeleted && calculateCanAnswer(
            currentUserRole = currentUserRole,
            isAdmin = isAdmin,
            currentUserId = currentUserId,
            questionStudentId = qRow[QuestionsTable.studentId],
            assignedProfessorId = assignedProfId,
            courseId = qRow[QuestionsTable.courseId],
            category = category,
            isThreadOpen = isThreadOpen,
            isAnsweredByAssignedProfessor = isAnsweredByAssigned
        )
        val canFollowUp = !isQuestionDeleted &&
            (currentUserRole == UserRole.STUDENT) &&
            isMyQuestion &&
            !isQuestionHidden &&
            profAnswers.isNotEmpty()

        val maskedForHidden = isQuestionHidden && !isAdmin
        val attachmentDtos = if (maskedForHidden || isQuestionDeleted) {
            emptyList()
        } else {
            AttachmentsTable.select { AttachmentsTable.questionId eq questionId }
                .orderBy(AttachmentsTable.createdAt to SortOrder.ASC)
                .map { attRow ->
                    val attIdStr = attRow[AttachmentsTable.id].value.toString()
                    AttachmentDto(
                        id = attIdStr,
                        questionId = questionId.toString(),
                        originalName = attRow[AttachmentsTable.originalName],
                        savedName = attRow[AttachmentsTable.savedName],
                        fileSize = attRow[AttachmentsTable.fileSize],
                        originalSize = attRow[AttachmentsTable.originalSize],
                        mimeType = attRow[AttachmentsTable.mimeType],
                        isImage = attRow[AttachmentsTable.isImage],
                        isCompressed = attRow[AttachmentsTable.isCompressed],
                        compressionRatio = attRow[AttachmentsTable.compressionRatio],
                        url = "/api/attachments/$attIdStr",
                        downloadUrl = "/api/attachments/$attIdStr/download",
                        createdAt = attRow[AttachmentsTable.createdAt].toString()
                    )
                }
        }

        val actualTitle = qRow[QuestionsTable.title]
        val actualRaw = qRow[QuestionsTable.rawContent]
        val actualRefined = qRow[QuestionsTable.refinedContent]
        val actualContent = actualRefined.ifBlank { actualRaw }

        val displayTitle = when {
            maskedForHidden -> "관리자에 의해 가려진 질문입니다."
            isQuestionDeleted -> "[작성자 삭제] $actualTitle"
            else -> actualTitle
        }
        val displayContent = when {
            isQuestionDeleted -> "질문 작성자에 의해 삭제된 게시물입니다."
            maskedForHidden -> "관리자에 의해 가려진 게시물입니다."
            else -> actualContent
        }
        val displayOrigContent = when {
            isQuestionDeleted || isProfessor || maskedForHidden || (!isMyQuestion && !isAdmin) -> ""
            else -> actualRaw
        }
        val displayRefinedContent = when {
            isQuestionDeleted -> "질문 작성자에 의해 삭제된 게시물입니다."
            maskedForHidden -> "관리자에 의해 가려진 게시물입니다."
            else -> actualRefined
        }

        return QuestionDetailDto(
            id = questionId.toString(),
            courseId = qRow[QuestionsTable.courseId].toString(),
            courseName = courseName,
            profId = assignedProfId.toString(),
            profName = assignedProfName,
            category = category,
            title = displayTitle,
            content = displayContent,
            originalContent = displayOrigContent,
            rawContent = displayOrigContent,
            refinedContent = displayRefinedContent,
            studentId = if (isAnonymous && !isMyQuestion && !isAdmin) null else qRow[QuestionsTable.studentId].toString(),
            studentTag = if (isAnonymous && !isMyQuestion && !isAdmin) "$courseName 수강생" else authorTag,
            studentName = if (isAnonymous && !isMyQuestion && !isAdmin) "익명 수강생" else authorName,
            realStudentId = if (isAdmin) qRow[QuestionsTable.studentId].toString() else null,
            realStudentName = if (isAdmin) authorName else null,
            realStudentTag = if (isAdmin) authorTag else null,
            realStudentIdentifier = if (isAdmin) authorIdentifier else null,
            authorDisplayName = authorDisplayName,
            isAnonymous = isAnonymous,
            isMyQuestion = isMyQuestion,
            isHidden = isQuestionHidden,
            isThreadOpen = isThreadOpen,
            lastSequenceNumber = lastSequenceNumber,
            isAdminAuthor = authorIsAdmin,
            isDeleted = isQuestionDeleted,
            deletedAt = deletedAtStr,
            canDelete = !isQuestionDeleted && isMyQuestion && currentUserRole == UserRole.STUDENT,
            assignedProfessorId = assignedProfId.toString(),
            assignedProfessorName = assignedProfName,
            isAnsweredByAssignedProfessor = isAnsweredByAssigned,
            canAnswer = canAnswer,
            canFollowUp = canFollowUp,
            status = QuestionStatus.valueOf(qRow[QuestionsTable.status]),
            answers = answersList,
            attachments = attachmentDtos,
            createdAt = qRow[QuestionsTable.createdAt].toString()
        )
    }

    suspend fun setQuestionHidden(questionId: UUID, isHidden: Boolean, adminUserId: UUID): QuestionDetailDto = dbQuery {
        val exists = QuestionsTable.select { QuestionsTable.id eq questionId }.count() > 0
        if (!exists) throw IllegalArgumentException("질문을 찾을 수 없습니다.")

        QuestionsTable.update({ QuestionsTable.id eq questionId }) {
            it[QuestionsTable.isHidden] = isHidden
        }
        getQuestionDetailInternal(questionId, adminUserId)
    }

    suspend fun setThreadOpen(questionId: UUID, actorId: UUID, isThreadOpen: Boolean): QuestionDetailDto = dbQuery {
        val qRow = QuestionsTable.select { QuestionsTable.id eq questionId }.singleOrNull()
            ?: throw IllegalArgumentException("질문을 찾을 수 없습니다.")
        if (qRow[QuestionsTable.isDeletedByAuthor]) {
            throw IllegalStateException("작성자에 의해 삭제된 질문의 스레드 상태는 변경할 수 없습니다.")
        }
        val actorUser = UsersTable.select { UsersTable.id eq actorId }.singleOrNull()
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다.")
        val actorRole = runCatching { UserRole.valueOf(actorUser[UsersTable.role]) }.getOrDefault(UserRole.STUDENT)
        val isActorAdmin = actorUser[UsersTable.isAdmin] || actorRole == UserRole.ADMIN
        val isAssignedProf = qRow[QuestionsTable.assignedProfessorId] == actorId

        if (!isAssignedProf && !isActorAdmin) {
            throw IllegalStateException("담당교수 또는 관리자만 스레드 열림/닫힘 상태를 전환할 수 있습니다.")
        }

        QuestionsTable.update({ QuestionsTable.id eq questionId }) {
            it[QuestionsTable.isThreadOpen] = isThreadOpen
        }
        getQuestionDetailInternal(questionId, actorId)
    }

    suspend fun setAnswerHiddenByModerator(
        questionId: UUID,
        answerId: UUID,
        actorId: UUID,
        isHidden: Boolean
    ): AnswerDto = dbQuery {
        val qRow = QuestionsTable.select { QuestionsTable.id eq questionId }.singleOrNull()
            ?: throw IllegalArgumentException("질문을 찾을 수 없습니다.")
        val aRow = AnswersTable.innerJoin(UsersTable, { AnswersTable.professorId }, { UsersTable.id })
            .select { (AnswersTable.id eq answerId) and (AnswersTable.questionId eq questionId) }
            .singleOrNull() ?: throw IllegalArgumentException("답변을 찾을 수 없습니다.")
        val actorUser = UsersTable.select { UsersTable.id eq actorId }.singleOrNull()
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다.")

        val actorRole = runCatching { UserRole.valueOf(actorUser[UsersTable.role]) }.getOrDefault(UserRole.STUDENT)
        val isActorAdmin = actorUser[UsersTable.isAdmin] || actorRole == UserRole.ADMIN
        val isAssignedProf = qRow[QuestionsTable.assignedProfessorId] == actorId
        val answerAuthorRole = aRow[UsersTable.role]
        val answerAuthorIsAdmin = aRow[UsersTable.isAdmin] || answerAuthorRole == UserRole.ADMIN.name
        val answerAuthorId = aRow[AnswersTable.professorId]
        val questionStudentId = qRow[QuestionsTable.studentId]

        val isPeerStudentAnswer = answerAuthorRole == UserRole.STUDENT.name &&
            !answerAuthorIsAdmin &&
            answerAuthorId != questionStudentId
        val allowed = isActorAdmin || (isAssignedProf && isPeerStudentAnswer)
        if (!allowed) {
            throw IllegalStateException("답변 가리기 권한이 없습니다.")
        }

        val courseName = CoursesTable.select { CoursesTable.id eq qRow[QuestionsTable.courseId] }
            .singleOrNull()?.get(CoursesTable.name) ?: "강의"

        AnswersTable.update({ AnswersTable.id eq answerId }) {
            it[AnswersTable.isHidden] = isHidden
        }

        buildAnswerDto(
            answerId = answerId,
            questionId = questionId,
            questionStudentId = questionStudentId,
            authorUuid = answerAuthorId,
            authorSummary = AuthorSummary(
                name = aRow[UsersTable.name],
                role = answerAuthorRole,
                displayTag = aRow[UsersTable.displayTag],
                department = aRow[UsersTable.department],
                isAdmin = answerAuthorIsAdmin
            ),
            rawContent = aRow[AnswersTable.content],
            originalDraftContent = aRow[AnswersTable.rawContent],
            isAssignedProfessor = aRow[AnswersTable.isAssignedProfessor],
            isAnsHidden = isHidden,
            sequenceNumber = aRow[AnswersTable.sequenceNumber],
            createdAt = aRow[AnswersTable.createdAt].toString(),
            courseName = courseName,
            isQuestionAnonymous = qRow[QuestionsTable.isAnonymous],
            currentUserId = actorId,
            isMyQuestion = (questionStudentId == actorId),
            isAdminViewer = isActorAdmin,
            isProfessorViewer = (actorRole == UserRole.PROFESSOR),
            isAssignedProfViewer = isAssignedProf,
            isThreadOpen = qRow[QuestionsTable.isThreadOpen],
            isQuestionDeleted = qRow[QuestionsTable.isDeletedByAuthor],
            isQuestionHidden = qRow[QuestionsTable.isHidden],
            hideOriginalDraft = (actorRole == UserRole.PROFESSOR)
        )
    }

    suspend fun deleteAnswerByModerator(questionId: UUID, answerId: UUID, actorId: UUID): UUID = dbQuery {
        val qRow = QuestionsTable.select { QuestionsTable.id eq questionId }.singleOrNull()
            ?: throw IllegalArgumentException("질문을 찾을 수 없습니다.")
        val aRow = AnswersTable.innerJoin(UsersTable, { AnswersTable.professorId }, { UsersTable.id })
            .select { (AnswersTable.id eq answerId) and (AnswersTable.questionId eq questionId) }
            .singleOrNull() ?: throw IllegalArgumentException("답변을 찾을 수 없습니다.")
        val actorUser = UsersTable.select { UsersTable.id eq actorId }.singleOrNull()
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다.")

        val actorRole = runCatching { UserRole.valueOf(actorUser[UsersTable.role]) }.getOrDefault(UserRole.STUDENT)
        val isActorAdmin = actorUser[UsersTable.isAdmin] || actorRole == UserRole.ADMIN
        val isAssignedProf = qRow[QuestionsTable.assignedProfessorId] == actorId
        val answerAuthorRole = aRow[UsersTable.role]
        val answerAuthorIsAdmin = aRow[UsersTable.isAdmin] || answerAuthorRole == UserRole.ADMIN.name

        val isPeerStudentAnswer = answerAuthorRole == UserRole.STUDENT.name &&
            !answerAuthorIsAdmin &&
            aRow[AnswersTable.professorId] != qRow[QuestionsTable.studentId]
        val allowed = isActorAdmin || (isAssignedProf && isPeerStudentAnswer)
        if (!allowed) {
            throw IllegalStateException("답변 삭제 권한이 없습니다.")
        }

        deleteAnswerInternal(answerId)
    }

    suspend fun setAnswerHidden(answerId: UUID, isHidden: Boolean): AnswerDto = dbQuery {
        setAnswerHiddenInternal(answerId, isHidden)
    }

    private fun setAnswerHiddenInternal(answerId: UUID, isHidden: Boolean): AnswerDto {
        val aRow = AnswersTable.innerJoin(UsersTable, { AnswersTable.professorId }, { UsersTable.id })
            .select { AnswersTable.id eq answerId }
            .singleOrNull() ?: throw IllegalArgumentException("답변을 찾을 수 없습니다.")

        AnswersTable.update({ AnswersTable.id eq answerId }) {
            it[AnswersTable.isHidden] = isHidden
        }

        val authorRole = aRow[UsersTable.role]
        val isAdminAuthor = aRow[UsersTable.isAdmin] || authorRole == UserRole.ADMIN.name
        return AnswerDto(
            id = answerId.toString(),
            questionId = aRow[AnswersTable.questionId].toString(),
            professorId = aRow[AnswersTable.professorId].toString(),
            professorName = aRow[UsersTable.name],
            professorDepartment = aRow[UsersTable.department],
            authorRole = authorRole,
            authorTitle = if (isAdminAuthor) "관리자" else if (authorRole == UserRole.STUDENT.name) "질문자 추가 질문" else if (aRow[AnswersTable.isAssignedProfessor]) "담당교수" else "같은 과목 교수 (공동 답변)",
            content = aRow[AnswersTable.content],
            originalContent = "",
            isAssignedProfessor = aRow[AnswersTable.isAssignedProfessor],
            isHidden = isHidden,
            sequenceNumber = aRow[AnswersTable.sequenceNumber],
            isAdminAuthor = isAdminAuthor,
            createdAt = aRow[AnswersTable.createdAt].toString()
        )
    }

    suspend fun deleteQuestion(questionId: UUID): Boolean {
        val savedAttachmentNames = dbQuery {
            val exists = QuestionsTable.select { QuestionsTable.id eq questionId }.count() > 0
            if (!exists) throw IllegalArgumentException("질문을 찾을 수 없습니다.")

            val answerIds = AnswersTable.select { AnswersTable.questionId eq questionId }
                .map { it[AnswersTable.id].value }
            val attachmentNames = AttachmentsTable.select { AttachmentsTable.questionId eq questionId }
                .map { it[AttachmentsTable.savedName] }

            NotificationsTable.deleteWhere { NotificationsTable.referenceId eq questionId }
            if (answerIds.isNotEmpty()) {
                NotificationsTable.deleteWhere { NotificationsTable.referenceId inList answerIds }
            }
            AttachmentsTable.deleteWhere { AttachmentsTable.questionId eq questionId }
            AnswersTable.deleteWhere { AnswersTable.questionId eq questionId }
            QuestionsTable.deleteWhere { QuestionsTable.id eq questionId }
            attachmentNames
        }

        attachmentService?.deleteStoredFiles(savedAttachmentNames)
        return true
    }

    /**
     * Soft-deletes a question by its student author: hides it from students and similarity
     * search, purges stored attachments, blocks further replies, and notifies the assigned
     * professor while leaving a redacted tombstone visible to professors and admins.
     */
    suspend fun deleteQuestionByAuthor(questionId: UUID, currentUserId: UUID): Boolean {
        val result = dbQuery {
            val qRow = QuestionsTable.select { QuestionsTable.id eq questionId }.singleOrNull()
                ?: throw IllegalArgumentException("질문을 찾을 수 없습니다.")

            val currentUser = UsersTable.select { UsersTable.id eq currentUserId }.singleOrNull()
                ?: throw IllegalArgumentException("사용자 계정을 찾을 수 없습니다.")

            val role = runCatching { UserRole.valueOf(currentUser[UsersTable.role]) }.getOrDefault(UserRole.STUDENT)
            if (role != UserRole.STUDENT || qRow[QuestionsTable.studentId] != currentUserId) {
                throw IllegalStateException("질문을 작성한 학생 본인만 삭제할 수 있습니다.")
            }

            if (qRow[QuestionsTable.isDeletedByAuthor]) {
                throw IllegalArgumentException("이미 삭제된 질문입니다.")
            }

            val attachmentNames = AttachmentsTable.select { AttachmentsTable.questionId eq questionId }
                .map { it[AttachmentsTable.savedName] }
            AttachmentsTable.deleteWhere { AttachmentsTable.questionId eq questionId }

            QuestionsTable.update({ QuestionsTable.id eq questionId }) {
                it[isDeletedByAuthor] = true
                it[deletedAt] = Instant.now()
            }

            val courseName = CoursesTable.select { CoursesTable.id eq qRow[QuestionsTable.courseId] }
                .singleOrNull()?.get(CoursesTable.name) ?: "강의"
            val participatingProfIds = findParticipatingProfessorIds(questionId)

            AuthorDeleteResult(
                attachmentNames = attachmentNames,
                assignedProfId = qRow[QuestionsTable.assignedProfessorId],
                participatingProfIds = participatingProfIds,
                courseName = courseName,
                questionTitle = if (qRow[QuestionsTable.isHidden]) "관리자에 의해 가려진 질문입니다." else qRow[QuestionsTable.title]
            )
        }

        attachmentService?.deleteStoredFiles(result.attachmentNames)
        notificationService.notifyQuestionDeletedByAuthor(
            assignedProfessorId = result.assignedProfId,
            courseName = result.courseName,
            questionId = questionId,
            questionTitle = result.questionTitle,
            participatingProfessorIds = result.participatingProfIds
        )
        return true
    }

    suspend fun deleteAnswer(answerId: UUID): UUID = dbQuery {
        deleteAnswerInternal(answerId)
    }

    private fun deleteAnswerInternal(answerId: UUID): UUID {
        val aRow = AnswersTable.select { AnswersTable.id eq answerId }.singleOrNull()
            ?: throw IllegalArgumentException("답변을 찾을 수 없습니다.")
        val questionId = aRow[AnswersTable.questionId]

        NotificationsTable.deleteWhere { NotificationsTable.referenceId eq answerId }
        AnswersTable.deleteWhere { AnswersTable.id eq answerId }

        val remainingPosts = AnswersTable.innerJoin(UsersTable, { AnswersTable.professorId }, { UsersTable.id })
            .select { AnswersTable.questionId eq questionId }
            .orderBy(AnswersTable.sequenceNumber to SortOrder.ASC, AnswersTable.createdAt to SortOrder.ASC)
            .toList()
        val hasProfAnswer = remainingPosts.any { it[UsersTable.role] == UserRole.PROFESSOR.name || it[UsersTable.isAdmin] }
        val latestIsProf = remainingPosts.lastOrNull()?.let { it[UsersTable.role] == UserRole.PROFESSOR.name || it[UsersTable.isAdmin] } == true
        val nextStatus = if (hasProfAnswer && latestIsProf) QuestionStatus.ANSWERED else QuestionStatus.OPEN
        QuestionsTable.update({ QuestionsTable.id eq questionId }) {
            it[QuestionsTable.status] = nextStatus.name
        }
        return questionId
    }

    suspend fun updateQuestion(
        questionId: UUID,
        currentUserId: UUID,
        request: UpdateQuestionRequest
    ): QuestionDetailDto {
        val (detail, newVectorText) = dbQuery {
            val qRow = QuestionsTable.select { QuestionsTable.id eq questionId }.singleOrNull()
                ?: throw IllegalArgumentException("질문을 찾을 수 없습니다.")

            if (qRow[QuestionsTable.isDeletedByAuthor]) {
                throw IllegalStateException("삭제된 질문은 수정할 수 없습니다.")
            }
            if (qRow[QuestionsTable.isHidden]) {
                throw IllegalStateException("관리자에 의해 가려진 질문은 수정할 수 없습니다.")
            }

            val authorId = qRow[QuestionsTable.studentId]
            if (authorId != currentUserId) {
                throw IllegalStateException("질문 작성자만 수정할 수 있습니다.")
            }

            val authorUser = UsersTable.select { UsersTable.id eq currentUserId }.singleOrNull()
                ?: throw IllegalStateException("유효한 사용자 계정을 찾을 수 없습니다.")
            if (authorUser[UsersTable.isWithdrawn]) {
                throw IllegalStateException("탈퇴 처리된 계정입니다. 질문을 수정할 수 없습니다.")
            }
            val banUntil = authorUser[UsersTable.questionBanUntil]
            if (banUntil != null && banUntil.isAfter(Instant.now())) {
                val formattedUntil = banDateFormatter.format(banUntil)
                throw IllegalStateException("관리자에 의해 ${formattedUntil}까지 질문 수정이 제한된 계정입니다.")
            }

            val currentCategory = QuestionCategory.valueOf(qRow[QuestionsTable.category])
            val effectiveCategory = request.category ?: currentCategory
            val effectiveAnonymous = request.isAnonymous ?: qRow[QuestionsTable.isAnonymous]
            if (effectiveCategory == QuestionCategory.GRADE && effectiveAnonymous) {
                throw IllegalArgumentException("성적 카테고리 질문은 익명으로 작성할 수 없습니다.")
            }
            if (request.category != null && request.category != currentCategory) {
                val categorySetting = ProfessorCategorySettingsTable.select {
                    (ProfessorCategorySettingsTable.professorId eq qRow[QuestionsTable.assignedProfessorId]) and
                        (ProfessorCategorySettingsTable.courseId eq qRow[QuestionsTable.courseId]) and
                        (ProfessorCategorySettingsTable.category eq effectiveCategory.name)
                }.singleOrNull()
                if (categorySetting != null && !categorySetting[ProfessorCategorySettingsTable.isEnabled]) {
                    throw IllegalStateException("담당 교수님에 의해 '${effectiveCategory}' 카테고리 질문 접수가 비활성화되어 있습니다.")
                }
            }

            val cleanUpdatedTitle = request.title?.trim()?.takeIf { it.isNotBlank() }?.also { v ->
                if (v.length > MAX_TITLE_LENGTH) {
                    throw IllegalArgumentException("제목은 최대 ${MAX_TITLE_LENGTH}자까지 입력할 수 있습니다.")
                }
            }
            validateContentLength(request.rawContent, "질문 원문")
            validateContentLength(request.originalContent, "질문 원문")
            validateContentLength(request.refinedContent, "질문 본문")
            validateContentLength(request.content, "질문 본문")

            QuestionsTable.update({ QuestionsTable.id eq questionId }) {
                cleanUpdatedTitle?.let { v -> it[title] = v }
                request.category?.let { v -> it[category] = v.name }
                request.isAnonymous?.let { v -> it[isAnonymous] = v }
                request.rawContent?.takeIf { it.isNotBlank() }?.let { v -> it[rawContent] = v }
                request.originalContent?.takeIf { it.isNotBlank() }?.let { v -> it[rawContent] = v }
                request.refinedContent?.takeIf { it.isNotBlank() }?.let { v -> it[refinedContent] = v }
                request.content?.takeIf { it.isNotBlank() }?.let { v -> it[refinedContent] = v }
            }

            val updatedRow = QuestionsTable.select { QuestionsTable.id eq questionId }.single()
            val newText = "${updatedRow[QuestionsTable.title]} ${updatedRow[QuestionsTable.refinedContent]}"
            getQuestionDetailInternal(questionId, currentUserId) to newText
        }

        // Recompute vector asynchronously on dedicated similarity worker thread pool
        workerManager.launchSimilarityTask("reindex-question-$questionId") {
            val newVector = similarityService.computeVector(newVectorText)
            val vectorJson = similarityService.serializeVector(newVector)
            dbQuery(workerManager.similarityDispatcher) {
                QuestionsTable.update({ QuestionsTable.id eq questionId }) {
                    it[vectorData] = vectorJson
                }
            }
        }

        return detail
    }

    suspend fun createAnswer(
        questionId: UUID,
        professorId: UUID,
        request: CreateAnswerRequest
    ): AnswerDto {
        validateContentLength(request.content, "답변 내용")
        val created = dbQueryWithRetry {
            val qRow = QuestionsTable.select { QuestionsTable.id eq questionId }.singleOrNull()
                ?: throw IllegalArgumentException("질문을 찾을 수 없습니다.")

            if (qRow[QuestionsTable.isDeletedByAuthor]) {
                throw IllegalStateException("작성자에 의해 삭제된 질문에는 답변을 등록할 수 없습니다.")
            }

            val profUser = UsersTable.select { UsersTable.id eq professorId }.singleOrNull()
                ?: throw IllegalArgumentException("교수 계정을 찾을 수 없습니다.")

            if (profUser[UsersTable.isWithdrawn]) {
                throw IllegalStateException("탈퇴 처리된 계정입니다. 답변을 작성할 수 없습니다.")
            }
            val answerBanUntil = profUser[UsersTable.answerBanUntil]
            if (answerBanUntil != null && answerBanUntil.isAfter(Instant.now())) {
                val formattedUntil = banDateFormatter.format(answerBanUntil)
                throw IllegalStateException("관리자에 의해 ${formattedUntil}까지 답변 작성이 제한된 계정입니다.")
            }

            val role = runCatching { UserRole.valueOf(profUser[UsersTable.role]) }.getOrDefault(UserRole.STUDENT)
            val isActorAdmin = profUser[UsersTable.isAdmin] || role == UserRole.ADMIN
            if (qRow[QuestionsTable.isHidden] && !isActorAdmin) {
                throw IllegalStateException("관리자에 의해 가려진 질문에는 답변을 등록할 수 없습니다.")
            }
            val isThreadOpen = qRow[QuestionsTable.isThreadOpen]
            val questionStudentId = qRow[QuestionsTable.studentId]

            val courseId = qRow[QuestionsTable.courseId]
            val assignedProfId = qRow[QuestionsTable.assignedProfessorId]
            val category = QuestionCategory.valueOf(qRow[QuestionsTable.category])

            val existingAnswers = AnswersTable.innerJoin(UsersTable, { AnswersTable.professorId }, { UsersTable.id })
                .select { AnswersTable.questionId eq questionId }
                .toList()
            val isAssignedProfAnswered = existingAnswers.any {
                it[AnswersTable.isAssignedProfessor] && it[UsersTable.role] == UserRole.PROFESSOR.name
            }

            val canAnswer = calculateCanAnswer(
                currentUserRole = role,
                isAdmin = isActorAdmin,
                currentUserId = professorId,
                questionStudentId = questionStudentId,
                assignedProfessorId = assignedProfId,
                courseId = courseId,
                category = category,
                isThreadOpen = isThreadOpen,
                isAnsweredByAssignedProfessor = isAssignedProfAnswered
            )

            if (!canAnswer) {
                if (role == UserRole.STUDENT && !isThreadOpen) {
                    throw IllegalStateException("스레드가 닫힘 상태여서 다른 학생은 답변을 작성할 수 없습니다.")
                } else if (role == UserRole.PROFESSOR && professorId != assignedProfId && !isThreadOpen) {
                    throw IllegalStateException("스레드가 닫힘(질문자, 담당교수 외 닫힘) 상태여서 공동 교수가 답변할 수 없습니다.")
                } else if (isAssignedProfAnswered && professorId != assignedProfId) {
                    throw IllegalStateException("질문자의 담당교수가 이미 답변을 완료하여 추가 답변이 제한됩니다.")
                } else {
                    throw IllegalStateException("답변 권한이 없습니다.")
                }
            }

            val isAssigned = (role == UserRole.PROFESSOR && professorId == assignedProfId)
            val nextSeq = maxOf(qRow[QuestionsTable.lastSequenceNumber], 1) + 1
            val ansId = UUID.randomUUID()
            val now = Instant.now()

            AnswersTable.insert {
                it[id] = ansId
                it[AnswersTable.questionId] = questionId
                it[AnswersTable.professorId] = professorId
                it[sequenceNumber] = nextSeq
                it[content] = request.content
                it[isAssignedProfessor] = isAssigned
                it[isHidden] = false
                it[createdAt] = now
            }

            QuestionsTable.update({ QuestionsTable.id eq questionId }) {
                it[lastSequenceNumber] = nextSeq
                if (role == UserRole.PROFESSOR || isActorAdmin) {
                    it[status] = QuestionStatus.ANSWERED.name
                }
            }

            val course = CoursesTable.select { CoursesTable.id eq courseId }.single()
            val courseName = course[CoursesTable.name]
            val builtDto = buildAnswerDto(
                answerId = ansId,
                questionId = questionId,
                questionStudentId = questionStudentId,
                authorUuid = professorId,
                authorSummary = AuthorSummary(
                    name = profUser[UsersTable.name],
                    role = role.name,
                    displayTag = profUser[UsersTable.displayTag],
                    department = profUser[UsersTable.department],
                    isAdmin = isActorAdmin
                ),
                rawContent = request.content,
                originalDraftContent = "",
                isAssignedProfessor = isAssigned,
                isAnsHidden = false,
                sequenceNumber = nextSeq,
                createdAt = now.toString(),
                courseName = courseName,
                isQuestionAnonymous = qRow[QuestionsTable.isAnonymous],
                currentUserId = professorId,
                isMyQuestion = (questionStudentId == professorId),
                isAdminViewer = isActorAdmin,
                isProfessorViewer = (role == UserRole.PROFESSOR),
                isAssignedProfViewer = isAssigned,
                isThreadOpen = isThreadOpen,
                isQuestionDeleted = false,
                hideOriginalDraft = (role == UserRole.PROFESSOR)
            )

            val participatingProfIds = findParticipatingProfessorIds(questionId)
            val answerAuthorDisplay = when {
                isActorAdmin && role != UserRole.PROFESSOR -> "관리자 (${profUser[UsersTable.name]})"
                role == UserRole.STUDENT -> "다른 수강생"
                else -> profUser[UsersTable.name]
            }

            CreatedAnswerResult(
                dto = builtDto,
                studentId = questionStudentId,
                assignedProfId = assignedProfId,
                participatingProfIds = participatingProfIds,
                courseName = courseName,
                questionTitle = qRow[QuestionsTable.title],
                professorName = answerAuthorDisplay,
                isProfessorAuthor = (role == UserRole.PROFESSOR)
            )
        }

        // Dispatch notifications on dedicated notification worker pool outside DB transaction
        notificationService.notifyNewAnswer(
            studentId = created.studentId,
            courseName = created.courseName,
            questionId = questionId,
            questionTitle = created.questionTitle,
            professorName = created.professorName,
            isProfessorAuthor = created.isProfessorAuthor,
            actorId = professorId,
            assignedProfessorId = created.assignedProfId,
            participatingProfessorIds = created.participatingProfIds
        )

        return created.dto
    }

    suspend fun createFollowUp(
        questionId: UUID,
        studentId: UUID,
        request: CreateFollowUpRequest
    ): AnswerDto {
        val cleanContent = request.content.trim()
        if (cleanContent.isBlank()) {
            throw IllegalArgumentException("추가 질문 내용을 입력해 주세요.")
        }
        validateContentLength(cleanContent, "추가 질문 내용")
        request.originalContent?.trim()?.takeIf { it.isNotBlank() }?.let {
            validateContentLength(it, "추가 질문 원문")
        }

        val created = dbQueryWithRetry {
            val qRow = QuestionsTable.select { QuestionsTable.id eq questionId }.singleOrNull()
                ?: throw IllegalArgumentException("질문을 찾을 수 없습니다.")

            if (qRow[QuestionsTable.isDeletedByAuthor]) {
                throw IllegalStateException("작성자에 의해 삭제된 질문에는 추가 질문을 작성할 수 없습니다.")
            }
            if (qRow[QuestionsTable.isHidden]) {
                throw IllegalStateException("가려진 질문에는 추가 질문을 작성할 수 없습니다.")
            }

            val studentUser = UsersTable.select { UsersTable.id eq studentId }.singleOrNull()
                ?: throw IllegalArgumentException("학생 계정을 찾을 수 없습니다.")

            if (studentUser[UsersTable.isWithdrawn]) {
                throw IllegalStateException("탈퇴 처리된 계정입니다. 추가 질문을 작성할 수 없습니다.")
            }
            val banUntil = studentUser[UsersTable.questionBanUntil]
            if (banUntil != null && banUntil.isAfter(Instant.now())) {
                val formattedUntil = banDateFormatter.format(banUntil)
                throw IllegalStateException("관리자에 의해 ${formattedUntil}까지 질문 작성이 제한된 계정입니다.")
            }

            val role = runCatching { UserRole.valueOf(studentUser[UsersTable.role]) }.getOrDefault(UserRole.STUDENT)
            if (role != UserRole.STUDENT || qRow[QuestionsTable.studentId] != studentId) {
                throw IllegalStateException("질문을 작성한 학생 본인만 추가 질문을 남길 수 있습니다.")
            }

            val hasProfAnswer = AnswersTable.innerJoin(UsersTable, { AnswersTable.professorId }, { UsersTable.id })
                .select {
                    (AnswersTable.questionId eq questionId) and
                        (AnswersTable.isHidden eq false) and
                        ((UsersTable.role eq UserRole.PROFESSOR.name) or (UsersTable.isAdmin eq true))
                }.count() > 0
            if (!hasProfAnswer) {
                throw IllegalStateException("교수님의 답변이 등록된 후 추가 질문을 작성할 수 있습니다.")
            }

            val courseId = qRow[QuestionsTable.courseId]
            val course = CoursesTable.select { CoursesTable.id eq courseId }.single()
            val courseName = course[CoursesTable.name]
            val followUpId = UUID.randomUUID()
            val nextSeq = maxOf(qRow[QuestionsTable.lastSequenceNumber], 1) + 1
            val now = Instant.now()
            val originalDraft = request.originalContent?.trim()?.takeIf { it.isNotBlank() } ?: cleanContent

            AnswersTable.insert {
                it[id] = followUpId
                it[AnswersTable.questionId] = questionId
                it[professorId] = studentId
                it[sequenceNumber] = nextSeq
                it[rawContent] = originalDraft
                it[content] = cleanContent
                it[isAssignedProfessor] = false
                it[isHidden] = false
                it[createdAt] = now
            }

            QuestionsTable.update({ QuestionsTable.id eq questionId }) {
                it[status] = QuestionStatus.OPEN.name
                it[lastSequenceNumber] = nextSeq
            }

            val isAnonymous = qRow[QuestionsTable.isAnonymous]
            val authorDisplay = if (isAnonymous) "$courseName 수강생" else studentUser[UsersTable.displayTag]
            val dto = buildAnswerDto(
                answerId = followUpId,
                questionId = questionId,
                questionStudentId = qRow[QuestionsTable.studentId],
                authorUuid = studentId,
                authorSummary = AuthorSummary(
                    name = studentUser[UsersTable.name],
                    role = UserRole.STUDENT.name,
                    displayTag = studentUser[UsersTable.displayTag],
                    department = studentUser[UsersTable.department],
                    isAdmin = studentUser[UsersTable.isAdmin]
                ),
                rawContent = cleanContent,
                originalDraftContent = originalDraft,
                isAssignedProfessor = false,
                isAnsHidden = false,
                sequenceNumber = nextSeq,
                createdAt = now.toString(),
                courseName = courseName,
                isQuestionAnonymous = isAnonymous,
                currentUserId = studentId,
                isMyQuestion = true,
                isAdminViewer = studentUser[UsersTable.isAdmin]
            )

            val participatingProfIds = findParticipatingProfessorIds(questionId)

            CreatedFollowUpResult(
                dto = dto,
                assignedProfId = qRow[QuestionsTable.assignedProfessorId],
                participatingProfIds = participatingProfIds,
                courseName = courseName,
                questionTitle = qRow[QuestionsTable.title],
                category = QuestionCategory.valueOf(qRow[QuestionsTable.category]),
                authorDisplay = authorDisplay
            )
        }

        notificationService.notifyFollowUpQuestion(
            assignedProfessorId = created.assignedProfId,
            courseName = created.courseName,
            questionId = questionId,
            questionTitle = created.questionTitle,
            category = created.category,
            followUpContent = cleanContent,
            authorDisplay = created.authorDisplay,
            participatingProfessorIds = created.participatingProfIds
        )

        return created.dto
    }

    suspend fun getSimilarQuestions(
        courseId: UUID,
        category: QuestionCategory,
        queryText: String,
        excludeQuestionId: UUID? = null,
        limit: Int = 3
    ): List<SimilarQuestionDto> {
        if (category == QuestionCategory.GRADE) {
            return emptyList()
        }

        val candidates = dbQuery {
            val questions = QuestionsTable.select {
                (QuestionsTable.courseId eq courseId) and
                        (QuestionsTable.category eq category.name) and
                        (QuestionsTable.isHidden eq false) and
                        (QuestionsTable.isDeletedByAuthor eq false)
            }.toList()

            val profNames = UsersTable.select { UsersTable.role eq UserRole.PROFESSOR.name }
                .associate { it[UsersTable.id].value to it[UsersTable.name] }

            questions.mapNotNull { row ->
                val qId = row[QuestionsTable.id].value
                if (excludeQuestionId != null && qId == excludeQuestionId) return@mapNotNull null

                val answers = AnswersTable.innerJoin(UsersTable, { AnswersTable.professorId }, { UsersTable.id })
                    .select {
                        (AnswersTable.questionId eq qId) and
                            (AnswersTable.isHidden eq false) and
                            (UsersTable.role eq UserRole.PROFESSOR.name)
                    }
                    .orderBy(AnswersTable.createdAt to SortOrder.ASC)
                    .map { aRow ->
                        val pId = aRow[AnswersTable.professorId]
                        val pName = profNames[pId] ?: "담당교수"
                        AnswerDto(
                            id = aRow[AnswersTable.id].value.toString(),
                            questionId = qId.toString(),
                            professorId = pId.toString(),
                            professorName = pName,
                            professorDepartment = "",
                            authorRole = UserRole.PROFESSOR.name,
                            content = aRow[AnswersTable.content],
                            isAssignedProfessor = aRow[AnswersTable.isAssignedProfessor],
                            isHidden = false,
                            createdAt = aRow[AnswersTable.createdAt].toString()
                        )
                    }

                SimilarQuestionCandidate(
                    questionId = qId,
                    title = row[QuestionsTable.title],
                    refinedContent = row[QuestionsTable.refinedContent],
                    answerDtos = answers
                )
            }
        }

        // Run similarity computation and ranking on dedicated similarity worker thread pool
        val boundedQuery = queryText.take(2000)
        return withContext(workerManager.similarityDispatcher) {
            val results = mutableListOf<SimilarQuestionDto>()
            for (cand in candidates) {
                val docText = "${cand.title} ${cand.refinedContent}"
                val sim = similarityService.computeTextSimilarity(boundedQuery, docText)
                val simScore = (sim * 100).toInt()
                if (simScore < 25) continue

                val topAnswer = cand.answerDtos.firstOrNull()?.content
                val snippet = cand.refinedContent.take(100)

                results.add(
                    SimilarQuestionDto(
                        id = cand.questionId.toString(),
                        title = cand.title,
                        snippet = snippet,
                        content = snippet,
                        category = category,
                        similarity = sim,
                        similarityScore = simScore,
                        answerCount = cand.answerDtos.size,
                        topAnswerSnippet = topAnswer?.take(150),
                        answers = cand.answerDtos
                    )
                )
            }
            results.sortedByDescending { it.similarity }.take(limit)
        }
    }

    private fun generateSummaryTitle(content: String): String {
        val clean = content.trim()
        if (clean.isBlank()) return "학업 질문"
        val firstLine = clean.lines().firstOrNull { it.isNotBlank() } ?: clean
        val stripped = firstLine.replace(Regex("^[#*\\-•\\d.]+\\s*"), "").replace(Regex("[\\[\\]]"), "").trim()
        val sentenceMatch = Regex("^(.+?[.!?])(\\s|$)").find(stripped)
        if (sentenceMatch != null && sentenceMatch.groupValues[1].length in 10..45) {
            return sentenceMatch.groupValues[1]
        }
        return if (stripped.length <= 45) stripped.ifBlank { "학업 질문" } else stripped.take(45) + "..."
    }

    private fun calculateCanAnswer(
        currentUserRole: UserRole,
        isAdmin: Boolean,
        currentUserId: UUID,
        questionStudentId: UUID,
        assignedProfessorId: UUID,
        courseId: UUID,
        category: QuestionCategory,
        isThreadOpen: Boolean,
        isAnsweredByAssignedProfessor: Boolean
    ): Boolean {
        if (isAdmin) return true
        if (category == QuestionCategory.GRADE) {
            return currentUserRole == UserRole.PROFESSOR && currentUserId == assignedProfessorId
        }

        if (currentUserRole == UserRole.STUDENT) {
            // In R4: when thread is open (열림), students other than original question author can answer
            return isThreadOpen && currentUserId != questionStudentId
        }

        if (currentUserRole != UserRole.PROFESSOR) return false

        val teachesCourse = CourseProfessorsTable.select {
            (CourseProfessorsTable.courseId eq courseId) and (CourseProfessorsTable.professorId eq currentUserId)
        }.count() > 0

        if (!teachesCourse) return false

        if (currentUserId == assignedProfessorId) return true

        // Same-course co-professor answering requires thread to be open (열림) and assigned professor not answered yet
        return isThreadOpen && !isAnsweredByAssignedProfessor
    }

    private fun buildAnswerDto(
        answerId: UUID,
        questionId: UUID,
        questionStudentId: UUID,
        authorUuid: UUID,
        authorSummary: AuthorSummary,
        rawContent: String,
        originalDraftContent: String = "",
        isAssignedProfessor: Boolean,
        isAnsHidden: Boolean,
        sequenceNumber: Int = 2,
        createdAt: String,
        courseName: String,
        isQuestionAnonymous: Boolean,
        currentUserId: UUID? = null,
        isMyQuestion: Boolean,
        isAdminViewer: Boolean,
        isProfessorViewer: Boolean = false,
        isAssignedProfViewer: Boolean = false,
        isThreadOpen: Boolean = false,
        isQuestionDeleted: Boolean = false,
        isQuestionHidden: Boolean = false,
        hideOriginalDraft: Boolean = false
    ): AnswerDto {
        val isStudentPost = authorSummary.role == UserRole.STUDENT.name && !authorSummary.isAdmin
        val isOriginalAuthorFollowUp = isStudentPost && authorUuid == questionStudentId
        val isPeerStudentAnswer = isStudentPost && authorUuid != questionStudentId
        val isMyPost = (currentUserId != null && currentUserId == authorUuid)

        val maskedId = if (isStudentPost && !isMyPost && !isAdminViewer && (isPeerStudentAnswer || isQuestionAnonymous)) {
            "anonymous"
        } else {
            authorUuid.toString()
        }
        val displayName = when {
            authorSummary.isAdmin && authorSummary.role != UserRole.PROFESSOR.name -> authorSummary.displayTag.ifBlank { authorSummary.name }
            !isStudentPost -> authorSummary.name
            isMyPost -> "${authorSummary.displayTag} (나)"
            isPeerStudentAnswer -> if (isAdminViewer || isProfessorViewer) authorSummary.displayTag else "익명 학생"
            isQuestionAnonymous && !isMyQuestion && !isAdminViewer -> "$courseName 수강생"
            else -> authorSummary.displayTag
        }
        val authorTitle = when {
            authorSummary.isAdmin -> "관리자"
            isOriginalAuthorFollowUp -> "질문자 추가 질문"
            isPeerStudentAnswer -> "학생 답변"
            isAssignedProfessor -> "담당교수"
            else -> "같은 과목 교수 (공동 답변)"
        }
        val canModerate = isAdminViewer || (isAssignedProfViewer && isPeerStudentAnswer)
        val maskedByDeletion = isQuestionDeleted && isOriginalAuthorFollowUp
        val maskedByParentHidden = isQuestionHidden && !isAdminViewer && isOriginalAuthorFollowUp
        val safeContent = when {
            maskedByDeletion -> "작성자에 의해 삭제된 내용입니다."
            maskedByParentHidden || (isAnsHidden && !isAdminViewer) ->
                if (isOriginalAuthorFollowUp) "관리자에 의해 가려진 질문입니다." else "관리자에 의해 가려진 답변입니다."
            else -> rawContent
        }
        val safeOriginalContent = when {
            !isOriginalAuthorFollowUp -> ""
            hideOriginalDraft -> ""
            !isMyPost && !isAdminViewer -> ""
            maskedByDeletion -> ""
            maskedByParentHidden || (isAnsHidden && !isAdminViewer) -> ""
            else -> originalDraftContent
        }

        return AnswerDto(
            id = answerId.toString(),
            questionId = questionId.toString(),
            professorId = maskedId,
            professorName = displayName,
            professorDepartment = if (isStudentPost) "" else authorSummary.department,
            authorId = maskedId,
            authorName = displayName,
            authorRole = authorSummary.role,
            authorTitle = authorTitle,
            isPrimaryProf = !isStudentPost && isAssignedProfessor,
            content = safeContent,
            originalContent = safeOriginalContent,
            isAssignedProfessor = !isStudentPost && isAssignedProfessor,
            isHidden = isAnsHidden,
            sequenceNumber = sequenceNumber,
            isAdminAuthor = authorSummary.isAdmin,
            canModerate = canModerate,
            createdAt = createdAt
        )
    }
}
