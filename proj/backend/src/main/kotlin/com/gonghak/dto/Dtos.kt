package com.gonghak.dto

import com.gonghak.models.QuestionCategory
import com.gonghak.models.QuestionStatus
import com.gonghak.models.UserRole
import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null
)

@Serializable
data class ErrorResponse(
    val success: Boolean = false,
    val message: String
)

@Serializable
data class UserDto(
    val id: String,
    val name: String,
    val role: UserRole,
    val identifier: String,
    val department: String,
    val displayTag: String,
    val isDemo: Boolean
)

@Serializable
data class UserSettingDto(
    val userId: String,
    val themeMode: String, // light, dark, system
    val fontSize: String   // small, medium, large
)

@Serializable
data class UpdateUserSettingRequest(
    val themeMode: String? = null,
    val fontSize: String? = null
)

@Serializable
data class ProfessorNotificationSettingDto(
    val professorId: String,
    val quietHoursEnabled: Boolean,
    val quietHoursStart: String,
    val quietHoursEnd: String,
    val notifyOtherProfessorQuestions: Boolean
)

@Serializable
data class UpdateProfessorNotificationSettingRequest(
    val quietHoursEnabled: Boolean? = null,
    val quietHoursStart: String? = null,
    val quietHoursEnd: String? = null,
    val notifyOtherProfessorQuestions: Boolean? = null
)

@Serializable
data class UnifiedSettingDto(
    val userId: String? = null,
    val theme: String = "light",
    val themeMode: String = "light",
    val fontSize: String = "md",
    val dndEnabled: Boolean = false,
    val dndStartTime: String = "22:00",
    val dndEndTime: String = "08:00",
    val coProfNotification: Boolean = true,
    val activeCategories: Map<String, Boolean> = mapOf("grade" to true, "assignment" to true, "lecture" to true)
)

@Serializable
data class UpdateUnifiedSettingRequest(
    val userId: String? = null,
    val theme: String? = null,
    val themeMode: String? = null,
    val fontSize: String? = null,
    val dndEnabled: Boolean? = null,
    val dndStartTime: String? = null,
    val dndEndTime: String? = null,
    val coProfNotification: Boolean? = null,
    val activeCategories: Map<String, Boolean>? = null
)

@Serializable
data class CategorySettingDto(
    val category: QuestionCategory,
    val isEnabled: Boolean
)

@Serializable
data class UpdateCategorySettingRequest(
    val category: QuestionCategory,
    val isEnabled: Boolean
)

@Serializable
data class ProfessorSimpleDto(
    val id: String,
    val name: String,
    val department: String,
    val section: String,
    val schedule: String = ""
)

@Serializable
data class CourseDto(
    val id: String,
    val name: String,
    val code: String,
    val semester: String,
    val college: String = "",
    val department: String = "",
    val major: String = "",
    val completionType: String = "",
    val credit: Int = 3,
    val professors: List<ProfessorSimpleDto> = emptyList(),
    val availableCategories: List<QuestionCategory> = emptyList(),
    val assignedProfessorId: String? = null
)

@Serializable
data class CourseListResponse(
    val total: Long,
    val page: Int,
    val pageSize: Int,
    val courses: List<CourseDto>
)

@Serializable
data class CourseSummaryDto(
    val title: String,
    val semester: String,
    val totalCourses: Long,
    val totalSections: Long,
    val totalProfessors: Long,
    val colleges: List<String> = emptyList()
)

@Serializable
data class CourseDataFile(
    val metadata: CourseDataMetadata,
    val professors: List<ImportedProfessorDto>,
    val courses: List<ImportedCourseDto>
)

@Serializable
data class CourseDataMetadata(
    val title: String,
    val semester: String,
    val generatedAt: String,
    val totalCourses: Int,
    val totalSections: Int,
    val totalProfessors: Int
)

@Serializable
data class ImportedProfessorDto(
    val id: String,
    val name: String,
    val department: String,
    val identifier: String
)

@Serializable
data class ImportedSectionDto(
    val section: String,
    val sectionCode: String = "",
    val fullCode: String = "",
    val professorName: String,
    val professorId: String? = null,
    val schedule: String = "",
    val capacity: Int = 0,
    val remark: String = ""
)

@Serializable
data class ImportedCourseDto(
    val id: String,
    val name: String,
    val code: String,
    val semester: String,
    val college: String = "",
    val department: String = "",
    val major: String = "",
    val generalEduType: String = "",
    val completionType: String = "",
    val credit: Int = 3,
    val hours: Int = 3,
    val sections: List<ImportedSectionDto> = emptyList()
)

