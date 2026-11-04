package com.gonghak.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.sqrt

class SimilarityService {
    private val vectorDimension = 64
    private val json = Json { ignoreUnknownKeys = true }

    private val stopwords = setOf(
        "안녕하십니까", "안녕하세요", "교수님", "학생입니다", "수강생입니다", "수강생", "학생",
        "이번", "학기", "강의", "강의를", "수업을", "수업", "성실히", "수강하고", "있는",
        "감사드립니다", "감사합니다", "다름이", "아니라", "관련하여", "관련", "대해", "대한",
        "조심스럽게", "여쭙고자", "여쭙습니다", "문의드립니다", "질문드립니다", "질문", "문의",
        "바쁘신", "일정", "중에", "시간", "내어", "주셔서", "건강", "유의하시기", "바랍니다",
        "질의의", "학업", "학습", "문의의", "확인", "부탁드립니다", "있으신지", "궁금합니다"
    )

    /**
     * Compute lexical + character 2-gram similarity between two texts (0.0 to 1.0).
     */
    fun computeTextSimilarity(queryText: String, docText: String): Double {
        val qTokens = tokenize(queryText)
        val dTokens = tokenize(docText)
        if (qTokens.isEmpty() || dTokens.isEmpty()) return 0.0

        val qCounts = qTokens.groupingBy { it }.eachCount()
        val dCounts = dTokens.groupingBy { it }.eachCount()

        val sharedKeys = qCounts.keys.intersect(dCounts.keys)
        if (sharedKeys.isEmpty()) return 0.0

        var dot = 0.0
        var normQ = 0.0
        var normD = 0.0

        for ((_, c) in qCounts) normQ += c * c
        for ((_, c) in dCounts) normD += c * c
        for (k in sharedKeys) {
            dot += (qCounts[k] ?: 0) * (dCounts[k] ?: 0)
        }

        val denom = sqrt(normQ) * sqrt(normD)
        if (denom < 1e-9) return 0.0

        val sim = dot / denom
        return (sim.coerceIn(0.0, 1.0) * 1000).toInt() / 1000.0
    }

    /**
     * Compute embedding vector for a given text (Word2Vec / Token Average Embedding).
     */
    fun computeVector(text: String): List<Double> {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) {
            return List(vectorDimension) { 0.0 }
        }

        val vector = DoubleArray(vectorDimension) { 0.0 }

        for (token in tokens) {
            val tokenVec = getTokenVector(token)
            for (i in 0 until vectorDimension) {
                vector[i] += tokenVec[i]
            }
        }

        // L2 normalize
        var normSq = 0.0
        for (v in vector) {
            normSq += v * v
        }
        val norm = sqrt(normSq)

        if (norm > 1e-9) {
            for (i in 0 until vectorDimension) {
                vector[i] /= norm
            }
        }

        return vector.toList()
    }

    fun serializeVector(vector: List<Double>): String {
        return json.encodeToString(vector)
    }

    fun deserializeVector(jsonString: String?): List<Double> {
        if (jsonString.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<Double>>(jsonString)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Calculate cosine similarity between two normalized vectors.
     */
    fun cosineSimilarity(v1: List<Double>, v2: List<Double>): Double {
        if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) return 0.0

        var dot = 0.0
        var norm1Sq = 0.0
        var norm2Sq = 0.0

        for (i in v1.indices) {
            val a = v1[i]
            val b = v2[i]
            dot += a * b
            norm1Sq += a * a
            norm2Sq += b * b
        }

        val denominator = sqrt(norm1Sq) * sqrt(norm2Sq)
        if (denominator < 1e-9) return 0.0

        val sim = dot / denominator
        return (sim.coerceIn(0.0, 1.0) * 1000).toInt() / 1000.0 // round to 3 decimals
    }

    private fun tokenize(text: String): List<String> {
        val cleaned = text.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim()
        val words = cleaned.split("\\s+".toRegex())
            .filter { it.length >= 2 && it !in stopwords }

        // Add character 2-grams from meaningful words for Korean subword matching
        val ngrams = mutableListOf<String>()
        ngrams.addAll(words)
        for (word in words) {
            if (word.length >= 2) {
                for (i in 0..word.length - 2) {
                    val bg = word.substring(i, i + 2)
                    if (bg !in stopwords) {
                        ngrams.add(bg)
                    }
                }
            }
        }
        return ngrams
    }

    private fun getTokenVector(token: String): DoubleArray {
        val vec = DoubleArray(vectorDimension)
        val hash = token.hashCode()
        val rng = java.util.Random(hash.toLong())

        for (i in 0 until vectorDimension) {
            vec[i] = rng.nextGaussian()
        }
        return vec
    }
}
