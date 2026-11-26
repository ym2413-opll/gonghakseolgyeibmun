package com.gonghak.service

import com.gonghak.config.DatabaseFactory
import com.gonghak.dto.AdminTrashItemDto
import com.gonghak.models.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

object AdminTrashService {
    const val RETENTION_DAYS: Long = 30L
    const val TYPE_COURSE = "COURSE"
    const val TYPE_QUESTION = "QUESTION"
    const val TYPE_ANSWER = "ANSWER"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class TrashAttachmentRecord(
        val id: String,
        val questionId: String? = null,
        val answerId: String? = null,
        val uploaderId: String? = null,
        val originalName: String,
        val savedName: String,
        val fileSize: Long,
        val originalSize: Long,
        val mimeType: String,
        val isImage: Boolean,
        val isCompressed: Boolean,
        val compressionRatio: Double,
        val createdAt: String
    )

    @Serializable
    data class TrashAnswerSnapshot(
        val id: String,
        val questionId: String,
        val professorId: String,
        val sequenceNumber: Int,
        val rawContent: String,
        val content: String,
        val isAssignedProfessor: Boolean,
        val isHidden: Boolean,
        val createdAt: String,
        val attachments: List<TrashAttachmentRecord> = emptyList()
    )

    @Serializable
    data class TrashQuestionSnapshot(
        val id: String,
        val courseId: String,
        val studentId: String,
        val assignedProfessorId: String,
        val courseProfessorId: String? = null,
        val createdSemester: String = "",
        val category: String,
        val title: String,
        val rawContent: String,
        val refinedContent: String,
        val isAnonymous: Boolean,
        val isHidden: Boolean,
        val isThreadOpen: Boolean,
        val lastSequenceNumber: Int,
        val isDeletedByAuthor: Boolean,
        val deletedAt: String? = null,
        val vectorData: String,
        val status: String,
        val createdAt: String,
        val attachments: List<TrashAttachmentRecord> = emptyList(),
        val answers: List<TrashAnswerSnapshot> = emptyList()
    )

    @Serializable
    data class TrashCourseProfRecord(
        val id: String,
        val professorId: String,
        val section: String,
        val schedule: String
    )

    @Serializable
    data class TrashEnrollmentRecord(
        val id: String,
        val studentId: String,
        val assignedProfessorId: String? = null,
        val courseProfessorId: String? = null
    )

    @Serializable
    data class TrashCourseSnapshot(
        val id: String,
        val name: String,
        val code: String,
        val semester: String,
        val college: String,
        val department: String,
        val major: String,
        val completionType: String,
        val credit: Int,
        val aliases: List<String> = emptyList(),
        val professors: List<TrashCourseProfRecord> = emptyList(),
        val enrollments: List<TrashEnrollmentRecord> = emptyList(),
        val questions: List<TrashQuestionSnapshot> = emptyList()
    )

    private fun toAttachmentRecord(row: ResultRow) = TrashAttachmentRecord(
        id = row[AttachmentsTable.id].value.toString(),
        questionId = row[AttachmentsTable.questionId]?.toString(),
        answerId = row[AttachmentsTable.answerId]?.toString(),
        uploaderId = row[AttachmentsTable.uploaderId]?.toString(),
        originalName = row[AttachmentsTable.originalName],
        savedName = row[AttachmentsTable.savedName],
        fileSize = row[AttachmentsTable.fileSize],
        originalSize = row[AttachmentsTable.originalSize],
        mimeType = row[AttachmentsTable.mimeType],
        isImage = row[AttachmentsTable.isImage],
        isCompressed = row[AttachmentsTable.isCompressed],
        compressionRatio = row[AttachmentsTable.compressionRatio],
        createdAt = row[AttachmentsTable.createdAt].toString()
    )

    private fun buildAnswerSnapshot(aRow: ResultRow): TrashAnswerSnapshot {
        val aId = aRow[AnswersTable.id].value
        val atts = AttachmentsTable.select { AttachmentsTable.answerId eq aId }.map(::toAttachmentRecord)
        return TrashAnswerSnapshot(
            id = aId.toString(),
            questionId = aRow[AnswersTable.questionId].toString(),
            professorId = aRow[AnswersTable.professorId].toString(),
            sequenceNumber = aRow[AnswersTable.sequenceNumber],
            rawContent = aRow[AnswersTable.rawContent],
            content = aRow[AnswersTable.content],
            isAssignedProfessor = aRow[AnswersTable.isAssignedProfessor],
            isHidden = aRow[AnswersTable.isHidden],
            createdAt = aRow[AnswersTable.createdAt].toString(),
            attachments = atts
        )
    }

