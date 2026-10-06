package com.gonghak.models

import kotlinx.serialization.Serializable

@Serializable
enum class UserRole {
    STUDENT,
    PROFESSOR
}

@Serializable
enum class QuestionCategory {
    GRADE,       // 성적 (채점 기준, 이의제기 등) - 익명 불가, 유사질답 불가, 같은과목 다른교수 불가
    ASSIGNMENT,  // 과제 (과제 채점 기준, 제출 형식 등) - 익명 가능, 유사질답 가능, 같은과목 다른교수 불가
    LECTURE      // 강의 (강의 내용 질문) - 익명 가능, 유사질답 가능, 같은과목 다른교수 가능
}

@Serializable
enum class NotificationType {
    QUESTION_NEW,
    OTHER_PROFESSOR_QUESTION,
    ANSWER_NEW
}

@Serializable
enum class QuestionStatus {
    OPEN,
    ANSWERED
}