@Serializable
data class RefineQuestionRequest(
    val rawContent: String? = null,
    val text: String? = null,
    val courseName: String? = null,
    val category: QuestionCategory? = null,
    val studentInfo: Map<String, String>? = null
) {
    val effectiveContent: String
        get() = text?.takeIf { it.isNotBlank() } ?: rawContent ?: ""
}

@Serializable
data class RefineQuestionResponse(
    val original: String = "",
    val refined: String = "",
    val rawContent: String = original,
    val refinedContent: String = refined,
    val politenessScore: Int = 98,
    val changes: List<String> = listOf("격양된 표현을 공손하고 격식 있는 존댓말로 전환", "학업 문의 표준 서식 적용", "건의 및 배려 표현 추가")
)

@Serializable
data class CreateQuestionRequest(
    val courseId: String,
    val category: QuestionCategory,
    val title: String,
    val rawContent: String? = null,
    val content: String? = null,
    val refinedContent: String? = null,
    val originalContent: String? = null,
    val hasAiRefined: Boolean? = false,
    val studentId: String? = null,
    val studentName: String? = null,
    val studentTag: String? = null,
    val department: String? = null,
    val isAnonymous: Boolean = false
) {
    val effectiveRawContent: String
        get() = originalContent?.takeIf { it.isNotBlank() }
            ?: rawContent?.takeIf { it.isNotBlank() }
            ?: content
            ?: ""

    val effectiveRefinedContent: String?
        get() = refinedContent ?: content
}

@Serializable
data class AnswerDto(
    val id: String,
    val questionId: String,
    val professorId: String,
    val professorName: String,
    val professorDepartment: String,
    val content: String,
    val isAssignedProfessor: Boolean,
    val createdAt: String,
    val authorId: String = professorId,
    val authorName: String = professorName,
    val authorTitle: String = if (isAssignedProfessor) "담당교수" else "같은 과목 교수 (공동 답변)",
    val isPrimaryProf: Boolean = isAssignedProfessor
)

@Serializable
data class QuestionListItemDto(
    val id: String,
    val courseId: String,
    val courseName: String,
    val category: QuestionCategory,
    val title: String,
    val authorDisplayName: String,
    val isAnonymous: Boolean,
    val isMyQuestion: Boolean,
    val status: QuestionStatus,
    val hasAnswers: Boolean,
    val isAnsweredByAssignedProfessor: Boolean,
    val createdAt: String,
    val profId: String? = null,
    val profName: String? = null,
    val content: String = "",
    val originalContent: String = "",
    val studentId: String? = null,
    val studentTag: String? = null,
    val studentName: String? = null,
    val answers: List<AnswerDto> = emptyList()
)

@Serializable
data class QuestionDetailDto(
    val id: String,
    val courseId: String,
    val courseName: String,
    val category: QuestionCategory,
    val title: String,
    val rawContent: String,
    val refinedContent: String,
    val authorDisplayName: String,
    val isAnonymous: Boolean,
    val isMyQuestion: Boolean,
    val assignedProfessorId: String,
    val assignedProfessorName: String,
    val isAnsweredByAssignedProfessor: Boolean,
    val canAnswer: Boolean,
    val answers: List<AnswerDto>,
    val createdAt: String,
    val profId: String = assignedProfessorId,
    val profName: String = assignedProfessorName,
    val content: String = refinedContent.ifBlank { rawContent },
    val originalContent: String = rawContent,
    val studentId: String? = null,
    val studentTag: String? = null,
    val studentName: String? = null
)

@Serializable
data class CreateAnswerRequest(
    val content: String,
    val authorId: String? = null,
    val authorName: String? = null,
    val authorTitle: String? = null,
    val isPrimaryProf: Boolean? = null
)

@Serializable
data class SimilarQuestionDto(
    val id: String,
    val title: String,
    val snippet: String,
    val category: QuestionCategory,
    val similarity: Double,
    val answerCount: Int,
    val topAnswerSnippet: String? = null,
    val content: String = snippet,
    val similarityScore: Int = (similarity * 100).toInt(),
    val answers: List<AnswerDto> = emptyList()
)

@Serializable
data class SimilarQuestionRequest(
    val query: String? = null,
    val text: String? = null,
    val category: QuestionCategory = QuestionCategory.LECTURE,
    val courseId: String? = null,
    val excludeId: String? = null
) {
    val effectiveQuery: String
        get() = text?.takeIf { it.isNotBlank() } ?: query ?: ""
}

@Serializable
data class NotificationDto(
    val id: String,
    val type: String,
    val title: String,
    val message: String,
    val referenceId: String?,
    val isRead: Boolean,
    val isHeld: Boolean,
    val releaseAt: String?,
    val createdAt: String,
    val userId: String? = null,
    val questionId: String? = referenceId
)
