package com.gonghak.service

import com.gonghak.config.DatabaseFactory.dbQuery
import com.gonghak.config.EnvConfig
import com.gonghak.dto.SubscribePushRequest
import com.gonghak.models.PushSubscriptionsTable
import com.gonghak.models.UsersTable
import com.gonghak.models.WebPushKeysTable
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class PendingPushJob(
    val userId: UUID,
    val notificationId: UUID,
    val title: String,
    val message: String,
    val questionId: UUID? = null
)

data class VapidKeyMaterial(
    val publicKeyBase64Url: String,
    val publicKeyUncompressed: ByteArray,
    val privateKey: PrivateKey,
    val subject: String
)

@Serializable
private data class PushNotificationPayload(
    val id: String,
    val title: String,
    val body: String,
    val url: String,
    val questionId: String? = null,
    val tag: String,
    val icon: String = "/favicon.png"
)

@Serializable
private data class VapidJwtClaims(
    val aud: String,
    val exp: Long,
    val sub: String
)

/**
 * Delivers W3C Web Push notifications (VAPID ES256 RFC 8292 + aes128gcm RFC 8291) so users
 * continue receiving OS/browser alerts even when all portal tabs are closed.
 */
class WebPushService(
    private val workerManager: BackgroundWorkerManager = BackgroundWorkerManager(),
    private val httpClient: HttpClient = HttpClient(CIO) { followRedirects = false },
    private val dnsResolver: (String) -> Array<InetAddress> = { host -> InetAddress.getAllByName(host) }
) {
    private val logger = LoggerFactory.getLogger(WebPushService::class.java)
    private val b64UrlEnc = Base64.getUrlEncoder().withoutPadding()
    private val b64UrlDec = Base64.getUrlDecoder()
    private val secureRandom = SecureRandom()
    private val json = Json { encodeDefaults = true }

    private val ecParamSpec: ECParameterSpec by lazy {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        params.getParameterSpec(ECParameterSpec::class.java)
    }

    companion object {
        const val MAX_SUBSCRIPTIONS_PER_USER = 10
        private val DEFAULT_ALLOWED_PUSH_HOSTS = setOf(
            "fcm.googleapis.com",
            "updates.push.services.mozilla.com",
            "web.push.apple.com"
        )
        private val DEFAULT_ALLOWED_PUSH_SUFFIXES = listOf(
            ".fcm.googleapis.com",
            ".push.services.mozilla.com",
            ".push.apple.com",
            ".notify.windows.com",
            ".push.samsungosp.com"
        )
    }

    @Volatile
    private var cachedVapidKeys: VapidKeyMaterial? = null

    suspend fun getPublicKeyBase64Url(): String = getOrCreateVapidKeys().publicKeyBase64Url

    private fun resolveAtRestKeyBytes(): ByteArray? {
        val raw = EnvConfig.get("VAPID_KEY_ENCRYPTION_SECRET")?.trim()?.takeIf { it.length >= 16 }
            ?: EnvConfig.get("AUTH_TOKEN_SECRET")?.trim()?.takeIf { it.length >= 16 }
            ?: return null
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    }

    private fun protectPrivateKeyForStorage(rawPrivB64: String): String {
        val keyBytes = resolveAtRestKeyBytes() ?: return rawPrivB64
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(rawPrivB64.toByteArray(Charsets.UTF_8))
        return "enc:v1:${b64UrlEnc.encodeToString(iv)}:${b64UrlEnc.encodeToString(sealed)}"
    }

    private fun unprotectStoredPrivateKey(storedValue: String): String {
        if (!storedValue.startsWith("enc:v1:")) return storedValue
        val parts = storedValue.split(":")
        require(parts.size == 4) { "유효하지 않은 암호화된 VAPID 개인키 형식입니다." }
        val keyBytes = resolveAtRestKeyBytes()
            ?: throw IllegalStateException("암호화된 VAPID 개인키를 복호화할 AUTH_TOKEN_SECRET이 필요합니다.")
        val iv = b64UrlDec.decode(parts[2])
        val sealed = b64UrlDec.decode(parts[3])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(sealed), Charsets.UTF_8)
    }

    internal suspend fun getOrCreateVapidKeys(): VapidKeyMaterial {
        cachedVapidKeys?.let { return it }

        val envPub = EnvConfig.get("VAPID_PUBLIC_KEY")?.trim()?.takeIf { it.isNotEmpty() }
        val envPriv = EnvConfig.get("VAPID_PRIVATE_KEY")?.trim()?.takeIf { it.isNotEmpty() }
        val subject = EnvConfig.get("VAPID_SUBJECT")?.trim()?.takeIf { it.isNotEmpty() } ?: "mailto:noreply@kw.ac.kr"

        if (envPub != null && envPriv != null) {
            val material = loadKeyMaterialFromEncoded(envPub, envPriv, subject)
            cachedVapidKeys = material
            return material
        }

        val loaded = dbQuery(workerManager.notificationDispatcher) {
            val existing = WebPushKeysTable.select { WebPushKeysTable.keyName eq "default" }.singleOrNull()
            if (existing != null) {
                loadKeyMaterialFromEncoded(
                    existing[WebPushKeysTable.publicKey],
                    unprotectStoredPrivateKey(existing[WebPushKeysTable.privateKey]),
                    existing[WebPushKeysTable.subject]
                )
            } else {
                val generated = generateNewVapidKeyPair(subject)
                val rawPrivB64 = b64UrlEnc.encodeToString(generated.privateKey.encoded)
                WebPushKeysTable.insert {
                    it[keyName] = "default"
                    it[publicKey] = generated.publicKeyBase64Url
                    it[privateKey] = protectPrivateKeyForStorage(rawPrivB64)
                    it[WebPushKeysTable.subject] = subject
                    it[createdAt] = Instant.now()
                }
                generated
            }
        }
        cachedVapidKeys = loaded
        return loaded
    }

    private fun generateNewVapidKeyPair(subject: String): VapidKeyMaterial {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val kp = kpg.generateKeyPair()
        val pubBytes = toUncompressedP256Bytes(kp.public as ECPublicKey)
        return VapidKeyMaterial(
            publicKeyBase64Url = b64UrlEnc.encodeToString(pubBytes),
            publicKeyUncompressed = pubBytes,
            privateKey = kp.private,
            subject = subject
        )
    }

    private fun loadKeyMaterialFromEncoded(pubB64: String, privB64: String, subject: String): VapidKeyMaterial {
        val pubBytes = b64UrlDec.decode(pubB64)
        require(pubBytes.size == 65 && pubBytes[0] == 0x04.toByte()) { "유효하지 않은 VAPID 공개키 형식입니다." }
        val privBytes = b64UrlDec.decode(privB64)
        val privKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privBytes))
        return VapidKeyMaterial(
            publicKeyBase64Url = pubB64,
            publicKeyUncompressed = pubBytes,
            privateKey = privKey,
            subject = subject
        )
    }

    private fun isAllowedPushHost(host: String): Boolean {
        if (host in DEFAULT_ALLOWED_PUSH_HOSTS) return true
        if (DEFAULT_ALLOWED_PUSH_SUFFIXES.any { host.endsWith(it) && host.length > it.length }) return true
        val extraHosts = EnvConfig.get("WEBPUSH_ALLOWED_HOSTS")
            ?.split(",")
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        return extraHosts.any { allowed ->
            if (allowed.startsWith("*.")) {
                val suffix = allowed.removePrefix("*")
                host.endsWith(suffix) && host.length > suffix.length
            } else {
                host == allowed
            }
        }
    }

    fun validatePushEndpoint(rawEndpoint: String): URI {
        val trimmed = rawEndpoint.trim()
        if (trimmed.isBlank() || trimmed.length > 1024 || trimmed.any { it.code <= 0x1F || it.code == 0x7F }) {
            throw IllegalArgumentException("유효하지 않은 푸시 엔드포인트입니다.")
        }
        val uri = runCatching { URI(trimmed) }.getOrElse {
            throw IllegalArgumentException("유효하지 않은 푸시 엔드포인트 URL입니다.")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw IllegalArgumentException("웹 푸시 엔드포인트는 HTTPS만 허용됩니다.")
        }
        val host = uri.host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("유효하지 않은 푸시 호스트입니다.")
        if (uri.port != -1 && uri.port != 443) {
            throw IllegalArgumentException("허용되지 않는 푸시 포트입니다.")
        }
        if (!isAllowedPushHost(host)) {
            throw IllegalArgumentException("허용되지 않은 푸시 서비스 도메인입니다.")
        }

        val addresses = runCatching { dnsResolver(host) }.getOrElse {
            throw IllegalArgumentException("푸시 호스트 주소를 확인할 수 없습니다.")
        }
        if (addresses.isEmpty()) {
            throw IllegalArgumentException("푸시 호스트 주소를 확인할 수 없습니다.")
        }
        for (addr in addresses) {
            if (!isPublicRoutableAddress(addr)) {
                throw IllegalArgumentException("내부/사설 네트워크 주소로의 푸시 등록은 차단됩니다.")
            }
        }
        return uri
    }

    private fun isPublicRoutableAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return false
        }
        val raw = addr.address
        if (raw.size == 4) {
            val b0 = raw[0].toInt() and 0xFF
            val b1 = raw[1].toInt() and 0xFF
            // CGNAT 100.64.0.0/10, 0.0.0.0/8, benchmark 198.18.0.0/15
            if (b0 == 0 || (b0 == 100 && b1 in 64..127) || (b0 == 198 && b1 in 18..19)) {
                return false
            }
        } else if (addr is Inet6Address) {
            val firstByte = raw[0].toInt() and 0xFF
            // Unique local addresses fc00::/7
            if ((firstByte and 0xFE) == 0xFC) {
                return false
            }
        }
        return true
    }

    suspend fun subscribe(
        userId: UUID,
        request: SubscribePushRequest,
        userAgent: String = ""
    ): Boolean {
        validatePushEndpoint(request.endpoint)
        val p256dhClean = request.keys.p256dh.trim()
        val authClean = request.keys.auth.trim()
        val pubBytes = runCatching { b64UrlDec.decode(p256dhClean) }.getOrNull()
            ?: throw IllegalArgumentException("유효하지 않은 p256dh 키입니다.")
        val authBytes = runCatching { b64UrlDec.decode(authClean) }.getOrNull()
            ?: throw IllegalArgumentException("유효하지 않은 auth 키입니다.")

        fromUncompressedP256Bytes(pubBytes) // verifies curve validity
        if (authBytes.size != 16) {
            throw IllegalArgumentException("유효하지 않은 auth 비밀키 길이입니다.")
        }

        val cleanEndpoint = request.endpoint.trim()
        val cleanUa = userAgent.trim().take(255)
        val now = Instant.now()

        dbQuery(workerManager.notificationDispatcher) {
            val existing = PushSubscriptionsTable.select { PushSubscriptionsTable.endpoint eq cleanEndpoint }.singleOrNull()
            if (existing != null) {
                PushSubscriptionsTable.update({ PushSubscriptionsTable.endpoint eq cleanEndpoint }) {
                    it[PushSubscriptionsTable.userId] = userId
                    it[p256dh] = p256dhClean
                    it[auth] = authClean
                    it[PushSubscriptionsTable.userAgent] = cleanUa
                    it[updatedAt] = now
                }
            } else {
                // Enforce per-user subscription cap by evicting oldest device subscriptions beyond MAX_SUBSCRIPTIONS_PER_USER.
                val userSubIds = PushSubscriptionsTable.slice(PushSubscriptionsTable.id)
                    .select { PushSubscriptionsTable.userId eq userId }
                    .orderBy(PushSubscriptionsTable.updatedAt to SortOrder.ASC)
                    .map { it[PushSubscriptionsTable.id].value }
                if (userSubIds.size >= MAX_SUBSCRIPTIONS_PER_USER) {
                    val toEvictCount = userSubIds.size - MAX_SUBSCRIPTIONS_PER_USER + 1
                    for (evictId in userSubIds.take(toEvictCount)) {
                        PushSubscriptionsTable.deleteWhere { PushSubscriptionsTable.id eq evictId }
                    }
                }
                PushSubscriptionsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[PushSubscriptionsTable.userId] = userId
                    it[endpoint] = cleanEndpoint
                    it[p256dh] = p256dhClean
                    it[auth] = authClean
                    it[PushSubscriptionsTable.userAgent] = cleanUa
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
        return true
    }

    suspend fun unsubscribe(userId: UUID, endpoint: String): Boolean {
        val cleanEndpoint = endpoint.trim()
        if (cleanEndpoint.isBlank()) return false
        return dbQuery(workerManager.notificationDispatcher) {
            PushSubscriptionsTable.deleteWhere {
                (PushSubscriptionsTable.userId eq userId) and (PushSubscriptionsTable.endpoint eq cleanEndpoint)
            } > 0
        }
    }

    fun dispatchPushAsync(jobs: List<PendingPushJob>): Job? {
        if (jobs.isEmpty()) return null
        return workerManager.launchNotificationTask("webpush-${jobs.first().notificationId}") {
            sendPushBatch(jobs)
        }
    }

    suspend fun sendPushBatch(jobs: List<PendingPushJob>): Int {
        if (jobs.isEmpty()) return 0
        val vapid = runCatching { getOrCreateVapidKeys() }.getOrElse {
            logger.warn("[WebPush] Failed to initialize VAPID keys: ${it.message}")
            return 0
        }

        val userIds = jobs.map { it.userId }.distinct()
        val subsByUser = dbQuery(workerManager.notificationDispatcher) {
            PushSubscriptionsTable.innerJoin(UsersTable, { PushSubscriptionsTable.userId }, { UsersTable.id })
                .select {
                    (PushSubscriptionsTable.userId inList userIds) and
                        (UsersTable.isWithdrawn eq false)
                }
                .map { row ->
                    row[PushSubscriptionsTable.userId] to Triple(
                        row[PushSubscriptionsTable.endpoint],
                        row[PushSubscriptionsTable.p256dh],
                        row[PushSubscriptionsTable.auth]
                    )
                }.groupBy({ it.first }, { it.second })
        }

        var sentCount = 0
        val staleEndpoints = mutableListOf<String>()

        for (job in jobs) {
            val subs = subsByUser[job.userId].orEmpty()
            if (subs.isEmpty()) continue

            val payloadObj = PushNotificationPayload(
                id = job.notificationId.toString(),
                title = job.title.take(120),
                body = job.message.take(180),
                url = if (job.questionId != null) "/?questionId=${job.questionId}" else "/",
                questionId = job.questionId?.toString(),
                tag = "cistus-notif-${job.notificationId}"
            )
            val payloadBytes = json.encodeToString(payloadObj).toByteArray(Charsets.UTF_8)

            for ((endpoint, p256dh, auth) in subs) {
                try {
                    val uri = validatePushEndpoint(endpoint)
                    val encryptedBody = encryptPayloadAes128Gcm(payloadBytes, p256dh, auth)
                    val authHeader = buildVapidAuthorizationHeader(uri, vapid)

                    val response = withTimeout(5_000L) {
                        httpClient.post(endpoint) {
                            header(HttpHeaders.Authorization, authHeader)
                            header("TTL", "86400")
                            header("Urgency", "high")
                            header(HttpHeaders.ContentEncoding, "aes128gcm")
                            contentType(ContentType.Application.OctetStream)
                            setBody(encryptedBody)
                        }
                    }

                    val code = response.status.value
                    if (code in 200..299) {
                        sentCount++
                    } else if (code == 404 || code == 410) {
                        staleEndpoints.add(endpoint)
                    } else {
                        logger.warn("[WebPush] Push endpoint returned status=$code for user=${job.userId}")
                    }
                } catch (e: Exception) {
                    logger.warn("[WebPush] Delivery failed for user=${job.userId}: ${e.message}")
                }
            }
        }

        if (staleEndpoints.isNotEmpty()) {
            dbQuery(workerManager.notificationDispatcher) {
                for (ep in staleEndpoints.distinct()) {
                    PushSubscriptionsTable.deleteWhere { PushSubscriptionsTable.endpoint eq ep }
                }
            }
        }

        return sentCount
    }

    /**
     * Encrypts plaintext using RFC 8291 (aes128gcm content encoding) for Web Push.
     */
    internal fun encryptPayloadAes128Gcm(
        plaintext: ByteArray,
        userPublicKeyB64: String,
        userAuthB64: String
    ): ByteArray {
        val uaPublicBytes = b64UrlDec.decode(userPublicKeyB64)
        val authSecret = b64UrlDec.decode(userAuthB64)
        require(authSecret.size == 16) { "Auth secret must be 16 bytes" }
        val uaPublicKey = fromUncompressedP256Bytes(uaPublicBytes)

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val ephemeral = kpg.generateKeyPair()
        val asPublicBytes = toUncompressedP256Bytes(ephemeral.public as ECPublicKey)

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(ephemeral.private)
        ka.doPhase(uaPublicKey, true)
        val ecdhSecret = fixedUnsignedBytes(BigInteger(1, ka.generateSecret()), 32)

        val prkKey = hmacSha256(authSecret, ecdhSecret)
        val keyInfo = "WebPush: info\u0000".toByteArray(Charsets.US_ASCII) + uaPublicBytes + asPublicBytes
        val ikm = hmacSha256(prkKey, keyInfo + byteArrayOf(0x01))

        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val prk = hmacSha256(salt, ikm)
        val cek = hmacSha256(prk, "Content-Encoding: aes128gcm\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x01)).copyOfRange(0, 16)
        val nonce = hmacSha256(prk, "Content-Encoding: nonce\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x01)).copyOfRange(0, 12)

        val paddedPlaintext = plaintext + byteArrayOf(0x02)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(paddedPlaintext)

        val headerBuffer = ByteBuffer.allocate(16 + 4 + 1 + asPublicBytes.size)
        headerBuffer.put(salt)
        headerBuffer.putInt(4096)
        headerBuffer.put(asPublicBytes.size.toByte())
        headerBuffer.put(asPublicBytes)

        return headerBuffer.array() + ciphertext
    }

    internal fun buildVapidAuthorizationHeader(endpointUri: URI, vapid: VapidKeyMaterial): String {
        val audience = "${endpointUri.scheme.lowercase()}://${endpointUri.authority.lowercase()}"
        val exp = Instant.now().epochSecond + 12L * 3600L
        val headerJson = """{"typ":"JWT","alg":"ES256"}"""
        val claimsJson = json.encodeToString(VapidJwtClaims(aud = audience, exp = exp, sub = vapid.subject))
        val signingInput = "${b64UrlEnc.encodeToString(headerJson.toByteArray(Charsets.UTF_8))}.${b64UrlEnc.encodeToString(claimsJson.toByteArray(Charsets.UTF_8))}"

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(vapid.privateKey, secureRandom)
        signer.update(signingInput.toByteArray(Charsets.US_ASCII))
        val derSig = signer.sign()
        val p1363Sig = derEcdsaToP1363(derSig)

        val jwt = "$signingInput.${b64UrlEnc.encodeToString(p1363Sig)}"
        return "t=$jwt, k=${vapid.publicKeyBase64Url}".let { "vapid $it" }
    }

    fun close() {
        runCatching { httpClient.close() }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun toUncompressedP256Bytes(pubKey: ECPublicKey): ByteArray {
        val x = fixedUnsignedBytes(pubKey.w.affineX, 32)
        val y = fixedUnsignedBytes(pubKey.w.affineY, 32)
        return byteArrayOf(0x04) + x + y
    }

    internal fun fromUncompressedP256Bytes(bytes: ByteArray): ECPublicKey {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte()) { "Invalid uncompressed P-256 public key" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), ecParamSpec)) as ECPublicKey
    }

    private fun fixedUnsignedBytes(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(length)
        if (raw.size == length) return raw
        if (raw.size > length) {
            System.arraycopy(raw, raw.size - length, out, 0, length)
        } else {
            System.arraycopy(raw, 0, out, length - raw.size, raw.size)
        }
        return out
    }

    /**
     * Converts ASN.1 DER ECDSA signature to fixed 64-byte IEEE P1363 (R || S) format required by ES256 JWS.
     */
    internal fun derEcdsaToP1363(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "Invalid DER ECDSA signature" }
        var offset = 2
        if ((der[1].toInt() and 0x80) != 0) {
            offset += (der[1].toInt() and 0x7F)
        }
        require(der[offset] == 0x02.toByte()) { "Invalid DER R tag" }
        val rLen = der[offset + 1].toInt() and 0xFF
        val rStart = offset + 2
        val rBytes = der.copyOfRange(rStart, rStart + rLen)

        val sTagIdx = rStart + rLen
        require(der[sTagIdx] == 0x02.toByte()) { "Invalid DER S tag" }
        val sLen = der[sTagIdx + 1].toInt() and 0xFF
        val sStart = sTagIdx + 2
        val sBytes = der.copyOfRange(sStart, sStart + sLen)

        return fixedUnsignedBytes(BigInteger(1, rBytes), 32) + fixedUnsignedBytes(BigInteger(1, sBytes), 32)
    }
}
