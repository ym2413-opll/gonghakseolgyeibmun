package com.gonghak.service

import com.gonghak.models.QuestionCategory
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

data class RefinedResult(
    val title: String,
    val content: String
)

class AiRefineService(
    private val apiKey: String? = null,
    private val workerManager: BackgroundWorkerManager = BackgroundWorkerManager()
) {
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
        rawTitle: String? = null,
        courseName: String? = null,
        category: QuestionCategory? = null
    ): RefinedResult {
        val effectiveApiKey = apiKey?.takeIf { it.isNotBlank() } ?: System.getenv("GEMINI_API_KEY")

        if (!effectiveApiKey.isNullOrBlank()) {
            try {
                return withContext(workerManager.aiDispatcher) {
                    withTimeout(12_000L) {
                        callGeminiApi(effectiveApiKey, rawContent, rawTitle, courseName, category)
                    }
                }
            } catch (e: Exception) {
                logger.warn("[AiWorker] Failed to call Gemini API, falling back to rule-based refiner: ${e.message}")
            }
        }

        // Fallback rule-based polite refiner runs on dedicated AI worker thread
        return withContext(workerManager.aiDispatcher) {
            fallbackRefine(rawContent, rawTitle, courseName, category)
        }
    }

    suspend fun refineQuestion(
        rawContent: String,
        courseName: String? = null,
        category: QuestionCategory? = null
    ): String = refineQuestion(rawContent, null, courseName, category).content

    private suspend fun callGeminiApi(
        key: String,
        rawContent: String,
        rawTitle: String?,
        courseName: String?,
        category: QuestionCategory?
    ): RefinedResult {
        val preferredModel = System.getenv("GEMINI_MODEL")
            ?.trim()
            ?.trim('"', '\'')
            ?.removePrefix("models/")
            ?.takeIf { it.isNotBlank() }
            ?: "gemini-2.5-flash"
        val modelsToTry = listOf(preferredModel, "gemini-2.5-flash", "gemini-2.0-flash").distinct()

        val categoryDesc = when (category) {
            QuestionCategory.GRADE -> "성적 및 평가 관련 문의"
            QuestionCategory.ASSIGNMENT -> "과제 및 과제 제출 관련 문의"
            QuestionCategory.LECTURE -> "강의 내용 및 수업 관련 문의"
            null -> "수업 관련 문의"
        }

        val prompt = """
            당신은 대학교 강의 질의응답 시스템의 'AI 질문 도우미'입니다.
            학생이 작성한 거칠거나 비격식적인 제목과 질문 원문을 읽고, 교수님께 예의 바르고 명확하게 전달될 수 있도록 '제목'과 '본문'을 모두 교정 및 윤색(Refine)해주세요.
            
            [필수 요구사항]
            1. 학생의 질문 원문 텍스트를 따옴표나 [문의 내용] 등의 형태로 그대로 복사하거나 인용하여 포함하지 마세요.
               - 학생의 질문 의도와 핵심 내용을 완전히 파악하여, 본문 전체를 정중하고 격식 있는 대학생 문의 표현으로 완전히 새로 작성(의역 및 윤색)하세요.
               - 원문의 비격식적이거나 날것의 표현은 물론, 원문 문장 자체가 그대로 노출되지 않도록 자연스러운 문맥으로 변환해야 합니다.
            2. 제목(Title)도 학술적이고 정중한 질의 제목 양식으로 품격 있게 윤색하세요. (예: '[과제 문의] 과제 2번 프로젝트 제출 기한 유예 가능 여부 문의의 건')
            3. 본문 첫인사("안녕하십니까, [교수님 성함] 교수님.")와 끝인사("날씨가 쌀쌀한데 건강 유의하시기 바랍니다. 감사합니다.")를 자연스럽게 포함하세요.
            4. 학생의 학과, 학번, 이름 및 강의명(${courseName ?: "[강의명]"}) 정보가 있다면 적절히 배치하세요.
            5. 반드시 아래 JSON 형식으로만 응답하세요 (마크다운 코드블록이나 백틱 없이 순수 JSON 문자열만 출력):
            {
              "title": "윤색된 제목",
              "content": "윤색된 본문 내용"
            }
            
            [원문 카테고리]: $categoryDesc
            [학생 작성 원문 제목]: ${rawTitle?.ifBlank { "제목 없음" } ?: "제목 없음"}
            [학생 작성 원문 본문]:
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

        var lastStatus: HttpStatusCode? = null
        for (model in modelsToTry) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }
            lastStatus = response.status

            if (response.status.isSuccess()) {
                val responseText = response.body<String>()
                val json = Json.parseToJsonElement(responseText).jsonObject
                val candidates = json["candidates"]?.jsonArray
                val firstCandidate = candidates?.firstOrNull()?.jsonObject
                val parts = firstCandidate?.get("content")?.jsonObject?.get("parts")?.jsonArray
                val text = parts?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                if (!text.isNullOrBlank()) {
                    val cleanedText = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    try {
                        val parsed = Json.parseToJsonElement(cleanedText).jsonObject
                        val title = parsed["title"]?.jsonPrimitive?.contentOrNull?.trim()
                        val content = parsed["content"]?.jsonPrimitive?.contentOrNull?.trim()
                        if (!content.isNullOrBlank()) {
                            val finalTitle = title?.takeIf { it.isNotBlank() } ?: fallbackRefine(rawContent, rawTitle, courseName, category).title
                            return RefinedResult(finalTitle, content)
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to parse Gemini JSON output, using clean text directly: ${e.message}")
                        val fallback = fallbackRefine(rawContent, rawTitle, courseName, category)
                        return RefinedResult(fallback.title, cleanedText)
                    }
                }
            } else if (response.status != HttpStatusCode.NotFound) {
                break
            }
        }

        throw RuntimeException("Gemini API response unsuccessful: status=$lastStatus")
    }

    private fun fallbackRefine(
        rawContent: String,
        rawTitle: String?,
        courseName: String?,
        category: QuestionCategory?
    ): RefinedResult {
        val courseText = courseName?.let { "[$it]" } ?: "[강의명]"
        val catName = when (category) {
            QuestionCategory.GRADE -> "성적"
            QuestionCategory.ASSIGNMENT -> "과제"
            QuestionCategory.LECTURE -> "강의"
            null -> if (rawContent.contains("성적")) "성적" else if (rawContent.contains("과제")) "과제" else "강의"
        }

        val cleanTitle = rawTitle?.replace(Regex("[\\[\\]!~?]+"), "")?.trim()?.takeIf { it.isNotBlank() }

        // 1. Title refinement (정중하고 명확한 제목 윤색)
        val refinedTitle = when {
            catName == "성적" -> cleanTitle?.let { "[$catName 문의] $it 피드백 및 평가 기준 확인의 건" }
                ?: "[$catName 문의] 2026학년도 2학기 최종 평가 결과 및 피드백 문의의 건"
            catName == "과제" -> cleanTitle?.let { "[$catName 문의] $it 관련 일정 및 세부 사항 문의의 건" }
                ?: "[$catName 문의] 과제 수행 관련 일정 및 세부 사항 문의의 건"
            else -> cleanTitle?.let { "[$catName 문의] $it 개념 이해 및 학습 질의의 건" }
                ?: "[$catName 문의] 수업 내용 개념 이해 및 학습 관련 질의의 건"
        }

        // 2. Content refinement (학생의 원문 거친 문장을 직접 인용하지 않고 정중한 학술 문장으로 전면 재구성)
        val refinedContent = when {
            catName == "성적" || rawContent.contains("성적") -> {
                "안녕하십니까, 교수님. 이번 학기 $courseText 을(를) 수강하고 있는 학생입니다. " +
                "한 학기 동안 열정적인 강의와 가르침을 주셔서 진심으로 감사드립니다.\n\n" +
                "다름이 아니라 이번 학기 최종 성적 평가 결과와 관련하여, 스스로 부족했던 부분을 점검하고 향후 보완 방향을 확인하고자 조심스럽게 피드백을 여쭙고자 연락을 드립니다.\n\n" +
                "혹시 제가 어떤 평가 항목에서 미흡했는지 조언을 여쭈어보아도 괜찮을지 여쭙고자 합니다. " +
                "아울러 현재 부여된 성적과 관련하여 재수강 기준 충족 여부나 성적 산출 기준에 관해 교수님의 소중한 가르침을 청하고자 하오니, 결례를 무릅쓰고 조심스레 검토를 부탁드립니다.\n\n" +
                "학기 말 바쁘신 일정 중에 번거로운 문의를 드려 송구합니다. 건강 유의하시기 바랍니다. 감사합니다."
            }
            catName == "과제" || rawContent.contains("과제") || rawContent.contains("제출") || rawContent.contains("기한") -> {
                "안녕하십니까, 교수님. $courseText 강의를 성실히 수강하고 있는 학생입니다.\n\n" +
                "평소 깊이 있고 유익한 강의를 이끌어 주셔서 감사드립니다. " +
                "현재 안내해 주신 과제 프로젝트를 성실히 수행하는 과정에서, 핵심 요구사항 구현 및 일정과 관련하여 부득이하게 조심스럽게 문의를 드리게 되었습니다.\n\n" +
                "보다 완성도 높은 결과물과 분석 보고서를 작성하여 제출하고자 하오니, 혹시 제출 기한을 다소 유예해 주시거나 추가 보완 기회를 주시는 것이 강의 운영 일정상 가능한지 조심스럽게 여쭙고자 합니다.\n\n" +
                "학업 일정에 차질을 드려 송구하오며, 교수님의 소중한 지도 편달에 항상 감사드립니다."
            }
            else -> {
                "안녕하십니까, 교수님. $courseText 강의를 수강 중인 학생입니다.\n\n" +
                "수업 시간에 다루어 주신 핵심 개념과 이론을 복습하던 중, 개념 정리와 원리 이해에 다소 부족한 부분이 있어 조심스럽게 가르침을 청하고자 질문을 올립니다.\n\n" +
                "강의 자료와 관련 참고 문헌을 살펴보았으나 보다 명확한 학술적 직관이 필요하여, 시간 되실 때 핵심 원리에 대한 조언이나 추천 참고 자료를 말씀해 주시면 학업에 큰 도움이 될 것 같습니다.\n\n" +
                "바쁘신 연구 및 강의 일정 중 질문을 드려 송구하오며, 늘 건강 유의하시기 바랍니다. 감사합니다."
            }
        }

        return RefinedResult(refinedTitle, refinedContent)
    }

    fun close() {
        runCatching { httpClient.close() }
    }
}
