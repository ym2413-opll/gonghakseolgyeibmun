package com.gonghak.service

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PasswordSecurity {
    data class VerifiedAuthToken(
        val userId: UUID,
        val issuedAtEpochSecond: Long,
        val tokenVersion: Int = 0
    )

    private val logger = LoggerFactory.getLogger(PasswordSecurity::class.java)
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 65536
    private const val KEY_LENGTH = 256
    private const val SALT_BYTES = 16
    private const val TOKEN_TTL_SECONDS = 7L * 24L * 3600L // 7 days

    private val secureRandom = SecureRandom()
    private val hmacSecret: ByteArray by lazy {
        val isProd = System.getenv("PRODUCTION_MODE")?.trim()?.equals("true", ignoreCase = true) == true
        val rawSecret = System.getenv("AUTH_TOKEN_SECRET")?.trim()
        if (isProd && (rawSecret.isNullOrEmpty() || rawSecret.length < 32)) {
            throw IllegalStateException("AUTH_TOKEN_SECRET (at least 32 characters) must be configured in PRODUCTION_MODE.")
        }
        val envSecret = rawSecret?.takeIf { it.length >= 16 }
        if (envSecret != null) {
            envSecret.toByteArray(Charsets.UTF_8)
        } else {
            logger.warn("[Security] AUTH_TOKEN_SECRET is not set or too short; generating ephemeral per-process HMAC key.")
            ByteArray(32).also { secureRandom.nextBytes(it) }
        }
    }

    /**
     * Hash password using PBKDF2WithHmacSHA256 and random 16-byte salt.
     */
    fun hashPassword(rawPassword: String): String {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        val hash = pbkdf2(rawPassword.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val saltB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(salt)
        val hashB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        return "pbkdf2_sha256$$ITERATIONS$$saltB64$$hashB64"
    }

    /**
     * Verify password against stored PBKDF2 hash using constant-time comparison.
     */
    fun verifyPassword(rawPassword: String, storedHash: String?): Boolean {
        if (storedHash.isNullOrBlank()) return false
        val parts = storedHash.split("$")
        if (parts.size != 4 || parts[0] != "pbkdf2_sha256") return false

        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations < 10000 || iterations > 1_000_000) return false
        val salt = runCatching { Base64.getUrlDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expectedHash = runCatching { Base64.getUrlDecoder().decode(parts[3]) }.getOrNull() ?: return false

        val actualHash = pbkdf2(rawPassword.toCharArray(), salt, iterations, expectedHash.size * 8)
        return MessageDigest.isEqual(expectedHash, actualHash)
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, bits: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, bits)
        return try {
            val skf = SecretKeyFactory.getInstance(ALGORITHM)
            skf.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Issue an HMAC-SHA256 signed authentication token for a user UUID and token version.
     */
    fun generateAuthToken(userId: UUID, tokenVersion: Int = 0): String {
        val issuedAt = Instant.now().epochSecond
        val payload = "${userId}|$issuedAt|$tokenVersion"
        val payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
        val sigB64 = signPayload(payloadB64)
        return "$payloadB64.$sigB64"
    }

    /**
     * Verify an HMAC-SHA256 signed authentication token, check expiration, and extract claims.
     */
    fun verifyAuthTokenClaims(token: String?): VerifiedAuthToken? {
        if (token.isNullOrBlank()) return null
        val trimmed = token.trim()
        if (!trimmed.contains(".")) return null

        val parts = trimmed.split(".")
        if (parts.size != 2) return null

        val payloadB64 = parts[0]
        val providedSig = parts[1]
        val expectedSig = signPayload(payloadB64)
        val isValid = MessageDigest.isEqual(
            expectedSig.toByteArray(Charsets.UTF_8),
            providedSig.toByteArray(Charsets.UTF_8)
        )
        if (!isValid) return null

        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(payloadB64), Charsets.UTF_8)
        }.getOrNull() ?: return null

        val segments = decoded.split("|")
        if (segments.size != 2 && segments.size != 3) return null
        val userId = runCatching { UUID.fromString(segments[0]) }.getOrNull() ?: return null
        val issuedAt = segments[1].toLongOrNull() ?: return null
        val version = if (segments.size == 3) (segments[2].toIntOrNull() ?: return null) else 0

        val now = Instant.now().epochSecond
        // Reject tokens issued in the future (> 60s clock skew) or expired (> 7 days)
        if (issuedAt > now + 60L || (now - issuedAt) > TOKEN_TTL_SECONDS) {
            return null
        }
        return VerifiedAuthToken(
            userId = userId,
            issuedAtEpochSecond = issuedAt,
            tokenVersion = version
        )
    }

    /**
     * Verify an HMAC-SHA256 signed authentication token, check expiration, and extract the user UUID.
     */
    fun verifyAuthToken(token: String?): UUID? = verifyAuthTokenClaims(token)?.userId

    private fun signPayload(payloadB64: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacSecret, "HmacSHA256"))
        val sigBytes = mac.doFinal(payloadB64.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes)
    }

    /**
     * Strip CR, LF, and NUL characters to prevent SMTP/HTTP header injection.
     */
    fun sanitizeHeaderValue(input: String?): String {
        if (input == null) return ""
        return input.replace(Regex("[\\r\\n\\u0000]"), "").trim()
    }

    /**
     * Input sanitization to strip dangerous control characters.
     */
    fun sanitizeInput(input: String?): String {
        if (input == null) return ""
        return input
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), "")
            .trim()
    }

    fun validateUsername(username: String): String {
        val clean = sanitizeInput(username)
        if (!Regex("^[a-zA-Z0-9_.-]{3,30}$").matches(clean)) {
            throw IllegalArgumentException("아이디(ID)는 영문, 숫자, 밑줄(_), 하이픈(-), 마침표(.) 조합 3~30자여야 합니다.")
        }
        return clean
    }

    fun validatePassword(password: String) {
        if (password.length < 6 || password.length > 100) {
            throw IllegalArgumentException("비밀번호는 6자 이상 100자 이하로 입력해 주세요.")
        }
    }

    fun validateEmail(email: String): String {
        val clean = sanitizeInput(email).lowercase()
        if (!Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$").matches(clean)) {
            throw IllegalArgumentException("유효한 이메일 주소 형식이 아닙니다.")
        }
        return clean
    }
}
