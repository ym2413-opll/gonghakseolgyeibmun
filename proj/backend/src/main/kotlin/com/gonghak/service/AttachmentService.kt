package com.gonghak.service

import com.gonghak.config.DatabaseFactory.dbQuery
import com.gonghak.dto.AttachmentDto
import com.gonghak.models.AttachmentsTable
import com.gonghak.models.QuestionsTable
import com.gonghak.worker.ImageCompressionProcessor
import com.gonghak.worker.SubprocessRunner
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

class AttachmentService(
    private val uploadDirPath: String = "uploads/attachments",
    private val workerManager: BackgroundWorkerManager = BackgroundWorkerManager()
) {
    private val logger = LoggerFactory.getLogger(AttachmentService::class.java)

    companion object {
        // 최대 파일 크기: 15MB
        const val MAX_FILE_SIZE_BYTES = 15L * 1024L * 1024L

        // 허용 확장자 화이트리스트
        val ALLOWED_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp",
            "pdf", "docx", "doc", "xlsx", "xls", "pptx", "ppt",
            "hwp", "hwpx", "txt", "zip"
        )

        // 이미지 확장자 목록
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")

        // 위험 확장자 블랙리스트 (다중 확장자 검사용)
        val DANGEROUS_EXTENSIONS = setOf(
            "exe", "bat", "cmd", "sh", "vbs", "js", "mjs", "msi",
            "jar", "py", "php", "jsp", "asp", "aspx", "html", "htm",
            "svg", "dll", "com", "scr", "lnk", "ps1", "wsf", "reg", "elf", "bin"
        )

        // 이미지 압축 최대 가로/세로 해상도
        const val MAX_IMAGE_DIMENSION = 1920
        // JPEG 압축 품질
        const val JPEG_COMPRESSION_QUALITY = 0.82f
    }

    init {
        val dir = File(uploadDirPath)
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }

    /**
     * 파일 보안 검증 및 이미지 압축 후 파일 저장
     */
    suspend fun saveAttachment(
        uploaderId: UUID?,
        fileName: String,
        originalBytes: ByteArray,
        declaredContentType: String?
    ): AttachmentDto {
        // 1. 기본 크기 검사
        if (originalBytes.isEmpty()) {
            throw IllegalArgumentException("빈 파일은 업로드할 수 없습니다.")
        }
        if (originalBytes.size > MAX_FILE_SIZE_BYTES) {
            throw IllegalArgumentException("파일 용량(${originalBytes.size / (1024 * 1024)}MB)이 제한(15MB)을 초과하였습니다.")
        }

        // 2. 파일명 경로 탐색(Directory Traversal) 및 보안 검사
        val sanitizedOriginalName = sanitizeFileName(fileName)
        val extension = getExtension(sanitizedOriginalName).lowercase()

        // 3. 확장자 화이트리스트 및 다중 확장자 검증
        validateExtension(sanitizedOriginalName, extension)

        // 4. Magic Bytes (파일 헤더 시그니처) 정밀 검사
        validateMagicBytes(originalBytes, extension)

        val isImage = IMAGE_EXTENSIONS.contains(extension)
        if (isImage) {
            ImageCompressionProcessor.validateImageDimensions(originalBytes)
        }
        val attachmentId = UUID.randomUUID()
        val safeSavedName = "${attachmentId}.$extension"

        // 5-6. CPU-집약적인 이미지 압축 및 디스크 I/O는 전용 워커 스레드 풀(및 필요시 격리 프로세스)에서 처리
        val (finalBytes, isCompressed, compressionRatio) = withContext(workerManager.attachmentDispatcher) {
            var processedBytes = originalBytes
            var compressedFlag = false
            var ratio = 0.0

            if (isImage) {
                try {
                    val useSubprocess = System.getenv("IMAGE_SUBPROCESS_ENABLED")
                        ?.trim()?.equals("false", ignoreCase = true) != true
                    val compressed = if (useSubprocess) {
                        SubprocessRunner.runImageCompressSubprocess(
                            originalBytes = originalBytes,
                            extension = extension,
                            maxDimension = MAX_IMAGE_DIMENSION,
                            jpegQuality = JPEG_COMPRESSION_QUALITY
                        ) ?: compressImage(originalBytes, extension)
                    } else {
                        compressImage(originalBytes, extension)
                    }

                    if (compressed != null && compressed.size < originalBytes.size) {
                        processedBytes = compressed
                        compressedFlag = true
                        val savedBytesCount = originalBytes.size - processedBytes.size
                        ratio = Math.round((savedBytesCount.toDouble() / originalBytes.size.toDouble()) * 1000.0) / 10.0
                        logger.info("[AttachmentWorker:${Thread.currentThread().name}] Image compressed: ${originalBytes.size} bytes -> ${processedBytes.size} bytes (ratio: $ratio%)")
                    }
                } catch (e: Exception) {
                    logger.warn("[AttachmentWorker] Image compression failed or skipped for $sanitizedOriginalName, using original", e)
                }
            }

            val targetPath = Paths.get(uploadDirPath, safeSavedName)
            Files.write(
                targetPath,
                processedBytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )

            Triple(processedBytes, compressedFlag, ratio)
        }

        val resolvedMimeType = declaredContentType ?: getMimeTypeByExtension(extension)

        // 7. DB 기록
        val created = dbQuery(workerManager.attachmentDispatcher) {
            AttachmentsTable.insert {
                it[id] = attachmentId
                it[AttachmentsTable.uploaderId] = uploaderId
                it[originalName] = sanitizedOriginalName
                it[savedName] = safeSavedName
                it[fileSize] = finalBytes.size.toLong()
                it[originalSize] = originalBytes.size.toLong()
                it[mimeType] = resolvedMimeType
                it[AttachmentsTable.isImage] = isImage
                it[AttachmentsTable.isCompressed] = isCompressed
                it[AttachmentsTable.compressionRatio] = compressionRatio
                it[createdAt] = Instant.now()
            }

            AttachmentDto(
                id = attachmentId.toString(),
                questionId = null,
                originalName = sanitizedOriginalName,
                savedName = safeSavedName,
                fileSize = finalBytes.size.toLong(),
                originalSize = originalBytes.size.toLong(),
                mimeType = resolvedMimeType,
                isImage = isImage,
                isCompressed = isCompressed,
                compressionRatio = compressionRatio,
                url = "/api/attachments/$attachmentId",
                downloadUrl = "/api/attachments/$attachmentId/download",
                createdAt = Instant.now().toString()
            )
        }

        return created
    }

    /**
     * 파일명 정제 (경로 탐색, 널 바이트 등 제거)
     */
    fun sanitizeFileName(fileName: String): String {
        // null byte 제거
        var clean = fileName.replace("\u0000", "").trim()
        // 디렉토리 경로 문자 제거
        clean = File(clean).name
        clean = clean.replace("[/\\\\]+".toRegex(), "_")
        // 안전한 파일명 기본값
        if (clean.isBlank()) {
            clean = "attachment_${UUID.randomUUID().toString().substring(0, 8)}"
        }
        return clean
    }

    /**
     * 확장자 검사 (화이트리스트 + 다중 확장자 악성코드 방지)
     */
    fun validateExtension(fileName: String, extension: String) {
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw SecurityException("허용되지 않는 파일 형식(.${extension})입니다. 안전한 문서 및 이미지 파일만 첨부 가능합니다.")
        }

        // 다중 확장자 검사: test.php.png 또는 test.exe.jpg 같은 공격 방어
        val parts = fileName.lowercase().split(".")
        if (parts.size > 2) {
            for (i in 1 until parts.size - 1) {
                val subExt = parts[i]
                if (DANGEROUS_EXTENSIONS.contains(subExt)) {
                    throw SecurityException("파일명에 위험한 확장자(.${subExt})가 포함되어 있어 차단되었습니다.")
                }
            }
        }
    }

    /**
     * 파일 헤더 매직 바이트 검증 (확장자 위조 방지)
     */
    fun validateMagicBytes(bytes: ByteArray, extension: String) {
        if (bytes.size < 4) {
            throw SecurityException("유효하지 않은 파일 크기입니다.")
        }

        // Windows PE 실행 파일(MZ) 또는 Linux ELF 즉시 차단
        if (bytes[0] == 0x4D.toByte() && bytes[1] == 0x5A.toByte()) {
            throw SecurityException("실행 파일(EXE/DLL) 형식은 보안상 엄격히 금지됩니다.")
        }
        if (bytes[0] == 0x7F.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0x4C.toByte() && bytes[3] == 0x46.toByte()) {
            throw SecurityException("실행 바이너리(ELF) 형식은 보안상 엄격히 금지됩니다.")
        }

        when (extension) {
            "png" -> {
                // PNG 매직 바이트: 89 50 4E 47 0D 0A 1A 0A
                val pngHeader = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
                if (!startsWith(bytes, pngHeader)) {
                    throw SecurityException("PNG 이미지 파일 시그니처가 일치하지 않습니다. (위조 의심)")
                }
            }
            "jpg", "jpeg" -> {
                // JPEG 매직 바이트: FF D8 FF
                val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
                if (!startsWith(bytes, jpegHeader)) {
                    throw SecurityException("JPEG 이미지 파일 시그니처가 일치하지 않습니다. (위조 의심)")
                }
            }
            "gif" -> {
                // GIF 매직 바이트: 47 49 46 38 (GIF8)
                val gifHeader = byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte())
                if (!startsWith(bytes, gifHeader)) {
                    throw SecurityException("GIF 이미지 파일 시그니처가 일치하지 않습니다. (위조 의심)")
                }
            }
            "webp" -> {
                // WEBP: RIFF .... WEBP
                val riff = byteArrayOf(0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte())
                if (!startsWith(bytes, riff) || bytes.size < 12) {
                    throw SecurityException("WEBP 이미지 파일 시그니처가 일치하지 않습니다.")
                }
                val webpBytes = byteArrayOf(0x57.toByte(), 0x45.toByte(), 0x42.toByte(), 0x50.toByte())
                val sub = bytes.sliceArray(8..11)
                if (!sub.contentEquals(webpBytes)) {
                    throw SecurityException("WEBP 이미지 파일 시그니처가 일치하지 않습니다.")
                }
            }
            "pdf" -> {
                // PDF 매직 바이트: 25 50 44 46 (%PDF)
                val pdfHeader = byteArrayOf(0x25.toByte(), 0x50.toByte(), 0x44.toByte(), 0x46.toByte())
                if (!startsWith(bytes, pdfHeader)) {
                    throw SecurityException("PDF 문서 시그니처가 일치하지 않습니다. (위조 의심)")
                }
            }
            "zip", "docx", "xlsx", "pptx", "hwpx" -> {
                // ZIP / OpenXML: 50 4B 03 04 (PK..)
                val pkHeader = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte())
                if (!startsWith(bytes, pkHeader)) {
                    throw SecurityException("압축/문서 파일 시그니처가 일치하지 않습니다.")
                }
            }
            "doc", "xls", "ppt", "hwp" -> {
                // OLE2 Compound Document: D0 CF 11 E0
                val oleHeader = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte())
                if (!startsWith(bytes, oleHeader)) {
                    throw SecurityException("오피스/한글 문서 시그니처가 일치하지 않습니다.")
                }
            }
            "txt" -> {
                // 텍스트 파일: 널 바이트가 과도하게 포함되어 있으면 바이너리로 간주하여 차단
                val sampleSize = minOf(bytes.size, 1024)
                var nullCount = 0
                for (i in 0 until sampleSize) {
                    if (bytes[i] == 0.toByte()) nullCount++
                }
                if (nullCount > 2) {
                    throw SecurityException("텍스트 파일 내에 유효하지 않은 바이너리 데이터가 감지되었습니다.")
                }
            }
        }
    }

    private fun startsWith(array: ByteArray, prefix: ByteArray): Boolean {
        if (array.size < prefix.size) return false
        for (i in prefix.indices) {
            if (array[i] != prefix[i]) return false
        }
        return true
    }

    /**
     * 이미지 압축 및 최적화 리사이징
     */
    fun compressImage(originalBytes: ByteArray, extension: String): ByteArray? {
        return ImageCompressionProcessor.compressImageBytes(
            originalBytes = originalBytes,
            extension = extension,
            maxDimension = MAX_IMAGE_DIMENSION,
            jpegQuality = JPEG_COMPRESSION_QUALITY
        )
    }

    /**
     * 질문 ID와 첨부파일들 매핑
     */
    suspend fun linkAttachmentsToQuestion(
        questionId: UUID,
        attachmentIds: List<String>,
        uploaderId: UUID? = null
    ) = dbQuery {
        if (attachmentIds.isEmpty()) return@dbQuery
        val validUuids = attachmentIds.mapNotNull {
            try {
                UUID.fromString(it)
            } catch (_: Exception) {
                null
            }
        }
        if (validUuids.isNotEmpty()) {
            AttachmentsTable.update({
                val baseCond = (AttachmentsTable.id inList validUuids) and AttachmentsTable.questionId.isNull()
                if (uploaderId != null) baseCond and (AttachmentsTable.uploaderId eq uploaderId) else baseCond
            }) {
                it[AttachmentsTable.questionId] = questionId
            }
        }
    }

    /**
     * 특정 질문의 첨부파일 목록 조회
     */
    suspend fun getAttachmentsByQuestionId(questionId: UUID): List<AttachmentDto> = dbQuery {
        AttachmentsTable.select { AttachmentsTable.questionId eq questionId }
            .orderBy(AttachmentsTable.createdAt to SortOrder.ASC)
            .map { row ->
                val idStr = row[AttachmentsTable.id].value.toString()
                AttachmentDto(
                    id = idStr,
                    questionId = questionId.toString(),
                    originalName = row[AttachmentsTable.originalName],
                    savedName = row[AttachmentsTable.savedName],
                    fileSize = row[AttachmentsTable.fileSize],
                    originalSize = row[AttachmentsTable.originalSize],
                    mimeType = row[AttachmentsTable.mimeType],
                    isImage = row[AttachmentsTable.isImage],
                    isCompressed = row[AttachmentsTable.isCompressed],
                    compressionRatio = row[AttachmentsTable.compressionRatio],
                    url = "/api/attachments/$idStr",
                    downloadUrl = "/api/attachments/$idStr/download",
                    createdAt = row[AttachmentsTable.createdAt].toString()
                )
            }
    }

    /**
     * 첨부파일 메타데이터 및 물리 파일 조회 (작성자/숨김 질문 권한 검증 포함)
     */
    suspend fun getAccessibleAttachmentFile(
        attachmentId: UUID,
        requesterId: UUID,
        isAdmin: Boolean = false
    ): Pair<AttachmentDto, File>? = dbQuery {
        val row = AttachmentsTable.select { AttachmentsTable.id eq attachmentId }.singleOrNull()
            ?: return@dbQuery null

        val linkedQuestionId = row[AttachmentsTable.questionId]
        val uploaderId = row[AttachmentsTable.uploaderId]

        if (linkedQuestionId == null) {
            if (!isAdmin && uploaderId != requesterId) {
                throw IllegalStateException("접근 권한이 없는 첨부파일입니다.")
            }
        } else if (!isAdmin) {
            val qRow = QuestionsTable.select { QuestionsTable.id eq linkedQuestionId }.singleOrNull()
            if (qRow != null && qRow[QuestionsTable.isHidden]) {
                throw IllegalStateException("관리자에 의해 가려진 게시물의 첨부파일에 접근할 수 없습니다.")
            }
        }

        rowToAttachmentFilePair(row)
    }

    /**
     * 첨부파일 메타데이터 및 물리 파일 조회
     */
    suspend fun getAttachmentFile(attachmentId: UUID): Pair<AttachmentDto, File>? = dbQuery {
        val row = AttachmentsTable.select { AttachmentsTable.id eq attachmentId }.singleOrNull()
            ?: return@dbQuery null
        rowToAttachmentFilePair(row)
    }

    private fun rowToAttachmentFilePair(row: ResultRow): Pair<AttachmentDto, File> {
        val idStr = row[AttachmentsTable.id].value.toString()
        val savedName = row[AttachmentsTable.savedName]
        val file = File(uploadDirPath, savedName)

        val dto = AttachmentDto(
            id = idStr,
            questionId = row[AttachmentsTable.questionId]?.toString(),
            originalName = row[AttachmentsTable.originalName],
            savedName = savedName,
            fileSize = row[AttachmentsTable.fileSize],
            originalSize = row[AttachmentsTable.originalSize],
            mimeType = row[AttachmentsTable.mimeType],
            isImage = row[AttachmentsTable.isImage],
            isCompressed = row[AttachmentsTable.isCompressed],
            compressionRatio = row[AttachmentsTable.compressionRatio],
            url = "/api/attachments/$idStr",
            downloadUrl = "/api/attachments/$idStr/download",
            createdAt = row[AttachmentsTable.createdAt].toString()
        )

        return Pair(dto, file)
    }

    /**
     * 첨부파일 삭제 (본인 또는 관리자만 가능)
     */
    suspend fun deleteAttachment(attachmentId: UUID, userId: UUID?, isAdmin: Boolean = false): Boolean {
        val savedName = dbQuery(workerManager.attachmentDispatcher) {
            val row = AttachmentsTable.select { AttachmentsTable.id eq attachmentId }.singleOrNull()
                ?: return@dbQuery null

            val uploader = row[AttachmentsTable.uploaderId]
            if (!isAdmin && (userId == null || uploader == null || uploader != userId)) {
                throw IllegalStateException("본인이 업로드한 첨부파일만 삭제할 수 있습니다.")
            }

            val name = row[AttachmentsTable.savedName]
            AttachmentsTable.deleteWhere { AttachmentsTable.id eq attachmentId }
            name
        } ?: return false

        deleteStoredFiles(listOf(savedName))
        return true
    }

    suspend fun deleteStoredFiles(savedNames: List<String>) {
        if (savedNames.isEmpty()) return
        withContext(workerManager.attachmentDispatcher) {
            savedNames.forEach { savedName ->
                runCatching { Files.deleteIfExists(Paths.get(uploadDirPath, savedName)) }
            }
        }
    }

    /**
     * Executed periodically by the background scheduler thread to clean up unlinked
     * temporary attachments older than the retention window (default 24 hours).
     */
    suspend fun cleanupOrphanAttachments(maxAgeSeconds: Long = 24 * 3600L): Int {
        val cutoff = Instant.now().minusSeconds(maxAgeSeconds)
        val orphaned = dbQuery(workerManager.attachmentDispatcher) {
            val rows = AttachmentsTable.select {
                AttachmentsTable.questionId.isNull() and (AttachmentsTable.createdAt less cutoff)
            }.toList()

            if (rows.isEmpty()) return@dbQuery emptyList<String>()

            val ids = rows.map { it[AttachmentsTable.id].value }
            val names = rows.map { it[AttachmentsTable.savedName] }
            AttachmentsTable.deleteWhere { AttachmentsTable.id inList ids }
            names
        }

        deleteStoredFiles(orphaned)
        return orphaned.size
    }

    private fun getExtension(fileName: String): String {
        val idx = fileName.lastIndexOf('.')
        return if (idx >= 0 && idx < fileName.length - 1) {
            fileName.substring(idx + 1)
        } else {
            ""
        }
    }

    fun getMimeTypeByExtension(ext: String): String {
        return when (ext.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "ppt" -> "application/vnd.ms-powerpoint"
            "hwp", "hwpx" -> "application/x-hwp"
            "txt" -> "text/plain; charset=UTF-8"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
