package com.gonghak.worker

import com.gonghak.config.EnvConfig
import jakarta.activation.DataHandler
import jakarta.mail.Message
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import java.util.Date
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

@Serializable
data class SmtpInlineImage(
    val cid: String,
    val fileName: String,
    val contentType: String,
    val base64Data: String
)

@Serializable
data class SmtpJobPayload(
    val host: String,
    val port: Int,
    val user: String? = null,
    val pass: String? = null,
    val from: String,
    val to: String,
    val subject: String,
    val body: String,
    val replyTo: String? = null,
    val messageId: String? = null,
    val htmlBody: String? = null,
    val inlineImages: List<SmtpInlineImage> = emptyList()
)

@Serializable
data class SmtpJobResult(
    val success: Boolean,
    val message: String? = null
)

/**
 * Shared SMTP transport client used by both the dedicated email worker thread
 * and the isolated SMTP OS subprocess worker.
 */
object SmtpTransport {
    private fun sanitizeHeaderAddress(raw: String): String =
        raw.replace(Regex("[\\r\\n\\u0000<>]"), "").trim()

    internal fun dotStuffBody(body: String): String {
        val normalized = body.replace("\r\n", "\n").replace('\r', '\n')
        return normalized.split('\n').joinToString("\r\n") { line ->
            if (line.startsWith(".")) ".$line" else line
        }
    }

    fun dispatchSmtpMail(payload: SmtpJobPayload) {
        val cleanFrom = sanitizeHeaderAddress(payload.from)
        val cleanTo = sanitizeHeaderAddress(payload.to)
        require(cleanFrom.isNotEmpty() && cleanTo.isNotEmpty()) {
            "유효한 발신자/수신자 이메일 주소가 필요합니다."
        }

        val hasAuth = !payload.user.isNullOrBlank() && !payload.pass.isNullOrBlank()
        val implicitSsl = payload.port == 465
        val allowPlaintext = EnvConfig.get("SMTP_ALLOW_INSECURE_PLAINTEXT")
            ?.equals("true", ignoreCase = true) == true

        val props = Properties().apply {
            put("mail.transport.protocol", "smtp")
            put("mail.smtp.host", payload.host)
            put("mail.smtp.port", payload.port.toString())
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "15000")
            put("mail.smtp.writetimeout", "15000")
            put("mail.smtp.auth", hasAuth.toString())
            if (implicitSsl) {
                put("mail.smtp.ssl.enable", "true")
            } else {
                put("mail.smtp.starttls.enable", "true")
                if (hasAuth && !allowPlaintext) {
                    put("mail.smtp.starttls.required", "true")
                }
            }
        }

        val session = Session.getInstance(props)
        val mimeMsg = buildMimeMessage(session, payload, cleanFrom, cleanTo)

