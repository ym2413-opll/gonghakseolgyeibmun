package com.gonghak.service

import com.gonghak.config.DatabaseFactory.dbQuery
import com.gonghak.dto.NotificationDto
import com.gonghak.dto.ProfessorNotificationSettingDto
import com.gonghak.dto.UpdateProfessorNotificationSettingRequest
import com.gonghak.models.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class NotificationService(
    private val emailService: EmailService = EmailService(),
    private val workerManager: BackgroundWorkerManager = BackgroundWorkerManager()
) {
    private val zoneId = ZoneId.of("Asia/Seoul")

    suspend fun getProfessorNotificationSetting(professorId: UUID): ProfessorNotificationSettingDto = dbQuery {
        getProfessorNotificationSettingInternal(professorId)
    }

    private fun getProfessorNotificationSettingInternal(professorId: UUID): ProfessorNotificationSettingDto {
        val userEmail = UsersTable.select { UsersTable.id eq professorId }
            .singleOrNull()?.get(UsersTable.email)

        val row = ProfessorNotificationSettingsTable.select { ProfessorNotificationSettingsTable.professorId eq professorId }
            .singleOrNull()

        return if (row != null) {
            val settingEmail = row[ProfessorNotificationSettingsTable.notificationEmail] ?: userEmail
            ProfessorNotificationSettingDto(
                professorId = row[ProfessorNotificationSettingsTable.professorId].toString(),
                quietHoursEnabled = row[ProfessorNotificationSettingsTable.quietHoursEnabled],
                quietHoursStart = row[ProfessorNotificationSettingsTable.quietHoursStart],
                quietHoursEnd = row[ProfessorNotificationSettingsTable.quietHoursEnd],
                notifyOtherProfessorQuestions = row[ProfessorNotificationSettingsTable.notifyOtherProfessorQuestions],
                emailEnabled = row[ProfessorNotificationSettingsTable.emailEnabled],
                notificationEmail = settingEmail
            )
        } else {
            ProfessorNotificationSettingsTable.insert {
                it[ProfessorNotificationSettingsTable.professorId] = professorId
                it[quietHoursEnabled] = false
                it[quietHoursStart] = "22:00"
                it[quietHoursEnd] = "08:00"
                it[notifyOtherProfessorQuestions] = false
                it[emailEnabled] = true
                it[notificationEmail] = userEmail
            }
            ProfessorNotificationSettingDto(
                professorId = professorId.toString(),
                quietHoursEnabled = false,
                quietHoursStart = "22:00",
                quietHoursEnd = "08:00",
                notifyOtherProfessorQuestions = false,
                emailEnabled = true,
                notificationEmail = userEmail
            )
        }
    }

    suspend fun updateProfessorNotificationSetting(
        professorId: UUID,
        request: UpdateProfessorNotificationSettingRequest
    ): ProfessorNotificationSettingDto = dbQuery {
        val cleanEmail = request.notificationEmail?.trim()?.let {
            if (it.isBlank()) null else PasswordSecurity.validateEmail(it)
        }

        if (request.notificationEmail != null) {
            UsersTable.update({ UsersTable.id eq professorId }) {
                it[email] = cleanEmail
            }
        }

        val exists = ProfessorNotificationSettingsTable.select { ProfessorNotificationSettingsTable.professorId eq professorId }
            .count() > 0

        if (exists) {
            ProfessorNotificationSettingsTable.update({ ProfessorNotificationSettingsTable.professorId eq professorId }) {
                request.quietHoursEnabled?.let { v -> it[quietHoursEnabled] = v }
                request.quietHoursStart?.let { v -> it[quietHoursStart] = v }
                request.quietHoursEnd?.let { v -> it[quietHoursEnd] = v }
                request.notifyOtherProfessorQuestions?.let { v -> it[notifyOtherProfessorQuestions] = v }
                request.emailEnabled?.let { v -> it[emailEnabled] = v }
                if (request.notificationEmail != null) {
                    it[notificationEmail] = cleanEmail
                }
            }
        } else {
            ProfessorNotificationSettingsTable.insert {
                it[ProfessorNotificationSettingsTable.professorId] = professorId
                it[quietHoursEnabled] = request.quietHoursEnabled ?: false
                it[quietHoursStart] = request.quietHoursStart ?: "22:00"
                it[quietHoursEnd] = request.quietHoursEnd ?: "08:00"
                it[notifyOtherProfessorQuestions] = request.notifyOtherProfessorQuestions ?: false
                it[emailEnabled] = request.emailEnabled ?: true
                it[notificationEmail] = cleanEmail
            }
        }

        getProfessorNotificationSettingInternal(professorId)
    }

    private fun dispatchToAssignedProfessor(
        assignedProfessorId: UUID,
        courseName: String,
        questionId: UUID,
        questionTitle: String,
        notifTitle: String,
        notifMessage: String,
        category: QuestionCategory,
        questionContent: String,
        authorDisplay: String,
        emailsToDispatch: MutableList<PendingEmailJob>
    ) {
        val assignedUser = UsersTable.select { UsersTable.id eq assignedProfessorId }.singleOrNull()
        val assignedProfName = assignedUser?.get(UsersTable.name) ?: "교수"
        val assignedSetting = ProfessorNotificationSettingsTable.select {
            ProfessorNotificationSettingsTable.professorId eq assignedProfessorId
        }.singleOrNull()

        val isAssignedQuiet = isNowInQuietHours(
            assignedSetting?.get(ProfessorNotificationSettingsTable.quietHoursEnabled) ?: false,
            assignedSetting?.get(ProfessorNotificationSettingsTable.quietHoursStart) ?: "22:00",
            assignedSetting?.get(ProfessorNotificationSettingsTable.quietHoursEnd) ?: "08:00"
        )

        NotificationsTable.insert {
            it[userId] = assignedProfessorId
            it[type] = NotificationType.QUESTION_NEW.name
            it[title] = notifTitle
            it[message] = notifMessage
            it[referenceId] = questionId
            it[isRead] = false
            it[isHeld] = isAssignedQuiet
            it[releaseAt] = if (isAssignedQuiet) assignedSetting?.get(ProfessorNotificationSettingsTable.quietHoursEnd) else null
        }

        val assignedEmailEnabled = assignedSetting?.get(ProfessorNotificationSettingsTable.emailEnabled) ?: true
        val assignedEmail = assignedSetting?.get(ProfessorNotificationSettingsTable.notificationEmail)
            ?.takeIf { it.isNotBlank() }
            ?: assignedUser?.get(UsersTable.email)?.takeIf { it.isNotBlank() }

        if (assignedEmailEnabled && !assignedEmail.isNullOrBlank()) {
            emailService.enqueueQuestionEmail(
                recipientEmail = assignedEmail,
                professorId = assignedProfessorId,
                professorName = assignedProfName,
                courseName = courseName,
                questionId = questionId,
                questionTitle = questionTitle,
                questionContent = questionContent.ifBlank { questionTitle },
                category = category,
                authorDisplay = authorDisplay,
                isCoProfessor = false
            )?.let { emailsToDispatch.add(it) }
        }
    }

    suspend fun notifyNewQuestion(
        assignedProfessorId: UUID,
        courseId: UUID,
        courseName: String,
        questionId: UUID,
        questionTitle: String,
        category: QuestionCategory,
        questionContent: String = "",
        authorDisplay: String = "수강생"
    ) {
        val pendingEmails = dbQuery(workerManager.notificationDispatcher) {
            val emailsToDispatch = mutableListOf<PendingEmailJob>()

            // 1. Notify Assigned Professor
            dispatchToAssignedProfessor(
                assignedProfessorId = assignedProfessorId,
                courseName = courseName,
                questionId = questionId,
                questionTitle = questionTitle,
                notifTitle = "[$courseName] 새로운 질문 등록",
                notifMessage = "[$category] $questionTitle",
                category = category,
                questionContent = questionContent,
                authorDisplay = authorDisplay,
                emailsToDispatch = emailsToDispatch
            )

            // 2. Notify other professors of the same course IF category is LECTURE
            if (category == QuestionCategory.LECTURE) {
                val otherProfessors = CourseProfessorsTable.select {
                    (CourseProfessorsTable.courseId eq courseId) and (CourseProfessorsTable.professorId neq assignedProfessorId)
                }.map { it[CourseProfessorsTable.professorId] }

                for (otherProfId in otherProfessors) {
                    val otherUser = UsersTable.select { UsersTable.id eq otherProfId }.singleOrNull()
                    val otherProfName = otherUser?.get(UsersTable.name) ?: "교수"
                    val otherSetting = ProfessorNotificationSettingsTable.select {
                        ProfessorNotificationSettingsTable.professorId eq otherProfId
                    }.singleOrNull()

                    val notifyOther = otherSetting?.get(ProfessorNotificationSettingsTable.notifyOtherProfessorQuestions) ?: false
                    if (notifyOther) {
                        val isOtherQuiet = isNowInQuietHours(
                            otherSetting?.get(ProfessorNotificationSettingsTable.quietHoursEnabled) ?: false,
                            otherSetting?.get(ProfessorNotificationSettingsTable.quietHoursStart) ?: "22:00",
                            otherSetting?.get(ProfessorNotificationSettingsTable.quietHoursEnd) ?: "08:00"
                        )

                        NotificationsTable.insert {
                            it[userId] = otherProfId
                            it[type] = NotificationType.OTHER_PROFESSOR_QUESTION.name
                            it[title] = "[$courseName] (공동과목) 새로운 강의 질문 등록"
                            it[message] = "다른 분반/교수 담당 질문: $questionTitle"
                            it[referenceId] = questionId
                            it[isRead] = false
                            it[isHeld] = isOtherQuiet
                            it[releaseAt] = if (isOtherQuiet) otherSetting?.get(ProfessorNotificationSettingsTable.quietHoursEnd) else null
                        }

                        val otherEmailEnabled = otherSetting?.get(ProfessorNotificationSettingsTable.emailEnabled) ?: true
                        val otherEmail = otherSetting?.get(ProfessorNotificationSettingsTable.notificationEmail)
                            ?.takeIf { it.isNotBlank() }
                            ?: otherUser?.get(UsersTable.email)?.takeIf { it.isNotBlank() }

                        if (otherEmailEnabled && !otherEmail.isNullOrBlank()) {
                            emailService.enqueueQuestionEmail(
                                recipientEmail = otherEmail,
                                professorId = otherProfId,
                                professorName = otherProfName,
                                courseName = courseName,
                                questionId = questionId,
                                questionTitle = questionTitle,
                                questionContent = questionContent.ifBlank { questionTitle },
                                category = category,
                                authorDisplay = authorDisplay,
                                isCoProfessor = true
                            )?.let { emailsToDispatch.add(it) }
                        }
                    }
                }
            }

            emailsToDispatch
        }

        // Dispatch SMTP/mail sending asynchronously on dedicated email worker threads after DB commit
        pendingEmails.forEach { emailService.dispatchEmailAsync(it) }
    }

    suspend fun notifyFollowUpQuestion(
        assignedProfessorId: UUID,
        courseName: String,
        questionId: UUID,
        questionTitle: String,
        category: QuestionCategory,
        followUpContent: String,
        authorDisplay: String
    ) {
        val pendingEmails = dbQuery(workerManager.notificationDispatcher) {
            val emailsToDispatch = mutableListOf<PendingEmailJob>()
            dispatchToAssignedProfessor(
                assignedProfessorId = assignedProfessorId,
                courseName = courseName,
                questionId = questionId,
                questionTitle = questionTitle,
                notifTitle = "[$courseName] 질문에 추가 질문(꼬리질문)이 등록되었습니다.",
                notifMessage = "$authorDisplay: \"${questionTitle}\"",
                category = category,
                questionContent = followUpContent,
                authorDisplay = authorDisplay,
                emailsToDispatch = emailsToDispatch
            )
            emailsToDispatch
        }

        pendingEmails.forEach { emailService.dispatchEmailAsync(it) }
    }

    suspend fun notifyNewAnswer(
        studentId: UUID,
        courseName: String,
        questionId: UUID,
        questionTitle: String,
        professorName: String
    ) {
        dbQuery(workerManager.notificationDispatcher) {
            NotificationsTable.insert {
                it[userId] = studentId
                it[type] = NotificationType.ANSWER_NEW.name
                it[title] = "[$courseName] 질문에 답변이 등록되었습니다."
                it[message] = "${professorName} 교수님이 답변을 남겼습니다: \"$questionTitle\""
                it[referenceId] = questionId
                it[isRead] = false
                it[isHeld] = false
                it[releaseAt] = null
            }
        }
    }

    /**
     * Notifies the assigned professor when the question author deletes their question so
     * prior email or bell alerts have clear context in the dashboard.
     */
    suspend fun notifyQuestionDeletedByAuthor(
        assignedProfessorId: UUID,
        courseName: String,
        questionId: UUID,
        questionTitle: String
    ) {
        dbQuery(workerManager.notificationDispatcher) {
            NotificationsTable.insert {
                it[userId] = assignedProfessorId
                it[type] = NotificationType.QUESTION_DELETED.name
                it[title] = "[$courseName] 학생이 질문을 삭제했습니다."
                it[message] = "질문 작성자가 등록했던 질문을 삭제했습니다: \"$questionTitle\""
                it[referenceId] = questionId
                it[isRead] = false
                it[isHeld] = false
                it[releaseAt] = null
            }
        }
    }

    /**
     * Executed periodically by the background scheduler thread to proactively release
     * DND-held notifications whose recipients are no longer in quiet hours.
     */
    suspend fun releaseHeldNotificationsBatch(): Int = dbQuery(workerManager.notificationDispatcher) {
        val heldUserIds = NotificationsTable.slice(NotificationsTable.userId)
            .select { NotificationsTable.isHeld eq true }
            .withDistinct()
            .map { it[NotificationsTable.userId] }

        if (heldUserIds.isEmpty()) return@dbQuery 0

        var totalReleased = 0
        for (uid in heldUserIds) {
            val profSetting = ProfessorNotificationSettingsTable.select {
                ProfessorNotificationSettingsTable.professorId eq uid
            }.singleOrNull()

            val isCurrentlyQuiet = if (profSetting != null) {
                isNowInQuietHours(
                    profSetting[ProfessorNotificationSettingsTable.quietHoursEnabled],
                    profSetting[ProfessorNotificationSettingsTable.quietHoursStart],
                    profSetting[ProfessorNotificationSettingsTable.quietHoursEnd]
                )
            } else {
                false
            }

            if (!isCurrentlyQuiet) {
                totalReleased += NotificationsTable.update({
                    (NotificationsTable.userId eq uid) and (NotificationsTable.isHeld eq true)
                }) {
                    it[isHeld] = false
                }
            }
        }
        totalReleased
    }

    suspend fun getNotifications(userId: UUID): List<NotificationDto> = dbQuery {
        // Release any held notifications if quiet hours have passed
        val profSetting = ProfessorNotificationSettingsTable.select {
            ProfessorNotificationSettingsTable.professorId eq userId
        }.singleOrNull()

        val isCurrentlyQuiet = if (profSetting != null) {
            isNowInQuietHours(
                profSetting[ProfessorNotificationSettingsTable.quietHoursEnabled],
                profSetting[ProfessorNotificationSettingsTable.quietHoursStart],
                profSetting[ProfessorNotificationSettingsTable.quietHoursEnd]
            )
        } else {
            false
        }

        // If not in quiet hours right now, release all held notifications for this user (몰아서 보냄)
        if (!isCurrentlyQuiet) {
            NotificationsTable.update({
                (NotificationsTable.userId eq userId) and (NotificationsTable.isHeld eq true)
            }) {
                it[isHeld] = false
            }
        }

        // Return all notifications where isHeld == false (active to view)
        NotificationsTable.select {
            (NotificationsTable.userId eq userId) and (NotificationsTable.isHeld eq false)
        }
            .orderBy(NotificationsTable.createdAt to SortOrder.DESC)
            .map {
                val refId = it[NotificationsTable.referenceId]?.toString()
                NotificationDto(
                    id = it[NotificationsTable.id].value.toString(),
                    userId = it[NotificationsTable.userId].toString(),
                    type = it[NotificationsTable.type],
                    title = it[NotificationsTable.title],
                    message = it[NotificationsTable.message],
                    referenceId = refId,
                    questionId = refId,
                    isRead = it[NotificationsTable.isRead],
                    isHeld = it[NotificationsTable.isHeld],
                    releaseAt = it[NotificationsTable.releaseAt],
                    createdAt = it[NotificationsTable.createdAt].toString()
                )
            }
    }

    suspend fun markAsRead(notificationId: UUID, userId: UUID): Boolean = dbQuery {
        val updated = NotificationsTable.update({
            (NotificationsTable.id eq notificationId) and (NotificationsTable.userId eq userId)
        }) {
            it[isRead] = true
        }
        updated > 0
    }

    private fun isNowInQuietHours(enabled: Boolean, startStr: String, endStr: String): Boolean {
        if (!enabled) return false
        return try {
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            val start = LocalTime.parse(startStr, formatter)
            val end = LocalTime.parse(endStr, formatter)
            val now = LocalTime.now(zoneId)

            if (start.isBefore(end)) {
                now.isAfter(start) && now.isBefore(end)
            } else {
                now.isAfter(start) || now.isBefore(end)
            }
        } catch (_: Exception) {
            false
        }
    }
}