    private fun buildQuestionSnapshot(qRow: ResultRow): TrashQuestionSnapshot {
        val qId = qRow[QuestionsTable.id].value
        val qAtts = AttachmentsTable.select {
            (AttachmentsTable.questionId eq qId) and AttachmentsTable.answerId.isNull()
        }.map(::toAttachmentRecord)
        val answers = AnswersTable.select { AnswersTable.questionId eq qId }
            .orderBy(AnswersTable.sequenceNumber to SortOrder.ASC, AnswersTable.createdAt to SortOrder.ASC)
            .map(::buildAnswerSnapshot)
        return TrashQuestionSnapshot(
            id = qId.toString(),
            courseId = qRow[QuestionsTable.courseId].toString(),
            studentId = qRow[QuestionsTable.studentId].toString(),
            assignedProfessorId = qRow[QuestionsTable.assignedProfessorId].toString(),
            courseProfessorId = qRow[QuestionsTable.courseProfessorId]?.toString(),
            createdSemester = qRow[QuestionsTable.createdSemester],
            category = qRow[QuestionsTable.category],
            title = qRow[QuestionsTable.title],
            rawContent = qRow[QuestionsTable.rawContent],
            refinedContent = qRow[QuestionsTable.refinedContent],
            isAnonymous = qRow[QuestionsTable.isAnonymous],
            isHidden = qRow[QuestionsTable.isHidden],
            isThreadOpen = qRow[QuestionsTable.isThreadOpen],
            lastSequenceNumber = qRow[QuestionsTable.lastSequenceNumber],
            isDeletedByAuthor = qRow[QuestionsTable.isDeletedByAuthor],
            deletedAt = qRow[QuestionsTable.deletedAt]?.toString(),
            vectorData = qRow[QuestionsTable.vectorData],
            status = qRow[QuestionsTable.status],
            createdAt = qRow[QuestionsTable.createdAt].toString(),
            attachments = qAtts,
            answers = answers
        )
    }

    internal fun archiveCourseInternal(courseId: UUID, deletedBy: UUID? = null): UUID {
        val row = CoursesTable.select { CoursesTable.id eq courseId }.singleOrNull()
            ?: throw IllegalArgumentException("존재하지 않는 과목입니다.")
        val aliases = DatabaseFactory.traverseCourseAliasesInternal(courseId)
        val profs = CourseProfessorsTable.select { CourseProfessorsTable.courseId eq courseId }.map {
            TrashCourseProfRecord(
                id = it[CourseProfessorsTable.id].value.toString(),
                professorId = it[CourseProfessorsTable.professorId].toString(),
                section = it[CourseProfessorsTable.section],
                schedule = it[CourseProfessorsTable.schedule]
            )
        }
        val enrolls = EnrollmentsTable.select { EnrollmentsTable.courseId eq courseId }.map {
            TrashEnrollmentRecord(
                id = it[EnrollmentsTable.id].value.toString(),
                studentId = it[EnrollmentsTable.studentId].toString(),
                assignedProfessorId = it[EnrollmentsTable.assignedProfessorId]?.toString(),
                courseProfessorId = it[EnrollmentsTable.courseProfessorId]?.toString()
            )
        }
        val questions = QuestionsTable.select { QuestionsTable.courseId eq courseId }.map(::buildQuestionSnapshot)

        val snapshot = TrashCourseSnapshot(
            id = courseId.toString(),
            name = row[CoursesTable.name],
            code = row[CoursesTable.code],
            semester = row[CoursesTable.semester],
            college = row[CoursesTable.college],
            department = row[CoursesTable.department],
            major = row[CoursesTable.major],
            completionType = row[CoursesTable.completionType],
            credit = row[CoursesTable.credit],
            aliases = aliases,
            professors = profs,
            enrollments = enrolls,
            questions = questions
        )
        val now = Instant.now()
        val trashId = UUID.randomUUID()
        AdminTrashBinTable.insert {
            it[id] = trashId
            it[entityType] = TYPE_COURSE
            it[entityId] = courseId
            it[title] = "${snapshot.name} (${snapshot.code})".take(200)
            it[summary] = "[${snapshot.semester}] ${snapshot.department.ifBlank { snapshot.college }} · 분반 ${profs.size}개 · 질문 ${questions.size}건".take(300)
            it[snapshotJson] = json.encodeToString(snapshot)
            it[AdminTrashBinTable.deletedBy] = deletedBy
            it[deletedAt] = now
            it[expiresAt] = now.plus(RETENTION_DAYS, ChronoUnit.DAYS)
            it[restoredAt] = null
        }
        return trashId
    }