        val transport = session.getTransport("smtp")
        try {
            if (hasAuth) {
                transport.connect(payload.host, payload.port, payload.user, payload.pass)
            } else {
                transport.connect(payload.host, payload.port, null, null)
            }
            transport.sendMessage(mimeMsg, mimeMsg.allRecipients)
        } finally {
            runCatching { transport.close() }
        }
    }

    internal fun buildMimeMessage(
        session: Session,
        payload: SmtpJobPayload,
        cleanFrom: String = sanitizeHeaderAddress(payload.from),
        cleanTo: String = sanitizeHeaderAddress(payload.to)
    ): MimeMessage {
        val cleanMessageId = payload.messageId?.replace(Regex("[\\r\\n]"), "")?.trim()?.let { rawId ->
            if (rawId.isBlank()) null
            else if (rawId.startsWith("<") && rawId.endsWith(">")) rawId else "<$rawId>"
        }

        return object : MimeMessage(session) {
            override fun updateMessageID() {
                if (cleanMessageId != null) {
                    setHeader("Message-ID", cleanMessageId)
                } else {
                    super.updateMessageID()
                }
            }
        }.apply {
            setFrom(InternetAddress(cleanFrom))
            setRecipient(Message.RecipientType.TO, InternetAddress(cleanTo))
            val cleanReplyTo = payload.replyTo?.let(::sanitizeHeaderAddress)
            if (!cleanReplyTo.isNullOrBlank() && cleanReplyTo.contains("@")) {
                replyTo = arrayOf(InternetAddress(cleanReplyTo))
            }
            val safeSubject = payload.subject.replace(Regex("[\\r\\n\\u0000]"), " ")
            setSubject(safeSubject, Charsets.UTF_8.name())

            val allowedInlineMimes = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
            val validCidRegex = Regex("^[A-Za-z0-9._@-]{1,128}$")

            if (payload.htmlBody.isNullOrBlank()) {
                setText(payload.body, Charsets.UTF_8.name())
            } else {
                val alternative = MimeMultipart("alternative")

                val textPart = MimeBodyPart().apply {
                    setText(payload.body, Charsets.UTF_8.name())
                }
                alternative.addBodyPart(textPart)

                if (payload.inlineImages.isEmpty()) {
                    val htmlPart = MimeBodyPart().apply {
                        setContent(payload.htmlBody, "text/html; charset=UTF-8")
                    }
                    alternative.addBodyPart(htmlPart)
                } else {
                    val related = MimeMultipart("related")
                    val htmlPart = MimeBodyPart().apply {
                        setContent(payload.htmlBody, "text/html; charset=UTF-8")
                    }
                    related.addBodyPart(htmlPart)

                    for (img in payload.inlineImages) {
                        val normalizedMime = img.contentType.substringBefore(';').trim().lowercase()
                        if (normalizedMime !in allowedInlineMimes) continue
                        val safeCid = img.cid.trim()
                        if (!validCidRegex.matches(safeCid)) continue
                        val decoded = runCatching { Base64.getDecoder().decode(img.base64Data) }.getOrNull() ?: continue
                        if (decoded.isEmpty()) continue
                        val safeFileName = img.fileName
                            .replace(Regex("[\\u0000-\\u001f\\u007f;\"/\\\\]"), "_")
                            .take(120)
                            .ifBlank { "attachment" }
                        val imgPart = MimeBodyPart().apply {
                            dataHandler = DataHandler(ByteArrayDataSource(decoded, normalizedMime))
                            setHeader("Content-ID", "<$safeCid>")
                            disposition = Part.INLINE
                            fileName = safeFileName
                        }
                        related.addBodyPart(imgPart)
                    }

                    val relatedWrapper = MimeBodyPart().apply {
                        setContent(related)
                    }
                    alternative.addBodyPart(relatedWrapper)
                }

                setContent(alternative)
            }

            sentDate = Date()
            saveChanges()
        }
    }
}

/**
 * Shared CPU/memory-heavy image resizing and re-encoding helper used by both
 * the attachment worker thread pool and the isolated image subprocess worker.
 */
object ImageCompressionProcessor {
    const val MAX_SOURCE_DIMENSION = 8000
    const val MAX_SOURCE_PIXELS = 25_000_000L

