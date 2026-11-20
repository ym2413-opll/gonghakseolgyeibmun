package com.gonghak.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = UserRoleSerializer::class)
enum class UserRole {
    STUDENT,
    PROFESSOR,
    ADMIN
}

object UserRoleSerializer : KSerializer<UserRole> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UserRole", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UserRole) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): UserRole {
        val raw = decoder.decodeString().trim().uppercase()
        return runCatching { UserRole.valueOf(raw) }.getOrDefault(UserRole.STUDENT)
    }
}

@Serializable(with = QuestionCategorySerializer::class)
enum class QuestionCategory {
    GRADE,       // 성적 (채점 기준, 이의제기 등) - 익명 불가, 유사질답 불가, 같은과목 다른교수 불가
    ASSIGNMENT,  // 과제 (과제 채점 기준, 제출 형식 등) - 익명 가능, 유사질답 가능, 같은과목 다른교수 불가
    LECTURE      // 강의 (강의 내용 질문) - 익명 가능, 유사질답 가능, 같은과목 다른교수 가능
}

object QuestionCategorySerializer : KSerializer<QuestionCategory> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("QuestionCategory", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: QuestionCategory) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): QuestionCategory {
        val raw = decoder.decodeString().trim().uppercase()
        return runCatching { QuestionCategory.valueOf(raw) }.getOrDefault(QuestionCategory.LECTURE)
    }
}

@Serializable(with = NotificationTypeSerializer::class)
enum class NotificationType {
    QUESTION_NEW,
    OTHER_PROFESSOR_QUESTION,
    ANSWER_NEW,
    QUESTION_DELETED
}

object NotificationTypeSerializer : KSerializer<NotificationType> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("NotificationType", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: NotificationType) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): NotificationType {
        val raw = decoder.decodeString().trim().uppercase()
        return runCatching { NotificationType.valueOf(raw) }.getOrDefault(NotificationType.QUESTION_NEW)
    }
}

@Serializable(with = QuestionStatusSerializer::class)
enum class QuestionStatus {
    OPEN,
    ANSWERED
}

object QuestionStatusSerializer : KSerializer<QuestionStatus> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("QuestionStatus", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: QuestionStatus) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): QuestionStatus {
        val raw = decoder.decodeString().trim().uppercase()
        return runCatching { QuestionStatus.valueOf(raw) }.getOrDefault(QuestionStatus.OPEN)
    }
}
