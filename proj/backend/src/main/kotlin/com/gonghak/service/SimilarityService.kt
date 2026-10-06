package com.gonghak.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.sqrt

class SimilarityService {
    private val vectorDimension = 64
    private val json = Json { ignoreUnknownKeys = true }

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
        val cleaned = text.replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim()
        val words = cleaned.split("\\s+".toRegex()).filter { it.length >= 2 }
        
        // Also add character 2-grams for rich subword representation in Korean
        val ngrams = mutableListOf<String>()
        ngrams.addAll(words)
        for (word in words) {
            if (word.length >= 2) {
                for (i in 0..word.length - 2) {
                    ngrams.add(word.substring(i, i + 2))
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