    /**
     * Reads image container headers BEFORE full pixel decoding to prevent image-bomb OOMs.
     */
    fun validateImageDimensions(originalBytes: ByteArray) {
        try {
            val iis = ImageIO.createImageInputStream(ByteArrayInputStream(originalBytes)) ?: return
            iis.use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) return
                val reader = readers.next()
                try {
                    reader.input = stream
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0 ||
                        width > MAX_SOURCE_DIMENSION ||
                        height > MAX_SOURCE_DIMENSION ||
                        width.toLong() * height.toLong() > MAX_SOURCE_PIXELS
                    ) {
                        throw SecurityException("이미지 해상도(${width}x${height})가 보안 허용 범위를 초과했습니다.")
                    }
                } finally {
                    reader.dispose()
                }
            }
        } catch (se: SecurityException) {
            throw se
        } catch (_: Exception) {
            throw IllegalArgumentException("유효하지 않거나 손상된 이미지 파일입니다.")
        }
    }

    fun compressImageBytes(
        originalBytes: ByteArray,
        extension: String,
        maxDimension: Int,
        jpegQuality: Float
    ): ByteArray? {
        if (extension == "gif" || extension == "webp") {
            return null
        }

        validateImageDimensions(originalBytes)

        val inputStream = ByteArrayInputStream(originalBytes)
        val originalImage: BufferedImage = ImageIO.read(inputStream) ?: return null

        val originalWidth = originalImage.width
        val originalHeight = originalImage.height

        var targetWidth = originalWidth
        var targetHeight = originalHeight

        if (originalWidth > maxDimension || originalHeight > maxDimension) {
            if (originalWidth > originalHeight) {
                targetWidth = maxDimension
                targetHeight = (originalHeight.toDouble() * (maxDimension.toDouble() / originalWidth)).toInt()
            } else {
                targetHeight = maxDimension
                targetWidth = (originalWidth.toDouble() * (maxDimension.toDouble() / originalHeight)).toInt()
            }
        }

        val imageType = if (extension == "png" && originalImage.colorModel.hasAlpha()) {
            BufferedImage.TYPE_INT_ARGB
        } else {
            BufferedImage.TYPE_INT_RGB
        }

        val resizedImage = BufferedImage(targetWidth, targetHeight, imageType)
        val g2d = resizedImage.createGraphics()
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null)
        } finally {
            g2d.dispose()
        }

        val outputStream = ByteArrayOutputStream()
        val formatName = if (extension == "png") "png" else "jpeg"

        if (formatName == "jpeg") {
            val writers = ImageIO.getImageWritersByFormatName("jpeg")
            if (writers.hasNext()) {
                val writer = writers.next()
                var ios: javax.imageio.stream.ImageOutputStream? = null
                try {
                    val param = writer.defaultWriteParam
                    param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    param.compressionQuality = jpegQuality
                    ios = ImageIO.createImageOutputStream(outputStream)
                    writer.output = ios
                    writer.write(null, IIOImage(resizedImage, null, null), param)
                } finally {
                    runCatching { ios?.close() }
                    runCatching { writer.dispose() }
                }
            } else {
                ImageIO.write(resizedImage, "jpeg", outputStream)
            }
        } else {
            ImageIO.write(resizedImage, "png", outputStream)
        }

        val compressedBytes = outputStream.toByteArray()
        return if (compressedBytes.isNotEmpty()) compressedBytes else null
    }
}

/**
 * Isolated OS subprocess entry point for SMTP email dispatch.
 */
object SmtpWorkerMain {
    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val input = System.`in`.bufferedReader(Charsets.UTF_8).readText()
            val payload = json.decodeFromString<SmtpJobPayload>(input)
            SmtpTransport.dispatchSmtpMail(payload)
            println(json.encodeToString(SmtpJobResult(success = true)))
        } catch (e: Exception) {
            println(json.encodeToString(SmtpJobResult(success = false, message = e.message)))
            System.exit(1)
        }
    }
}

/**
 * Isolated OS subprocess entry point for image decoding/resizing/compression
 * so large or malformed images cannot OOM the main API JVM process.
 */
object ImageCompressWorkerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 5) {
            System.err.println("Usage: ImageCompressWorkerMain <inputPath> <outputPath> <ext> <maxDim> <quality>")
            System.exit(2)
        }
        try {
            val inputPath = Paths.get(args[0])
            val outputPath = Paths.get(args[1])
            val extension = args[2]
            val maxDim = args[3].toInt()
            val quality = args[4].toFloat()

            val originalBytes = Files.readAllBytes(inputPath)
            val compressed = ImageCompressionProcessor.compressImageBytes(
                originalBytes = originalBytes,
                extension = extension,
                maxDimension = maxDim,
                jpegQuality = quality
            )
            if (compressed != null) {
                Files.write(outputPath, compressed)
            }
        } catch (e: Exception) {
            System.err.println("ImageCompressWorkerMain error: ${e.message}")
            System.exit(1)
        }
    }
}

