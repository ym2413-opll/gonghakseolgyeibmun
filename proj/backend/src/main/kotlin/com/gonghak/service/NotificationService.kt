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
    private val workerManager: BackgroundWorkerManager = BackgroundWorkerManager(),
    private val webPushService: WebPushService? = null
) {
    private val zoneId = ZoneId.of("Asia/Seoul")
    private val quietHourFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private fun validateQuietHourTime(raw: String, fieldLabel: String): String {
        val trimmed = raw.trim()
        val parsed = runCatching { LocalTime.parse(trimmed, quietHourFormatter) }.getOrElse {
            throw IllegalArgumentException("$fieldLabel 형식이 올바르지 않습니다. (예: 22:00)")
        }
        return parsed.format(quietHourFormatter)
    }

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
        val cleanStart = request.quietHoursStart?.let { validateQuietHourTime(it, "방해금지 시작 시각") }
        val cleanEnd = request.quietHoursEnd?.let { validateQuietHourTime(it, "방해금지 종료 시각") }
        val cleanEmail = request.notificationEmail?.trim()?.let {
            if (it.isBlank()) null else PasswordSecurity.validateEmail(it)
        }

        if (cleanEmail != null) {
            val lowerEmail = cleanEmail.lowercase()
            val usedByOtherUser = UsersTable.select {
                (UsersTable.id neq professorId) and (UsersTable.email.lowerCase() eq lowerEmail)
            }.count() > 0
            val usedByOtherSetting = ProfessorNotificationSettingsTable.select {
                (ProfessorNotificationSettingsTable.professorId neq professorId) and
                    (ProfessorNotificationSettingsTable.notificationEmail.lowerCase() eq lowerEmail)
            }.count() > 0
            if (usedByOtherUser || usedByOtherSetting) {
                throw IllegalArgumentException("이미 다른 계정에 등록된 이메일 주소입니다.")
            }
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
                cleanStart?.let { v -> it[quietHoursStart] = v }
                cleanEnd?.let { v -> it[quietHoursEnd] = v }
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
                it[quietHoursStart] = cleanStart ?: "22:00"
                it[quietHoursEnd] = cleanEnd ?: "08:00"
                it[notifyOtherProfessorQuestions] = request.notifyOtherProfessorQuestions ?: false
                it[emailEnabled] = request.emailEnabled ?: true
                it[notificationEmail] = cleanEmail
            }
        }

        getProfessorNotificationSettingInternal(professorId)
    }

    /**
     * Inserts an in-app notification (honoring DND quiet hours) and optionally queues an
     * email alert for a single professor (either the assigned professor or a co-professor).
     */
    private fun dispatchToProfessor(
        professorId: UUID,
        notificationType: NotificationType,
        courseName: String,
        questionId: UUID,
        questionTitle: String,
        notifTitle: String,
        notifMessage: String,
        category: QuestionCategory,
        questionContent: String,
        authorDisplay: String,
        isCoProfessor: Boolean,
        emailsToDispatch: MutableList<PendingEmailJob>,
        pushesToDispatch: MutableList<PendingPushJob>
    ) {
        val profUser = UsersTable.select { UsersTable.id eq professorId }.singleOrNull()
        val profName = profUser?.get(UsersTable.name) ?: "교수"
        val profSetting = ProfessorNotificationSettingsTable.select {
            ProfessorNotificationSettingsTable.professorId eq professorId
        }.singleOrNull()

        val isQuiet = isNowInQuietHours(
            profSetting?.get(ProfessorNotificationSettingsTable.quietHoursEnabled) ?: false,
            profSetting?.get(ProfessorNotificationSettingsTable.quietHoursStart) ?: "22:00",
            profSetting?.get(ProfessorNotificationSettingsTable.quietHoursEnd) ?: "08:00"
        )

        val notifId = UUID.randomUUID()
        val safeTitle = notifTitle.take(200)
        NotificationsTable.insert {
            it[id] = notifId
            it[userId] = professorId
            it[type] = notificationType.name
            it[title] = safeTitle
            it[message] = notifMessage
            it[referenceId] = questionId
            it[isRead] = false
            it[isHeld] = isQuiet
            it[releaseAt] = if (isQuiet) profSetting?.get(ProfessorNotificationSettingsTable.quietHoursEnd) else null
        }

        if (!isQuiet) {
            pushesToDispatch.add(
                PendingPushJob(
                    userId = professorId,
                    notificationId = notifId,
                    title = safeTitle,
                    message = notifMessage,
                    questionId = questionId
                )
            )
        }

        val emailEnabled = profSetting?.get(ProfessorNotificationSettingsTable.emailEnabled) ?: true
        val targetEmail = profSetting?.get(ProfessorNotificationSettingsTable.notificationEmail)
            ?.takeIf { it.isNotBlank() }
            ?: profUser?.get(UsersTable.email)?.takeIf { it.isNotBlank() }

        if (emailEnabled && !targetEmail.isNullOrBlank()) {
            emailService.enqueueQuestionEmail(
                recipientEmail = targetEmail,
                professorId = professorId,
                professorName = profName,
                courseName = courseName,
                questionId = questionId,
                questionTitle = questionTitle,
                questionContent = questionContent.ifBlank { questionTitle },
                category = category,
                authorDisplay = authorDisplay,
                isCoProfessor = isCoProfessor
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
        val (pendingEmails, pendingPushes) = dbQuery(workerManager.notificationDispatcher) {
            val emailsToDispatch = mutableListOf<PendingEmailJob>()
            val pushesToDispatch = mutableListOf<PendingPushJob>()

            // 1. Notify Assigned Professor
            dispatchToProfessor(
                professorId = assignedProfessorId,
                notificationType = NotificationType.QUESTION_NEW,
                courseName = courseName,
                questionId = questionId,
                questionTitle = questionTitle,
                notifTitle = "[$courseName] 새로운 질문 등록",
                notifMessage = "[$category] $questionTitle",
                category = category,
                questionContent = questionContent,
                authorDisplay = authorDisplay,
                isCoProfessor = false,
                emailsToDispatch = emailsToDispatch,
                pushesToDispatch = pushesToDispatch
            )

            // 2. Notify other professors of the same course IF category is LECTURE
            if (category == QuestionCategory.LECTURE) {
                val otherProfessors = CourseProfessorsTable.select {
                    (CourseProfessorsTable.courseId eq courseId) and (CourseProfessorsTable.professorId neq assignedProfessorId)
                }.map { it[CourseProfessorsTable.professorId] }

                for (otherProfId in otherProfessors) {
                    val otherSetting = ProfessorNotificationSettingsTable.select {
                        ProfessorNotificationSettingsTable.professorId eq otherProfId
                    }.singleOrNull()

                    val notifyOther = otherSetting?.get(ProfessorNotificationSettingsTable.notifyOtherProfessorQuestions) ?: false
                    if (notifyOther) {
                        dispatchToProfessor(
                            professorId = otherProfId,
                            notificationType = NotificationType.OTHER_PROFESSOR_QUESTION,
                            courseName = courseName,
                            questionId = questionId,
                            questionTitle = questionTitle,
                            notifTitle = "[$courseName] (공동과목) 새로운 강의 질문 등록",
                            notifMessage = "다른 분반/교수 담당 질문: $questionTitle",
                            category = category,
                            questionContent = questionContent,
                            authorDisplay = authorDisplay,
                            isCoProfessor = true,
                            emailsToDispatch = emailsToDispatch,
                            pushesToDispatch = pushesToDispatch
                        )
                    }
                }
            }

            emailsToDispatch to pushesToDispatch
        }

        // Dispatch SMTP/mail sending & Web Push asynchronously after DB commit
        pendingEmails.forEach { emailService.dispatchEmailAsync(it) }
        webPushService?.dispatchPushAsync(pendingPushes)
    }

    suspend fun notifyFollowUpQuestion(
        assignedProfessorId: UUID,
        courseName: String,
        questionId: UUID,
        questionTitle: String,
        category: QuestionCategory,
        followUpContent: String,
        authorDisplay: String,
        participatingProfessorIds: List<UUID> = emptyList()
    ) {
        val (pendingEmails, pendingPushes) = dbQuery(workerManager.notificationDispatcher) {
            val emailsToDispatch = mutableListOf<PendingEmailJob>()
            val pushesToDispatch = mutableListOf<PendingPushJob>()
            dispatchToProfessor(
                professorId = assignedProfessorId,
                notificationType = NotificationType.QUESTION_NEW,
                courseName = courseName,
                questionId = questionId,
                questionTitle = questionTitle,
                notifTitle = "[$courseName] 질문에 추가 질문(꼬리질문)이 등록되었습니다.",
                notifMessage = "$authorDisplay: \"${questionTitle}\"",
                category = category,
                questionContent = followUpContent,
                authorDisplay = authorDisplay,
                isCoProfessor = false,
                emailsToDispatch = emailsToDispatch,
                pushesToDispatch = pushesToDispatch
            )

            // Also alert co-professors who previously participated in this thread so follow-ups are never missed.
            for (coProfId in participatingProfessorIds.distinct()) {
                if (coProfId == assignedProfessorId) continue
                dispatchToProfessor(
                    professorId = coProfId,
                    notificationType = NotificationType.OTHER_PROFESSOR_QUESTION,
                    courseName = courseName,
                    questionId = questionId,
                    questionTitle = questionTitle,
                    notifTitle = "[$courseName] 답변 참여 스레드에 추가 질문(꼬리질문)이 등록되었습니다.",
                    notifMessage = "$authorDisplay: \"${questionTitle}\"",
                    category = category,
                    questionContent = followUpContent,
                    authorDisplay = authorDisplay,
                    isCoProfessor = true,
                    emailsToDispatch = emailsToDispatch,
                    pushesToDispatch = pushesToDispatch
                )
            }
            emailsToDispatch to pushesToDispatch
        }

        pendingEmails.forEach { emailService.dispatchEmailAsync(it) }
        webPushService?.dispatchPushAsync(pendingPushes)
    }

    private fun insertInAppNotification(
        recipientId: UUID,
        notificationType: NotificationType,
        notifTitle: String,
        notifMessage: String,
        questionId: UUID,
        honorQuietHours: Boolean
    ): PendingPushJob? {
        val profSetting = if (honorQuietHours) {
            ProfessorNotificationSettingsTable.select {
                ProfessorNotificationSettingsTable.professorId eq recipientId
            }.singleOrNull()
        } else {
            null
        }

        val isQuiet = if (honorQuietHours && profSetting != null) {
            isNowInQuietHours(
                profSetting[ProfessorNotificationSettingsTable.quietHoursEnabled],
                profSetting[ProfessorNotificationSettingsTable.quietHoursStart],
                profSetting[ProfessorNotificationSettingsTable.quietHoursEnd]
            )
        } else {
            false
        }

        val notifId = UUID.randomUUID()
        val safeTitle = notifTitle.take(200)
        NotificationsTable.insert {
            it[id] = notifId
            it[userId] = recipientId
            it[type] = notificationType.name
            it[title] = safeTitle
            it[message] = notifMessage
            it[referenceId] = questionId
            it[isRead] = false
            it[isHeld] = isQuiet
            it[releaseAt] = if (isQuiet) profSetting?.get(ProfessorNotificationSettingsTable.quietHoursEnd) else null
        }

        return if (!isQuiet) {
            PendingPushJob(
                userId = recipientId,
                notificationId = notifId,
                title = safeTitle,
                message = notifMessage,
                questionId = questionId
            )
        } else {
            null
        }
    }

    suspend fun notifyNewAnswer(
        studentId: UUID,
        courseName: String,
        questionId: UUID,
        questionTitle: String,
        professorName: String,
        isProfessorAuthor: Boolean = true,
        actorId: UUID? = null,
        assignedProfessorId: UUID? = null,
        participatingProfessorIds: List<UUID> = emptyList()
    ) {
        val cleanAuthor = professorName.trim().ifBlank { "담당교수" }
        val authorDisplay = if (isProfessorAuthor) {
            if (cleanAuthor.endsWith("교수") || cleanAuthor.endsWith("교수님")) {
                if (cleanAuthor.endsWith("님")) cleanAuthor else "${cleanAuthor}님"
            } else {
                "${cleanAuthor} 교수님"
            }
        } else {
            "${cleanAuthor}님"
        }
        val safeTitle = "[$courseName] 질문에 답변이 등록되었습니다.".take(200)
        val safeMessage = if (isProfessorAuthor) {
            "${authorDisplay}이 답변을 남겼습니다: \"$questionTitle\""
        } else {
            "${authorDisplay}이 스레드에 답변을 남겼습니다: \"$questionTitle\""
        }

        val professorRecipients = (listOfNotNull(assignedProfessorId) + participatingProfessorIds)
            .distinct()
            .filter { it != actorId && it != studentId }
        val profTitle = "[$courseName] 스레드에 새로운 답변이 등록되었습니다.".take(200)
        val profMessage = "${authorDisplay}이 답변을 남겼습니다: \"$questionTitle\""

        val pendingPushes = dbQuery(workerManager.notificationDispatcher) {
            val pushes = mutableListOf<PendingPushJob>()
            if (actorId != studentId) {
                insertInAppNotification(
                    recipientId = studentId,
                    notificationType = NotificationType.ANSWER_NEW,
                    notifTitle = safeTitle,
                    notifMessage = safeMessage,
                    questionId = questionId,
                    honorQuietHours = false
                )?.let { pushes.add(it) }
            }

            // Notify assigned/participating professors when another professor, admin, or peer student replies.
            for (profRecipientId in professorRecipients) {
                insertInAppNotification(
                    recipientId = profRecipientId,
                    notificationType = NotificationType.ANSWER_NEW,
                    notifTitle = profTitle,
                    notifMessage = profMessage,
                    questionId = questionId,
                    honorQuietHours = true
                )?.let { pushes.add(it) }
            }
            pushes
        }

        webPushService?.dispatchPushAsync(pendingPushes)
    }

    /**
     * Notifies the assigned professor (and any participating co-professors) when the question
     * author deletes their question so prior email or bell alerts have clear context.
     */
    suspend fun notifyQuestionDeletedByAuthor(
        assignedProfessorId: UUID,
        courseName: String,
        questionId: UUID,
        questionTitle: String,
        participatingProfessorIds: List<UUID> = emptyList()
    ) {
        val recipients = (listOf(assignedProfessorId) + participatingProfessorIds).distinct()
        val safeTitle = "[$courseName] 학생이 질문을 삭제했습니다.".take(200)
        val safeMessage = "질문 작성자가 등록했던 질문을 삭제했습니다: \"$questionTitle\""
        val pendingPushes = dbQuery(workerManager.notificationDispatcher) {
            val pushes = mutableListOf<PendingPushJob>()
            for (recipientId in recipients) {
                insertInAppNotification(
                    recipientId = recipientId,
                    notificationType = NotificationType.QUESTION_DELETED,
                    notifTitle = safeTitle,
                    notifMessage = safeMessage,
                    questionId = questionId,
                    honorQuietHours = false
                )?.let { pushes.add(it) }
            }
            pushes
        }

        webPushService?.dispatchPushAsync(pendingPushes)
    }

    /**
     * Executed periodically by the background scheduler thread to proactively release
     * DND-held notifications whose recipients are no longer in quiet hours.
     */
    suspend fun releaseHeldNotificationsBatch(): Int {
        val (totalReleased, releasedPushes) = dbQuery(workerManager.notificationDispatcher) {
            val heldUserIds = NotificationsTable.slice(NotificationsTable.userId)
                .select { NotificationsTable.isHeld eq true }
                .withDistinct()
                .map { it[NotificationsTable.userId] }

            if (heldUserIds.isEmpty()) return@dbQuery 0 to emptyList<PendingPushJob>()

            var releasedCount = 0
            val pushes = mutableListOf<PendingPushJob>()
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
                    val toRelease = NotificationsTable.select {
                        (NotificationsTable.userId eq uid) and (NotificationsTable.isHeld eq true)
                    }.map { row ->
                        PendingPushJob(
                            userId = uid,
                            notificationId = row[NotificationsTable.id].value,
                            title = row[NotificationsTable.title],
                            message = row[NotificationsTable.message],
                            questionId = row[NotificationsTable.referenceId]
                        )
                    }
                    releasedCount += NotificationsTable.update({
                        (NotificationsTable.userId eq uid) and (NotificationsTable.isHeld eq true)
                    }) {
                        it[isHeld] = false
                    }
                    pushes.addAll(toRelease)
                }
            }
            releasedCount to pushes
        }

        webPushService?.dispatchPushAsync(releasedPushes)
        return totalReleased
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
