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

class NotificationService {
    private val zoneId = ZoneId.of("Asia/Seoul")

    suspend fun getProfessorNotificationSetting(professorId: UUID): ProfessorNotificationSettingDto = dbQuery {
        getProfessorNotificationSettingInternal(professorId)
    }

    private fun getProfessorNotificationSettingInternal(professorId: UUID): ProfessorNotificationSettingDto {
        val row = ProfessorNotificationSettingsTable.select { ProfessorNotificationSettingsTable.professorId eq professorId }
            .singleOrNull()

        return if (row != null) {
            ProfessorNotificationSettingDto(
                professorId = row[ProfessorNotificationSettingsTable.professorId].toString(),
                quietHoursEnabled = row[ProfessorNotificationSettingsTable.quietHoursEnabled],
                quietHoursStart = row[ProfessorNotificationSettingsTable.quietHoursStart],
                quietHoursEnd = row[ProfessorNotificationSettingsTable.quietHoursEnd],
                notifyOtherProfessorQuestions = row[ProfessorNotificationSettingsTable.notifyOtherProfessorQuestions]
            )
        } else {
            ProfessorNotificationSettingsTable.insert {
                it[ProfessorNotificationSettingsTable.professorId] = professorId
                it[quietHoursEnabled] = false
                it[quietHoursStart] = "22:00"
                it[quietHoursEnd] = "08:00"
                it[notifyOtherProfessorQuestions] = false
            }
            ProfessorNotificationSettingDto(
                professorId = professorId.toString(),
                quietHoursEnabled = false,
                quietHoursStart = "22:00",
                quietHoursEnd = "08:00",
                notifyOtherProfessorQuestions = false
            )
        }
    }

    suspend fun updateProfessorNotificationSetting(
        professorId: UUID,
        request: UpdateProfessorNotificationSettingRequest
    ): ProfessorNotificationSettingDto = dbQuery {
        val exists = ProfessorNotificationSettingsTable.select { ProfessorNotificationSettingsTable.professorId eq professorId }
            .count() > 0

        if (exists) {
            ProfessorNotificationSettingsTable.update({ ProfessorNotificationSettingsTable.professorId eq professorId }) {
                request.quietHoursEnabled?.let { v -> it[quietHoursEnabled] = v }
                request.quietHoursStart?.let { v -> it[quietHoursStart] = v }
                request.quietHoursEnd?.let { v -> it[quietHoursEnd] = v }
                request.notifyOtherProfessorQuestions?.let { v -> it[notifyOtherProfessorQuestions] = v }
            }
        } else {
            ProfessorNotificationSettingsTable.insert {
                it[ProfessorNotificationSettingsTable.professorId] = professorId
                it[quietHoursEnabled] = request.quietHoursEnabled ?: false
                it[quietHoursStart] = request.quietHoursStart ?: "22:00"
                it[quietHoursEnd] = request.quietHoursEnd ?: "08:00"
                it[notifyOtherProfessorQuestions] = request.notifyOtherProfessorQuestions ?: false
            }
        }

        getProfessorNotificationSettingInternal(professorId)
    }

    suspend fun notifyNewQuestion(
        assignedProfessorId: UUID,
        courseId: UUID,
        courseName: String,
        questionId: UUID,
        questionTitle: String,
        category: QuestionCategory
    ) = dbQuery {
        // 1. Notify Assigned Professor
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
            it[title] = "[$courseName] 새로운 질문 등록"
            it[message] = "[$category] $questionTitle"
            it[referenceId] = questionId
            it[isRead] = false
            it[isHeld] = isAssignedQuiet
            it[releaseAt] = if (isAssignedQuiet) assignedSetting?.get(ProfessorNotificationSettingsTable.quietHoursEnd) else null
        }

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
                }
            }
        }
    }

    suspend fun notifyNewAnswer(
        studentId: UUID,
        courseName: String,
        questionId: UUID,
        questionTitle: String,
        professorName: String
    ) = dbQuery {
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
                // e.g. 13:00 ~ 15:00
                now.isAfter(start) && now.isBefore(end)
            } else {
                // e.g. 22:00 ~ 08:00 (cross midnight)
                now.isAfter(start) || now.isBefore(end)
            }
        } catch (_: Exception) {
            false
        }
    }
}
