package com.gonghak

import com.gonghak.dto.*
import com.gonghak.models.QuestionCategory
import com.gonghak.service.AttachmentService
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class AttachmentSecurityTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val studentId = "11111111-1111-1111-1111-111111111101"
    private val courseId = "33333333-3333-3333-3333-333333333301"

    /**
     * 테스트용 유효한 PNG 이미지 바이트 생성 (2000x1500)
     */
    private fun createSampleLargeImageBytes(width: Int = 2200, height: Int = 1600): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        // 복잡한 패턴을 그려 압축률 테스트를 유의미하게 만듦
        for (x in 0 until width step 20) {
            for (y in 0 until height step 20) {
                g.color = Color((x * 7) % 256, (y * 11) % 256, ((x + y) * 13) % 256)
                g.fillRect(x, y, 20, 20)
            }
        }
        g.dispose()

        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        return baos.toByteArray()
    }

    /**
     * 테스트용 유효한 PDF 파일 바이트 생성
     */
    private fun createSamplePdfBytes(): ByteArray {
        val pdfHeader = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF".toByteArray(Charsets.UTF_8)
        return pdfHeader
    }

    @Test
    fun testUnitSecurityChecks() {
        val service = AttachmentService("build/tmp/test_uploads")

        // 1. 위험한 실행 파일 확장자 거부
        assertThrows<SecurityException> {
            service.validateExtension("virus.exe", "exe")
        }
        assertThrows<SecurityException> {
            service.validateExtension("script.sh", "sh")
        }

        // 2. 다중 확장자 악성코드 차단 (test.php.png)
        assertThrows<SecurityException> {
            service.validateExtension("exploit.php.png", "png")
        }
        assertThrows<SecurityException> {
            service.validateExtension("trojan.exe.jpg", "jpg")
        }

        // 3. 확장자 위조 (확장자는 png인데 내용은 MZ 실행파일 헤더)
        val fakeBytes = byteArrayOf(0x4D.toByte(), 0x5A.toByte(), 0x90.toByte(), 0x00.toByte())
        assertThrows<SecurityException> {
            service.validateMagicBytes(fakeBytes, "png")
        }

        // 4. 경로 탐색(Directory Traversal) 파일명 정제
        val sanitized = service.sanitizeFileName("../../evil_script.png")
        assertFalse(sanitized.contains(".."))
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("\\"))
        assertEquals("evil_script.png", sanitized)
    }

    @Test
    fun testImageCompression() {
        val service = AttachmentService("build/tmp/test_uploads")
        val largeImageBytes = createSampleLargeImageBytes(2200, 1600)
        val originalSize = largeImageBytes.size

        val compressed = service.compressImage(largeImageBytes, "png")
        assertNotNull(compressed)

        // 이미지 디코딩하여 리사이즈 확인
        val decoded = ImageIO.read(compressed.inputStream())
        assertNotNull(decoded)
        assertTrue(decoded.width <= AttachmentService.MAX_IMAGE_DIMENSION)
        assertTrue(decoded.height <= AttachmentService.MAX_IMAGE_DIMENSION)
    }

    @Test
    fun testAttachmentApiEndpoints() = testApplication {
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 1. 대용량 이미지 업로드 및 압축 검증
        val samplePngBytes = createSampleLargeImageBytes(2000, 1500)
        val uploadRes = client.submitFormWithBinaryData(
            url = "/api/attachments/upload",
            formData = formData {
                append("file", samplePngBytes, Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"circuit_schematic.png\"")
                })
            }
        ) {
            header("X-User-Id", studentId)
        }

        assertEquals(HttpStatusCode.Created, uploadRes.status)
        val uploadBody = uploadRes.body<FileUploadResponse>()
        assertTrue(uploadBody.success)
        assertNotNull(uploadBody.attachment)
        val att = uploadBody.attachment
        assertEquals("circuit_schematic.png", att.originalName)
        assertTrue(att.isImage)
        assertTrue(att.fileSize > 0)

        // 2. 파일 다운로드/미리보기 엔드포인트 확인
        val downloadRes = client.get("/api/attachments/${att.id}/download")
        assertEquals(HttpStatusCode.OK, downloadRes.status)
        assertEquals("nosniff", downloadRes.headers["X-Content-Type-Options"])
        assertTrue(downloadRes.headers[HttpHeaders.ContentDisposition]?.contains("attachment") == true)

        // 3. 위조된 파일(확장자는 png인데 내용은 텍스트/스크립트) 업로드 시도 -> 보안 검사 차단 확인
        val maliciousFakePng = "<?php echo 'malicious code'; ?>".toByteArray(Charsets.UTF_8)
        val fakeUploadRes = client.submitFormWithBinaryData(
            url = "/api/attachments/upload",
            formData = formData {
                append("file", maliciousFakePng, Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"fake_image.png\"")
                })
            }
        ) {
            header("X-User-Id", studentId)
        }
        assertEquals(HttpStatusCode.BadRequest, fakeUploadRes.status)

        // 4. 악성 실행 파일(.exe) 업로드 시도 -> 보안 검사 차단 확인
        val exeBytes = byteArrayOf(0x4D.toByte(), 0x5A.toByte(), 0x90.toByte(), 0x00.toByte())
        val exeUploadRes = client.submitFormWithBinaryData(
            url = "/api/attachments/upload",
            formData = formData {
                append("file", exeBytes, Headers.build {
                    append(HttpHeaders.ContentType, "application/x-msdownload")
                    append(HttpHeaders.ContentDisposition, "filename=\"malware.exe\"")
                })
            }
        ) {
            header("X-User-Id", studentId)
        }
        assertEquals(HttpStatusCode.BadRequest, exeUploadRes.status)

        // 5. 질문 생성 시 첨부파일 연동 및 조회 검증
        val createQRes = client.post("/api/questions") {
            header("X-User-Id", studentId)
            contentType(ContentType.Application.Json)
            setBody(
                CreateQuestionRequest(
                    courseId = courseId,
                    category = QuestionCategory.LECTURE,
                    title = "회로도 첨부 질문입니다",
                    content = "첨부된 회로도에서 3번 저항값 계산이 맞는지 확인 부탁드립니다.",
                    attachmentIds = listOf(att.id)
                )
            )
        }
        assertEquals(HttpStatusCode.Created, createQRes.status)
        val createdQ = createQRes.body<ApiResponse<QuestionDetailDto>>().data
        assertNotNull(createdQ)
        assertEquals(1, createdQ.attachments.size)
        assertEquals(att.id, createdQ.attachments[0].id)
        assertEquals("circuit_schematic.png", createdQ.attachments[0].originalName)

        // 6. 질문 상세 조회 시 첨부파일 확인
        val detailRes = client.get("/api/questions/${createdQ.id}") {
            header("X-User-Id", studentId)
        }
        assertEquals(HttpStatusCode.OK, detailRes.status)
        val detailQ = detailRes.body<ApiResponse<QuestionDetailDto>>().data
        assertNotNull(detailQ)
        assertEquals(1, detailQ.attachments.size)
    }
}
