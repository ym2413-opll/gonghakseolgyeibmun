package com.gonghak.routes

import com.gonghak.dto.ApiResponse
import com.gonghak.dto.FileUploadResponse
import com.gonghak.service.AttachmentService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

fun Route.attachmentRoutes(attachmentService: AttachmentService) {
    route("/api/attachments") {
        // 1. 파일 업로드 (보안 검사 및 이미지 압축)
        post("/upload") {
            val uploaderId = try {
                call.getCurrentUserId()
            } catch (_: Exception) {
                null
            }

            var uploadedDto: com.gonghak.dto.AttachmentDto? = null
            var errorMsg: String? = null

            try {
                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem && uploadedDto == null) {
                        val fileName = part.originalFileName ?: "unnamed"
                        val contentType = part.contentType?.toString()
                        val stream = part.streamProvider()

                        val buffer = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (stream.read(chunk).also { bytesRead = it } != -1) {
                            totalRead += bytesRead
                            if (totalRead > AttachmentService.MAX_FILE_SIZE_BYTES) {
                                throw IllegalArgumentException("파일 용량이 15MB를 초과하여 업로드가 중단되었습니다.")
                            }
                            buffer.write(chunk, 0, bytesRead)
                        }

                        val fileBytes = buffer.toByteArray()
                        uploadedDto = attachmentService.saveAttachment(
                            uploaderId = uploaderId,
                            fileName = fileName,
                            originalBytes = fileBytes,
                            declaredContentType = contentType
                        )
                    }
                    part.dispose()
                }

                if (uploadedDto != null) {
                    call.respond(
                        HttpStatusCode.Created,
                        FileUploadResponse(
                            success = true,
                            attachment = uploadedDto!!,
                            message = if (uploadedDto!!.isCompressed) {
                                "이미지가 성공적으로 최적화 및 압축되었습니다. (${uploadedDto!!.compressionRatio}% 절감)"
                            } else {
                                "파일 보안 검사를 성공적으로 통과하여 업로드되었습니다."
                            }
                        )
                    )
                } else {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse<String>(success = false, message = "업로드할 파일이 제공되지 않았습니다.")
                    )
                }
            } catch (e: SecurityException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse<String>(success = false, message = "[보안 검사 차단] ${e.message}")
                )
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse<String>(success = false, message = e.message ?: "잘못된 파일 요청입니다.")
                )
            } catch (e: Exception) {
                call.application.environment.log.error("Attachment upload failed: ${e.message}", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse<String>(success = false, message = "파일 업로드 중 서버 오류가 발생했습니다: ${e.message}")
                )
            }
        }

        // 2. 파일 미리보기 / 보기
        get("/{id}") {
            val idParam = call.parameters["id"]
            val attachmentId = try {
                UUID.fromString(idParam)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = "잘못된 첨부파일 ID입니다."))
                return@get
            }

            val pair = attachmentService.getAttachmentFile(attachmentId)
            if (pair == null || !pair.second.exists()) {
                call.respond(HttpStatusCode.NotFound, ApiResponse<String>(success = false, message = "첨부파일을 찾을 수 없습니다."))
                return@get
            }

            val (dto, file) = pair
            val encodedName = URLEncoder.encode(dto.originalName, StandardCharsets.UTF_8.toString()).replace("+", "%20")
            val dispositionType = if (dto.isImage) "inline" else "attachment"

            call.response.header(HttpHeaders.ContentDisposition, "$dispositionType; filename=\"$encodedName\"; filename*=UTF-8''$encodedName")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("Content-Security-Policy", "sandbox")
            call.respondFile(file)
        }

        // 3. 파일 안전 다운로드 강제
        get("/{id}/download") {
            val idParam = call.parameters["id"]
            val attachmentId = try {
                UUID.fromString(idParam)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = "잘못된 첨부파일 ID입니다."))
                return@get
            }

            val pair = attachmentService.getAttachmentFile(attachmentId)
            if (pair == null || !pair.second.exists()) {
                call.respond(HttpStatusCode.NotFound, ApiResponse<String>(success = false, message = "첨부파일을 찾을 수 없습니다."))
                return@get
            }

            val (dto, file) = pair
            val encodedName = URLEncoder.encode(dto.originalName, StandardCharsets.UTF_8.toString()).replace("+", "%20")

            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$encodedName\"; filename*=UTF-8''$encodedName")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondFile(file)
        }

        // 4. 첨부파일 삭제
        delete("/{id}") {
            val idParam = call.parameters["id"]
            val attachmentId = try {
                UUID.fromString(idParam)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(success = false, message = "잘못된 첨부파일 ID입니다."))
                return@delete
            }

            val currentUserId = try {
                call.getCurrentUserId()
            } catch (_: Exception) {
                null
            }

            try {
                val deleted = attachmentService.deleteAttachment(attachmentId, currentUserId)
                if (deleted) {
                    call.respond(HttpStatusCode.OK, ApiResponse(success = true, data = "파일이 성공적으로 삭제되었습니다."))
                } else {
                    call.respond(HttpStatusCode.NotFound, ApiResponse<String>(success = false, message = "삭제할 파일을 찾을 수 없습니다."))
                }
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(success = false, message = e.message))
            }
        }
    }
}