    internal fun archiveQuestionInternal(questionId: UUID, deletedBy: UUID? = null): UUID {
        val qRow = QuestionsTable.select { QuestionsTable.id eq questionId }.singleOrNull()
            ?: throw IllegalArgumentException("질문을 찾을 수 없습니다.")
        val snapshot = buildQuestionSnapshot(qRow)
        val courseName = CoursesTable.select { CoursesTable.id eq qRow[QuestionsTable.courseId] }
            .singleOrNull()?.get(CoursesTable.name) ?: "과목"
        val now = Instant.now()
        val trashId = UUID.randomUUID()
        AdminTrashBinTable.insert {
            it[id] = trashId
            it[entityType] = TYPE_QUESTION
            it[entityId] = questionId
            it[title] = snapshot.title.take(200)
            it[summary] = "$courseName · 답변 ${snapshot.answers.size}건 · ${snapshot.category}".take(300)
            it[snapshotJson] = json.encodeToString(snapshot)
            it[AdminTrashBinTable.deletedBy] = deletedBy
            it[deletedAt] = now
            it[expiresAt] = now.plus(RETENTION_DAYS, ChronoUnit.DAYS)
            it[restoredAt] = null
        }
        return trashId
    }

    internal fun archiveAnswerInternal(answerId: UUID, deletedBy: UUID? = null): UUID {
        val aRow = AnswersTable.select { AnswersTable.id eq answerId }.singleOrNull()
            ?: throw IllegalArgumentException("답변을 찾을 수 없습니다.")
        val snapshot = buildAnswerSnapshot(aRow)
        val qTitle = QuestionsTable.select { QuestionsTable.id eq aRow[AnswersTable.questionId] }
            .singleOrNull()?.get(QuestionsTable.title) ?: "질문"
        val now = Instant.now()
        val trashId = UUID.randomUUID()
        AdminTrashBinTable.insert {
            it[id] = trashId
            it[entityType] = TYPE_ANSWER
            it[entityId] = answerId
            it[title] = "답변: ${snapshot.content.replace(Regex("\\s+"), " ").take(80)}".take(200)
            it[summary] = "원문 질문: $qTitle".take(300)
            it[snapshotJson] = json.encodeToString(snapshot)
            it[AdminTrashBinTable.deletedBy] = deletedBy
            it[deletedAt] = now
            it[expiresAt] = now.plus(RETENTION_DAYS, ChronoUnit.DAYS)
            it[restoredAt] = null
        }
        return trashId
    }

