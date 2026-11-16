package com.gonghak

import com.gonghak.config.DatabaseFactory
import com.gonghak.dto.*
import com.gonghak.models.PushSubscriptionsTable
import com.gonghak.models.UserRole
import com.gonghak.service.BackgroundWorkerManager
import com.gonghak.service.WebPushService
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigInteger
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class WebPushServiceTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val b64UrlDec = Base64.getUrlDecoder()
    private val b64UrlEnc = Base64.getUrlEncoder().withoutPadding()

    @Test
    fun testRfc8291EncryptionRoundTripAndVapidSignatureVerification() = runBlocking {
        DatabaseFactory.init()
        val workers = BackgroundWorkerManager()
        val service = WebPushService(
            workerManager = workers,
            dnsResolver = { arrayOf(InetAddress.getByAddress(byteArrayOf(142.toByte(), 250.toByte(), 1.toByte(), 1))) }
        )

        try {
            // 1. Auto-generated VAPID keypair is persisted & stable across calls
            val keyMaterial = service.getOrCreateVapidKeys()
            assertEquals(65, keyMaterial.publicKeyUncompressed.size)
            assertEquals(0x04.toByte(), keyMaterial.publicKeyUncompressed[0])
            assertEquals(keyMaterial.publicKeyBase64Url, service.getPublicKeyBase64Url())

            // 2. Verify VAPID ES256 JWT signature against uncompressed P-256 public key
            val endpointUri = service.validatePushEndpoint("https://fcm.googleapis.com/fcm/send/sample-token")
            val authHeader = service.buildVapidAuthorizationHeader(endpointUri, keyMaterial)
            assertTrue(authHeader.startsWith("vapid t="))
            val jwt = authHeader.substringAfter("vapid t=").substringBefore(", k=")
            val jwtParts = jwt.split(".")
            assertEquals(3, jwtParts.size)

            val sigP1363 = b64UrlDec.decode(jwtParts[2])
            assertEquals(64, sigP1363.size)
            val vapidPubKey = service.fromUncompressedP256Bytes(b64UrlDec.decode(keyMaterial.publicKeyBase64Url))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(vapidPubKey)
            verifier.update("${jwtParts[0]}.${jwtParts[1]}".toByteArray(Charsets.US_ASCII))
            assertTrue(verifier.verify(p1363ToDer(sigP1363)), "VAPID ES256 JWT signature must verify cleanly")

            // 3. Simulate browser PushManager subscription keypair + auth secret and verify RFC 8291 aes128gcm decrypt
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val uaKp = kpg.generateKeyPair()
            val uaPubEc = uaKp.public as ECPublicKey
            val uaPubUncompressed = byteArrayOf(0x04) +
                fixed32(uaPubEc.w.affineX) +
                fixed32(uaPubEc.w.affineY)
            val uaAuthSecret = ByteArray(16).also { SecureRandom().nextBytes(it) }

            val originalPayload = """{"id":"123","title":"새 답변 알림","body":"교수님이 답변을 남겼습니다."}"""
            val encrypted = service.encryptPayloadAes128Gcm(
                plaintext = originalPayload.toByteArray(Charsets.UTF_8),
                userPublicKeyB64 = b64UrlEnc.encodeToString(uaPubUncompressed),
                userAuthB64 = b64UrlEnc.encodeToString(uaAuthSecret)
            )

            // Parse RFC 8291 header: salt(16) || rs(4) || idlen(1=65) || asPublic(65) || ciphertext
            val buf = ByteBuffer.wrap(encrypted)
            val salt = ByteArray(16).also { buf.get(it) }
            val recordSize = buf.int
            assertEquals(4096, recordSize)
            val keyIdLen = buf.get().toInt() and 0xFF
            assertEquals(65, keyIdLen)
            val asPublicBytes = ByteArray(65).also { buf.get(it) }
            val ciphertext = ByteArray(buf.remaining()).also { buf.get(it) }

            val asPublicKey = service.fromUncompressedP256Bytes(asPublicBytes)
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(uaKp.private)
            ka.doPhase(asPublicKey, true)
            val ecdhSecret = fixed32(BigInteger(1, ka.generateSecret()))

            val prkKey = hmacSha256(uaAuthSecret, ecdhSecret)
            val keyInfo = "WebPush: info\u0000".toByteArray(Charsets.US_ASCII) + uaPubUncompressed + asPublicBytes
            val ikm = hmacSha256(prkKey, keyInfo + byteArrayOf(0x01))
            val prk = hmacSha256(salt, ikm)
            val cek = hmacSha256(prk, "Content-Encoding: aes128gcm\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x01)).copyOfRange(0, 16)
            val nonce = hmacSha256(prk, "Content-Encoding: nonce\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x01)).copyOfRange(0, 12)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
            val decryptedPadded = cipher.doFinal(ciphertext)
            assertEquals(0x02.toByte(), decryptedPadded.last())
            val decryptedText = String(decryptedPadded.copyOf(decryptedPadded.size - 1), Charsets.UTF_8)
            assertEquals(originalPayload, decryptedText)
        } finally {
            service.close()
            workers.shutdown()
            DatabaseFactory.close(preserveInMemory = true)
        }
    }

    @Test
    fun testPushEndpointSsrfGuards() {
        val service = WebPushService(
            dnsResolver = { host ->
                when (host) {
                    "169.254.169.254" -> arrayOf(InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte())))
                    "internal.local" -> arrayOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 5)))
                    "loop.example" -> arrayOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                    else -> arrayOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
                }
            }
        )
        try {
            assertFailsWith<IllegalArgumentException> { service.validatePushEndpoint("http://fcm.googleapis.com/push") }
            assertFailsWith<IllegalArgumentException> { service.validatePushEndpoint("https://fcm.googleapis.com:8443/push") }
            assertFailsWith<IllegalArgumentException> { service.validatePushEndpoint("https://169.254.169.254/latest/meta-data") }
            assertFailsWith<IllegalArgumentException> { service.validatePushEndpoint("https://internal.local/push") }
            assertFailsWith<IllegalArgumentException> { service.validatePushEndpoint("https://loop.example/push") }
            assertFailsWith<IllegalArgumentException> { service.validatePushEndpoint("https://attacker.example.com/push") }

            val validUri = service.validatePushEndpoint("https://updates.push.services.mozilla.com/wpush/v2/abc")
            assertEquals(URI("https://updates.push.services.mozilla.com/wpush/v2/abc"), validUri)
        } finally {
            service.close()
        }
    }

    @Test
    fun testPushRoutesAuthenticationSubscribeAndUnsubscribe() = testApplication {
        val client = createClient { install(ContentNegotiation) { json(json) } }

        // 1. Unauthenticated access must fail with 401
        val unauthKey = client.get("/api/push/vapid-public-key")
        assertEquals(HttpStatusCode.Unauthorized, unauthKey.status)

        // 2. Register student & fetch VAPID public key
        val stuAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.STUDENT,
                    username = "push_stu_01",
                    password = "stupass1234",
                    name = "푸시학생",
                    department = "소프트웨어학과",
                    identifier = "AUTO",
                    entryYear = 2029
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<ApiResponse<AuthResponse>>().data!!

        val vapidRes = client.get("/api/push/vapid-public-key") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<ApiResponse<VapidPublicKeyDto>>().data!!
        assertTrue(vapidRes.enabled)
        assertEquals(65, b64UrlDec.decode(vapidRes.publicKey).size)

        // 3. Reject SSRF internal endpoint & arbitrary non-push external host
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val kp = kpg.generateKeyPair()
        val pubEc = kp.public as ECPublicKey
        val p256dh = b64UrlEnc.encodeToString(byteArrayOf(0x04) + fixed32(pubEc.w.affineX) + fixed32(pubEc.w.affineY))
        val auth = b64UrlEnc.encodeToString(ByteArray(16) { 7 })

        val badEndpointRes = client.post("/api/push/subscribe") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(SubscribePushRequest(endpoint = "https://127.0.0.1/internal", keys = PushSubscriptionKeysDto(p256dh, auth)))
        }
        assertEquals(HttpStatusCode.BadRequest, badEndpointRes.status)

        val arbitraryHostRes = client.post("/api/push/subscribe") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(SubscribePushRequest(endpoint = "https://attacker.example.com/hook", keys = PushSubscriptionKeysDto(p256dh, auth)))
        }
        assertEquals(HttpStatusCode.BadRequest, arbitraryHostRes.status)

        // 4. Subscribe allowlisted browser push vendor endpoint & verify DB row exists, then unsubscribe cleanly
        val validEndpoint = "https://fcm.googleapis.com/fcm/send/fake-push-endpoint-test"
        val subRes = client.post("/api/push/subscribe") {
            header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(SubscribePushRequest(endpoint = validEndpoint, keys = PushSubscriptionKeysDto(p256dh, auth)))
        }
        assertEquals(HttpStatusCode.OK, subRes.status)

        val storedBefore = transaction {
            PushSubscriptionsTable.select {
                (PushSubscriptionsTable.userId eq UUID.fromString(stuAuth.user.id)) and
                    (PushSubscriptionsTable.endpoint eq validEndpoint)
            }.count()
        }
        assertEquals(1L, storedBefore)

        // 5. Subscription cap (MAX_SUBSCRIPTIONS_PER_USER = 10) & WITHDRAW cleanup
        for (i in 1..12) {
            client.post("/api/push/subscribe") {
                header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
                contentType(ContentType.Application.Json)
                setBody(
                    SubscribePushRequest(
                        endpoint = "https://fcm.googleapis.com/fcm/send/cap-token-$i",
                        keys = PushSubscriptionKeysDto(p256dh, auth)
                    )
                )
            }.also { assertEquals(HttpStatusCode.OK, it.status) }
        }
        val cappedCount = transaction {
            PushSubscriptionsTable.select {
                PushSubscriptionsTable.userId eq UUID.fromString(stuAuth.user.id)
            }.count()
        }
        assertEquals(WebPushService.MAX_SUBSCRIPTIONS_PER_USER.toLong(), cappedCount)

        // 6. Rate limiting triggers 429 TooManyRequests beyond 20 subscribe/unsubscribe requests/min
        var got429 = false
        for (i in 1..10) {
            val rateRes = client.post("/api/push/unsubscribe") {
                header(HttpHeaders.Authorization, "Bearer ${stuAuth.token}")
                contentType(ContentType.Application.Json)
                setBody(UnsubscribePushRequest(endpoint = "https://fcm.googleapis.com/fcm/send/cap-token-$i"))
            }
            if (rateRes.status == HttpStatusCode.TooManyRequests) {
                got429 = true
                break
            }
        }
        assertTrue(got429, "Expected /api/push rate limiter to trigger 429")

        // 7. Admin WITHDRAW purges any remaining push subscriptions for the withdrawn account
        val adminAuth = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    role = UserRole.PROFESSOR,
                    username = "push_admin_prof",
                    password = "adminpass1234",
                    name = "푸시관리자",
                    department = "소프트웨어학과"
                )
            )
        }.body<ApiResponse<AuthResponse>>().data!!
        com.gonghak.service.UserService().grantOrRevokeAdminByUsername("push_admin_prof", grant = true)

        client.post("/api/admin/users/${stuAuth.user.id}/sanction") {
            header(HttpHeaders.Authorization, "Bearer ${adminAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(UserSanctionRequest(action = "WITHDRAW"))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        val storedAfterWithdraw = transaction {
            PushSubscriptionsTable.select {
                PushSubscriptionsTable.userId eq UUID.fromString(stuAuth.user.id)
            }.count()
        }
        assertEquals(0L, storedAfterWithdraw)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        if (raw.size >= 32) {
            System.arraycopy(raw, raw.size - 32, out, 0, 32)
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        }
        return out
    }

    private fun p1363ToDer(sig: ByteArray): ByteArray {
        val r = BigInteger(1, sig.copyOfRange(0, 32)).toByteArray()
        val s = BigInteger(1, sig.copyOfRange(32, 64)).toByteArray()
        val seqLen = 2 + r.size + 2 + s.size
        val out = ByteBuffer.allocate(2 + seqLen)
        out.put(0x30.toByte()).put(seqLen.toByte())
        out.put(0x02.toByte()).put(r.size.toByte()).put(r)
        out.put(0x02.toByte()).put(s.size.toByte()).put(s)
        return out.array()
    }
}
