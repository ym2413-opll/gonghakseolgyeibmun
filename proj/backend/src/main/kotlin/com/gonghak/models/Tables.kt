package com.gonghak.models

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

object UsersTable : UUIDTable("users") {
    val username = varchar("username", 100).nullable().uniqueIndex()
    val passwordHash = varchar("password_hash", 255).nullable()
    val email = varchar("email", 150).nullable()
    val name = varchar("name", 100)
    val role = varchar("role", 20) // STUDENT, PROFESSOR, ADMIN
    val identifier = varchar("identifier", 50) // 학번 또는 교번
    val department = varchar("department", 100)
    val displayTag = varchar("display_tag", 100)
    val nickname = varchar("nickname", 50).nullable()
    val isDemo = bool("is_demo").default(false)
    val isAdmin = bool("is_admin").default(false)
    val isWithdrawn = bool("is_withdrawn").default(false)
    val questionBanUntil = timestamp("question_ban_until").nullable()
    val answerBanUntil = timestamp("answer_ban_until").nullable()
    val tokenVersion = integer("token_version").default(0)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}

object CollegesTable : UUIDTable("colleges") {
    val name = varchar("name", 100).uniqueIndex()
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}

object CollegeAliasesTable : UUIDTable("college_aliases") {
    val collegeId = uuid("college_id").references(CollegesTable.id)
    val aliasName = varchar("alias_name", 100).uniqueIndex()
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}

object DepartmentsTable : UUIDTable("departments") {
    val name = varchar("name", 100).uniqueIndex()
    val college = varchar("college", 100).default("")
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}

object DepartmentAliasesTable : UUIDTable("department_aliases") {
    val departmentId = uuid("department_id").references(DepartmentsTable.id)
    val aliasName = varchar("alias_name", 100).uniqueIndex()
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
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
    val prevCourseId = uuid("prev_course_id").nullable()
    val nextCourseId = uuid("next_course_id").nullable()
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
    val assignedProfessorId = uuid("assigned_professor_id").references(UsersTable.id).nullable()
    val courseProfessorId = uuid("course_professor_id").references(CourseProfessorsTable.id).nullable()
}

object ProfessorCategorySettingsTable : UUIDTable("professor_category_settings") {
    val professorId = uuid("professor_id").references(UsersTable.id)
    val courseId = uuid("course_id").references(CoursesTable.id)
    val category = varchar("category", 20)
    val isEnabled = bool("is_enabled").default(true)

    init {
        uniqueIndex(professorId, courseId, category)
    }
}

object ProfessorNotificationSettingsTable : UUIDTable("professor_notification_settings") {
    val professorId = uuid("professor_id").references(UsersTable.id).uniqueIndex()
    val quietHoursEnabled = bool("quiet_hours_enabled").default(false)
    val quietHoursStart = varchar("quiet_hours_start", 10).default("22:00")
    val quietHoursEnd = varchar("quiet_hours_end", 10).default("08:00")
    val notifyOtherProfessorQuestions = bool("notify_other_professor_questions").default(false)
    val emailEnabled = bool("email_enabled").default(true)
    val notificationScope = varchar("notification_scope", 24).default("ALL_SECTIONS") // NONE, ASSIGNED_SECTION, ALL_SECTIONS
    val emailScope = varchar("email_scope", 24).default("ASSIGNED_SECTION") // NONE, ASSIGNED_SECTION, ALL_SECTIONS
    val notificationEmail = varchar("notification_email", 150).nullable()
}

object SystemSettingsTable : org.jetbrains.exposed.sql.Table("system_settings") {
    val settingKey = varchar("setting_key", 64)
    val settingValue = text("setting_value")
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(settingKey)
}

object UserSettingsTable : UUIDTable("user_settings") {
    val userId = uuid("user_id").references(UsersTable.id).uniqueIndex()
    val themeMode = varchar("theme_mode", 20).default("light") // light, dark, green, system
    val fontSize = varchar("font_size", 20).default("md") // sm, md, lg, xl
    val helpLevel = varchar("help_level", 20).default("SOME") // NONE, SOME, ALL
    val exhibitionMode = bool("exhibition_mode").default(false)
}

object QuestionsTable : UUIDTable("questions") {
    val courseId = uuid("course_id").references(CoursesTable.id)
    val studentId = uuid("student_id").references(UsersTable.id)
    val assignedProfessorId = uuid("assigned_professor_id").references(UsersTable.id)
    val courseProfessorId = uuid("course_professor_id").nullable()
    val createdSemester = varchar("created_semester", 20).default("")
    val category = varchar("category", 20) // GRADE, ASSIGNMENT, LECTURE
    val title = varchar("title", 200)
    val rawContent = text("raw_content")
    val refinedContent = text("refined_content")
    val isAnonymous = bool("is_anonymous").default(false)
    val isHidden = bool("is_hidden").default(false)
    val isThreadOpen = bool("is_thread_open").default(false)
    val lastSequenceNumber = integer("last_sequence_number").default(1)
    val isDeletedByAuthor = bool("is_deleted_by_author").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val vectorData = text("vector_data").default("[]")
    val status = varchar("status", 20).default(QuestionStatus.OPEN.name)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}

object AnswersTable : UUIDTable("answers") {
    val questionId = uuid("question_id").references(QuestionsTable.id)
    val professorId = uuid("professor_id").references(UsersTable.id)
    val sequenceNumber = integer("sequence_number").default(2)
    val rawContent = text("raw_content").default("")
    val content = text("content")
    val isAssignedProfessor = bool("is_assigned_professor").default(false)
    val isHidden = bool("is_hidden").default(false)
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
    val answerId = uuid("answer_id").references(AnswersTable.id).nullable()
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

object EmailLogsTable : UUIDTable("email_logs") {
    val recipientEmail = varchar("recipient_email", 150)
    val professorId = uuid("professor_id").nullable()
    val questionId = uuid("question_id").nullable()
    val subject = varchar("subject", 255)
    val body = text("body")
    val status = varchar("status", 50).default("SENT")
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}

object WebPushKeysTable : org.jetbrains.exposed.sql.Table("web_push_keys") {
    val keyName = varchar("key_name", 50)
    val publicKey = varchar("public_key", 255)
    val privateKey = text("private_key")
    val subject = varchar("subject", 255).default("mailto:noreply@kw.ac.kr")
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(keyName)
}

object PushSubscriptionsTable : UUIDTable("push_subscriptions") {
    val userId = uuid("user_id").references(UsersTable.id)
    val endpoint = varchar("endpoint", 1024).uniqueIndex()
    val p256dh = varchar("p256dh", 255)
    val auth = varchar("auth", 128)
    val userAgent = varchar("user_agent", 255).default("")
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
}