    private fun extractSavedAttachments(entityType: String, rawJson: String): List<String> = runCatching {
        when (entityType) {
            TYPE_COURSE -> {
                val snap = json.decodeFromString<TrashCourseSnapshot>(rawJson)
                snap.questions.flatMap { q ->
                    q.attachments.map { it.savedName } + q.answers.flatMap { a -> a.attachments.map { it.savedName } }
                }
            }
            TYPE_QUESTION -> {
                val snap = json.decodeFromString<TrashQuestionSnapshot>(rawJson)
                snap.attachments.map { it.savedName } + snap.answers.flatMap { a -> a.attachments.map { it.savedName } }
            }
            TYPE_ANSWER -> {
                val snap = json.decodeFromString<TrashAnswerSnapshot>(rawJson)
                snap.attachments.map { it.savedName }
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    suspend fun listTrashItems(questionService: QuestionService): List<AdminTrashItemDto> {
        val (items, expiredFiles) = DatabaseFactory.adminTransaction("listAdminTrash") {
            val now = Instant.now()
            val expiredRows = AdminTrashBinTable.select {
                AdminTrashBinTable.restoredAt.isNull() and (AdminTrashBinTable.expiresAt lessEq now)
            }.toList()
            val filesToPurge = expiredRows.flatMap {
                extractSavedAttachments(it[AdminTrashBinTable.entityType], it[AdminTrashBinTable.snapshotJson])
            }
            if (expiredRows.isNotEmpty()) {
                val expiredIds = expiredRows.map { it[AdminTrashBinTable.id].value }
                AdminTrashBinTable.deleteWhere { AdminTrashBinTable.id inList expiredIds }
            }

            val activeRows = AdminTrashBinTable.select { AdminTrashBinTable.restoredAt.isNull() }
                .orderBy(AdminTrashBinTable.deletedAt to SortOrder.DESC)
                .map { row ->
                    val exp = row[AdminTrashBinTable.expiresAt]
                    val remDays = Duration.between(now, exp).toDays().coerceAtLeast(0L)
                    AdminTrashItemDto(
                        id = row[AdminTrashBinTable.id].value.toString(),
                        entityType = row[AdminTrashBinTable.entityType],
                        entityId = row[AdminTrashBinTable.entityId].toString(),
                        title = row[AdminTrashBinTable.title],
                        summary = row[AdminTrashBinTable.summary],
                        deletedBy = row[AdminTrashBinTable.deletedBy]?.toString(),
                        deletedAt = row[AdminTrashBinTable.deletedAt].toString(),
                        expiresAt = exp.toString(),
                        remainingDays = remDays
                    )
                }
            activeRows to filesToPurge
        }
        if (expiredFiles.isNotEmpty()) {
            questionService.purgeStoredFiles(expiredFiles)
        }
        return items
    }

    private fun restoreAttachmentsInternal(records: List<TrashAttachmentRecord>) {
        records.forEach { att ->
            val attId = UUID.fromString(att.id)
            if (AttachmentsTable.select { AttachmentsTable.id eq attId }.count() == 0L) {
                AttachmentsTable.insert {
                    it[id] = attId
                    it[questionId] = att.questionId?.let(UUID::fromString)
                    it[answerId] = att.answerId?.let(UUID::fromString)
                    it[uploaderId] = att.uploaderId?.let(UUID::fromString)
                    it[originalName] = att.originalName
                    it[savedName] = att.savedName
                    it[fileSize] = att.fileSize
                    it[originalSize] = att.originalSize
                    it[mimeType] = att.mimeType
                    it[isImage] = att.isImage
                    it[isCompressed] = att.isCompressed
                    it[compressionRatio] = att.compressionRatio
                    it[createdAt] = runCatching { Instant.parse(att.createdAt) }.getOrDefault(Instant.now())
                }
            }
        }
    }

    private fun restoreAnswerInternal(snap: TrashAnswerSnapshot) {
        val qId = UUID.fromString(snap.questionId)
        if (QuestionsTable.select { QuestionsTable.id eq qId }.count() == 0L) {
            throw IllegalArgumentException("부모 질문이 삭제 상태입니다. 먼저 질문을 복구해 주세요.")
        }
        val aId = UUID.fromString(snap.id)
        if (AnswersTable.select { AnswersTable.id eq aId }.count() == 0L) {
            AnswersTable.insert {
                it[id] = aId
                it[questionId] = qId
                it[professorId] = UUID.fromString(snap.professorId)
                it[sequenceNumber] = snap.sequenceNumber
                it[rawContent] = snap.rawContent
                it[content] = snap.content
                it[isAssignedProfessor] = snap.isAssignedProfessor
                it[isHidden] = snap.isHidden
                it[createdAt] = runCatching { Instant.parse(snap.createdAt) }.getOrDefault(Instant.now())
            }
        }
        restoreAttachmentsInternal(snap.attachments)
    }

    private fun restoreQuestionInternal(snap: TrashQuestionSnapshot) {
        val cId = UUID.fromString(snap.courseId)
        if (CoursesTable.select { CoursesTable.id eq cId }.count() == 0L) {
            throw IllegalArgumentException("소속 교과목이 삭제 상태입니다. 먼저 교과목을 복구해 주세요.")
        }
        val qId = UUID.fromString(snap.id)
        if (QuestionsTable.select { QuestionsTable.id eq qId }.count() == 0L) {
            QuestionsTable.insert {
                it[id] = qId
                it[courseId] = cId
                it[studentId] = UUID.fromString(snap.studentId)
                it[assignedProfessorId] = UUID.fromString(snap.assignedProfessorId)
                it[courseProfessorId] = snap.courseProfessorId?.let(UUID::fromString)
                it[createdSemester] = snap.createdSemester
                it[category] = snap.category
                it[title] = snap.title
                it[rawContent] = snap.rawContent
                it[refinedContent] = snap.refinedContent
                it[isAnonymous] = snap.isAnonymous
                it[isHidden] = snap.isHidden
                it[isThreadOpen] = snap.isThreadOpen
                it[lastSequenceNumber] = snap.lastSequenceNumber
                it[isDeletedByAuthor] = snap.isDeletedByAuthor
                it[deletedAt] = snap.deletedAt?.let { ts -> runCatching { Instant.parse(ts) }.getOrNull() }
                it[vectorData] = snap.vectorData
                it[status] = snap.status
                it[createdAt] = runCatching { Instant.parse(snap.createdAt) }.getOrDefault(Instant.now())
            }
        }
        restoreAttachmentsInternal(snap.attachments)
        snap.answers.forEach { restoreAnswerInternal(it) }
    }

    private fun restoreCourseInternal(snap: TrashCourseSnapshot) {
        val cId = UUID.fromString(snap.id)
        if (CoursesTable.select { CoursesTable.id eq cId }.count() == 0L) {
            CoursesTable.insert {
                it[id] = cId
                it[name] = snap.name
                it[code] = snap.code
                it[semester] = snap.semester
                it[college] = snap.college
                it[department] = snap.department
                it[major] = snap.major
                it[completionType] = snap.completionType
                it[credit] = snap.credit
            }
        }
        snap.aliases.forEach { DatabaseFactory.ensureCourseAliasInternal(cId, it) }
        snap.professors.forEach { p ->
            val cpId = UUID.fromString(p.id)
            val profId = UUID.fromString(p.professorId)
            if (UsersTable.select { UsersTable.id eq profId }.count() > 0L &&
                CourseProfessorsTable.select { CourseProfessorsTable.id eq cpId }.count() == 0L
            ) {
                CourseProfessorsTable.insert {
                    it[id] = cpId
                    it[courseId] = cId
                    it[professorId] = profId
                    it[section] = p.section
                    it[schedule] = p.schedule
                }
            }
        }
        snap.enrollments.forEach { e ->
            val eId = UUID.fromString(e.id)
            val sId = UUID.fromString(e.studentId)
            if (UsersTable.select { UsersTable.id eq sId }.count() > 0L &&
                EnrollmentsTable.select { EnrollmentsTable.id eq eId }.count() == 0L
            ) {
                EnrollmentsTable.insert {
                    it[id] = eId
                    it[courseId] = cId
                    it[studentId] = sId
                    it[assignedProfessorId] = e.assignedProfessorId?.let(UUID::fromString)
                    it[courseProfessorId] = e.courseProfessorId?.let(UUID::fromString)
                }
            }
        }
        snap.questions.forEach { restoreQuestionInternal(it) }
    }

    suspend fun restoreTrashItem(trashId: UUID): AdminTrashItemDto =
        DatabaseFactory.adminTransaction("restoreTrashItem") {
            val row = AdminTrashBinTable.select { AdminTrashBinTable.id eq trashId }.singleOrNull()
                ?: throw IllegalArgumentException("휴지통에서 해당 항목을 찾을 수 없습니다.")
            if (row[AdminTrashBinTable.restoredAt] != null) {
                throw IllegalArgumentException("이미 복구된 항목입니다.")
            }
            val now = Instant.now()
            if (!row[AdminTrashBinTable.expiresAt].isAfter(now)) {
                throw IllegalArgumentException("보존 기간(30일)이 만료되어 복구할 수 없습니다.")
            }
            val type = row[AdminTrashBinTable.entityType]
            val rawJson = row[AdminTrashBinTable.snapshotJson]
            when (type) {
                TYPE_COURSE -> restoreCourseInternal(json.decodeFromString(rawJson))
                TYPE_QUESTION -> restoreQuestionInternal(json.decodeFromString(rawJson))
                TYPE_ANSWER -> restoreAnswerInternal(json.decodeFromString(rawJson))
                else -> throw IllegalArgumentException("지원하지 않는 복구 유형입니다: $type")
            }
            AdminTrashBinTable.update({ AdminTrashBinTable.id eq trashId }) {
                it[restoredAt] = now
            }
            AdminTrashItemDto(
                id = trashId.toString(),
                entityType = type,
                entityId = row[AdminTrashBinTable.entityId].toString(),
                title = row[AdminTrashBinTable.title],
                summary = row[AdminTrashBinTable.summary],
                deletedBy = row[AdminTrashBinTable.deletedBy]?.toString(),
                deletedAt = row[AdminTrashBinTable.deletedAt].toString(),
                expiresAt = row[AdminTrashBinTable.expiresAt].toString(),
                remainingDays = Duration.between(now, row[AdminTrashBinTable.expiresAt]).toDays().coerceAtLeast(0L)
            )
        }

    suspend fun purgeTrashItem(trashId: UUID, questionService: QuestionService): Boolean {
        val savedFiles = DatabaseFactory.adminTransaction("purgeTrashItem") {
            val row = AdminTrashBinTable.select { AdminTrashBinTable.id eq trashId }.singleOrNull()
                ?: throw IllegalArgumentException("휴지통에서 해당 항목을 찾을 수 없습니다.")
            val files = extractSavedAttachments(row[AdminTrashBinTable.entityType], row[AdminTrashBinTable.snapshotJson])
            AdminTrashBinTable.deleteWhere { AdminTrashBinTable.id eq trashId }
            files
        }
        if (savedFiles.isNotEmpty()) {
            questionService.purgeStoredFiles(savedFiles)
        }
        return true
    }
}
