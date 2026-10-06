package com.gonghak.service

import com.gonghak.models.QuestionCategory
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class AiRefineService(private val apiKey: String? = null) {
    private val logger = LoggerFactory.getLogger(AiRefineService::class.java)

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    suspend fun refineQuestion(
        rawContent: String,
        courseName: String? = null,
        category: QuestionCategory? = null
    ): String {
        val effectiveApiKey = apiKey?.takeIf { it.isNotBlank() } ?: System.getenv("GEMINI_API_KEY")

        if (!effectiveApiKey.isNullOrBlank()) {
            try {
                return callGeminiApi(effectiveApiKey, rawContent, courseName, category)
            } catch (e: Exception) {
                logger.warn("Failed to call Gemini API, falling back to rule-based refiner: ${e.message}")
            }
        }

        // Fallback rule-based polite refiner
        return fallbackRefine(rawContent, courseName, category)
    }

    private suspend fun callGeminiApi(
        key: String,
        rawContent: String,
        courseName: String?,
        category: QuestionCategory?
    ): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$key"

        val categoryDesc = when (category) {
            QuestionCategory.GRADE -> "성적 및 평가 관련 문의"
            QuestionCategory.ASSIGNMENT -> "과제 및 과제 제출 관련 문의"
            QuestionCategory.LECTURE -> "강의 내용 및 수업 관련 문의"
            null -> "수업 관련 문의"
        }

        val prompt = """
            당신은 대학교 강의 질의응답 시스템의 'AI 질문 도우미'입니다.
            학생이 작성한 거칠거나 비격식적인 원문을 읽고, 교수님께 예의 바르고 정중하게 보내는 격식 있는 대학생 문의문(이메일/게시글 형식)으로 교정 및 윤문해주세요.
            
            [규칙]
            1. 첫인사("안녕하십니까, [교수님 성함] 교수님.")와 끝인사("날씨가 쌀쌀한데 건강 유의하시기 바랍니다. 감사합니다.")를 자연스럽게 포함하세요.
            2. 학생의 학과, 학번, 이름 및 강의명(${courseName ?: "[강의명]"}) 플레이스홀더를 적절히 배치하세요.
            3. 원문의 핵심 의도(성적 이의, 재수강 조건 문의, 과제 기한, 개념 질문 등)를 결례가 되지 않도록 공손하고 품격 있는 어조로 변환하세요.
            4. 오직 윤문된 최종 텍스트만 출력하세요. 부가적인 해설이나 마크다운 백틱은 생략하세요.
            
            [원문 카테고리]: $categoryDesc
            [학생 원문]:
            $rawContent
        """.trimIndent()

        val requestBody = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", prompt)
                        }
                    }
                }
            }
        }

        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }

        if (response.status.isSuccess()) {
            val responseText = response.body<String>()
            val json = Json.parseToJsonElement(responseText).jsonObject
            val candidates = json["candidates"]?.jsonArray
            val firstCandidate = candidates?.firstOrNull()?.jsonObject
            val parts = firstCandidate?.get("content")?.jsonObject?.get("parts")?.jsonArray
            val text = parts?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
            if (!text.isNullOrBlank()) {
                return text.trim()
            }
        }

        throw RuntimeException("Gemini API response unsuccessful: status=${response.status}")
    }

    private fun fallbackRefine(
        rawContent: String,
        courseName: String?,
        category: QuestionCategory?
    ): String {
        val courseText = courseName?.let { "[$it]" } ?: "[강의명]"

        // Specific handling for common demo examples
        if (rawContent.contains("성적") && (rawContent.contains("말이 되냐") || rawContent.contains("C로 내려줘") || rawContent.contains("재수강"))) {
            return "안녕하십니까, 교수님. 이번 학기 $courseText 을(를) 수강하고 있는 학생입니다. " +
                    "한 학기 동안 열정적인 강의와 가르침을 주셔서 진심으로 감사드립니다.\n\n" +
                    "다름이 아니라 이번 학기 최종 성적을 확인하였으나, 제가 예상했던 결과보다 다소 아쉬운 성적을 받게 되어 조심스럽게 문의를 드립니다. " +
                    "혹시 제가 어떤 평가 항목에서 부족했는지 피드백을 여쭈어보아도 괜찮을지 여쭙고자 합니다. " +
                    "아울러 현재 부여된 성적으로는 본교 규정상 추후 재수강을 통한 보완이 어려운 상황입니다. " +
                    "만약 성적 상향이 어렵다면, 부족한 부분을 다시 성실히 공부하여 채울 수 있도록 재수강 요건에 맞추어 성적 조정을 검토해 주시는 것이 학칙상 가능한지 결례를 무릅쓰고 조심스레 여쭙습니다.\n\n" +
                    "학기 말 바쁘신 일정 중에 번거로운 문의를 드려 송구합니다. 건강 유의하시기 바랍니다. 감사합니다."
        }

        if (rawContent.contains("과제") || rawContent.contains("제출") || rawContent.contains("기한")) {
            return "안녕하십니까, 교수님. $courseText 강의를 수강 중인 학생입니다.\n\n" +
                    "항상 유익하고 열정적인 강의를 진행해 주셔서 감사드립니다. " +
                    "과제 진행 및 제출과 관련하여 명확히 확인하고자 조심스럽게 질문을 남깁니다.\n" +
                    "문의 내용: \"${rawContent.trim()}\"\n\n" +
                    "바쁘신 일정 중에 번거롭게 해 드려 죄송하며, 확인해 주시면 성실히 과제를 수행하여 제출하도록 하겠습니다. 감사합니다."
        }

        // Generic refinement for any other inquiries
        return "안녕하십니까, 교수님. $courseText 강의를 수강 중인 학생입니다.\n\n" +
                "강의를 수강하며 궁금한 점이 생겨 조심스럽게 여쭙고자 문의를 남깁니다.\n" +
                "\"${rawContent.trim()}\"\n\n" +
                "강의 준비와 연구로 바쁘신 와중에 질문을 드려 송구합니다. 시간 나실 때 확인해 주시면 감사하겠습니다. 늘 건강하시기 바랍니다."
    }
}
