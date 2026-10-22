package com.gonghak.models

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

object UsersTable : UUIDTable("users") {
    val name = varchar("name", 100)
    val role = varchar("role", 20) // STUDENT, PROFESSOR
    val identifier = varchar("identifier", 50) // 학번 또는 교번
    val department = varchar("department", 100)
    val displayTag = varchar("display_tag", 100)
    val isDemo = bool("is_demo").default(false)
}

object CoursesTable : UUIDTable("courses") {
    val name = varchar("name", 100)
    val code = varchar("code", 50)
    val semester = varchar("semester", 20)
    val college = varchar("college", 100).default("")
    val department = varchar("department", 100).default("")
    val major = varchar("major", 100).default("")
    val completionType = varchar("completion_type", 50).default("")
    val credit = integer("credit").default(3)
}

object CourseProfessorsTable : UUIDTable("course_professors") {
    val courseId = uuid("course_id").references(CoursesTable.id)
    val professorId = uuid("professor_id").references(UsersTable.id)
    val section = varchar("section", 50)
    val schedule = varchar("schedule", 200).default("")
}

object EnrollmentsTable : UUIDTable("enrollments") {
    val courseId = uuid("course_id").references(CoursesTable.id)
    val studentId = uuid("student_id").references(UsersTable.id)
    val assignedProfessorId = uuid("assigned_professor_id").references(UsersTable.id)
}

object ProfessorCategorySettingsTable : UUIDTable("professor_category_settings") {
    val professorId = uuid("professor_id").references(UsersTable.id)
    val courseId = uuid("course_id").references(CoursesTable.id)
    val category = varchar("category", 20)
    val isEnabled = bool("is_enabled").default(true)
}

object ProfessorNotificationSettingsTable : UUIDTable("professor_notification_settings") {
    val professorId = uuid("professor_id").references(UsersTable.id).uniqueIndex()
    val quietHoursEnabled = bool("quiet_hours_enabled").default(false)
    val quietHoursStart = varchar("quiet_hours_start", 10).default("22:00")
    val quietHoursEnd = varchar("quiet_hours_end", 10).default("08:00")
    val notifyOtherProfessorQuestions = bool("notify_other_professor_questions").default(false)
}

object UserSettingsTable : UUIDTable("user_settings") {
    val userId = uuid("user_id").references(UsersTable.id).uniqueIndex()
    val themeMode = varchar("theme_mode", 20).default("system") // light, dark, system
    val fontSize = varchar("font_size", 20).default("medium") // small, medium, large
}

object QuestionsTable : UUIDTable("questions") {
    val courseId = uuid("course_id").references(CoursesTable.id)
    val studentId = uuid("student_id").references(UsersTable.id)
    val assignedProfessorId = uuid("assigned_professor_id").references(UsersTable.id)
    val category = varchar("category", 20) // GRADE, ASSIGNMENT, LECTURE
    val title = varchar("title", 200)
    val rawContent = text("raw_content")
    val refinedContent = text("refined_content")
    val isAnonymous = bool("is_anonymous").default(false)
    val vectorData = text("vector_data").default("[]")
    val status = varchar("status", 20).default(QuestionStatus.OPEN.name)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}

object AnswersTable : UUIDTable("answers") {
    val questionId = uuid("question_id").references(QuestionsTable.id)
    val professorId = uuid("professor_id").references(UsersTable.id)
    val content = text("content")
    val isAssignedProfessor = bool("is_assigned_professor").default(false)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}

object NotificationsTable : UUIDTable("notifications") {
    val userId = uuid("user_id").references(UsersTable.id)
    val type = varchar("type", 50)
    val title = varchar("title", 200)
    val message = text("message")
    val referenceId = uuid("reference_id").nullable()
    val isRead = bool("is_read").default(false)
    val isHeld = bool("is_held").default(false)
    val releaseAt = varchar("release_at", 50).nullable()
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}

object AttachmentsTable : UUIDTable("attachments") {
    val questionId = uuid("question_id").references(QuestionsTable.id).nullable()
    val uploaderId = uuid("uploader_id").references(UsersTable.id).nullable()
    val originalName = varchar("original_name", 255)
    val savedName = varchar("saved_name", 255)
    val fileSize = long("file_size")
    val originalSize = long("original_size")
    val mimeType = varchar("mime_type", 100)
    val isImage = bool("is_image").default(false)
    val isCompressed = bool("is_compressed").default(false)
    val compressionRatio = double("compression_ratio").default(0.0)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}