/**
 * Spawns bounded OS child JVM processes for heavy or failure-prone workloads.
 */
object SubprocessRunner {
    private val json = Json { ignoreUnknownKeys = true }

    private fun resolveJavaExecutable(): String {
        val javaHome = System.getProperty("java.home")
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val bin = File(javaHome, if (isWindows) "bin/java.exe" else "bin/java")
        return if (bin.exists()) bin.absolutePath else "java"
    }

    private fun drainStreamAsync(input: java.io.InputStream, maxBytes: Int = 65_536): Pair<Thread, ByteArrayOutputStream> {
        val baos = ByteArrayOutputStream()
        val readerThread = Thread({
            runCatching {
                input.use { stream ->
                    val buf = ByteArray(2048)
                    while (true) {
                        val read = stream.read(buf)
                        if (read == -1) break
                        val remaining = maxBytes - baos.size()
                        if (remaining > 0) {
                            baos.write(buf, 0, minOf(read, remaining))
                        }
                    }
                }
            }
        }, "subprocess-stream-drainer").apply {
            isDaemon = true
            start()
        }
        return readerThread to baos
    }

    fun runSmtpSubprocess(payload: SmtpJobPayload, timeoutSeconds: Long = 30L): SmtpJobResult {
        val javaExec = resolveJavaExecutable()
        val classpath = System.getProperty("java.class.path")
        val process = ProcessBuilder(
            javaExec,
            "-Xmx256m",
            "-Djava.net.preferIPv4Stack=true",
            "-cp",
            classpath,
            "com.gonghak.worker.SmtpWorkerMain"
        ).redirectErrorStream(true).start()

        val (drainThread, outputBuf) = drainStreamAsync(process.inputStream)
        try {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(json.encodeToString(payload))
                writer.flush()
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                runCatching { process.waitFor(2, TimeUnit.SECONDS) }
                return SmtpJobResult(success = false, message = "SMTP subprocess timed out after ${timeoutSeconds}s")
            }

            drainThread.join(1000L)
            val output = outputBuf.toString(Charsets.UTF_8).trim()
            val lastLine = output.lines().lastOrNull { it.isNotBlank() }.orEmpty()
            return runCatching { json.decodeFromString<SmtpJobResult>(lastLine) }
                .getOrElse { SmtpJobResult(success = process.exitValue() == 0, message = output.ifBlank { null }) }
        } finally {
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    fun runImageCompressSubprocess(
        originalBytes: ByteArray,
        extension: String,
        maxDimension: Int,
        jpegQuality: Float,
        timeoutSeconds: Long = 15L
    ): ByteArray? {
        val tempIn = Files.createTempFile("cistus_img_in_", ".$extension")
        val tempOut = Files.createTempFile("cistus_img_out_", ".$extension")
        return try {
            Files.write(tempIn, originalBytes)
            val javaExec = resolveJavaExecutable()
            val classpath = System.getProperty("java.class.path")
            val process = ProcessBuilder(
                javaExec,
                "-Xmx256m",
                "-Djava.awt.headless=true",
                "-cp",
                classpath,
                "com.gonghak.worker.ImageCompressWorkerMain",
                tempIn.toAbsolutePath().toString(),
                tempOut.toAbsolutePath().toString(),
                extension,
                maxDimension.toString(),
                jpegQuality.toString()
            ).redirectErrorStream(true).start()

            val (drainThread, _) = drainStreamAsync(process.inputStream)
            try {
                runCatching { process.outputStream.close() }
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    runCatching { process.waitFor(2, TimeUnit.SECONDS) }
                    return null
                }
                drainThread.join(1000L)
                if (process.exitValue() != 0 || !Files.exists(tempOut)) {
                    return null
                }
                val resultBytes = Files.readAllBytes(tempOut)
                if (resultBytes.isNotEmpty()) resultBytes else null
            } finally {
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }
        } finally {
            Files.deleteIfExists(tempIn)
            Files.deleteIfExists(tempOut)
        }
    }
}
