package com.gonghak.worker

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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
    val messageId: String? = null
)

@Serializable
data class SmtpJobResult(
    val success: Boolean,
    val message: String? = null
)

/**
 * Shared low-level SMTP socket client used by both the dedicated email worker thread
 * and the isolated SMTP OS subprocess worker.
 */
object SmtpTransport {
    private fun sanitizeHeaderAddress(raw: String): String =
        raw.replace(Regex("[\\r\\n\\u0000<>]"), "").trim()

    private fun readSmtpResponse(reader: BufferedReader): Pair<Int, List<String>> {
        val lines = mutableListOf<String>()
        var code = -1
        while (true) {
            val line = reader.readLine() ?: break
            lines.add(line)
            if (line.length >= 3) {
                code = line.substring(0, 3).toIntOrNull() ?: code
            }
            if (line.length < 4 || line[3] != '-') {
                break
            }
        }
        return code to lines
    }

    private fun writeCrlf(writer: PrintWriter, line: String) {
        writer.print(line)
        writer.print("\r\n")
        writer.flush()
    }

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

        val implicitTls = payload.port == 465
        var sock: Socket = if (implicitTls) {
            (SSLSocketFactory.getDefault().createSocket(payload.host, payload.port) as SSLSocket).apply {
                soTimeout = 4000
                startHandshake()
            }
        } else {
            Socket(payload.host, payload.port).apply {
                soTimeout = 4000
            }
        }

        var isTlsActive = implicitTls
        try {
            var reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            var writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8), false)

            readSmtpResponse(reader)
            writeCrlf(writer, "EHLO kwangwoon.ac.kr")
            val (_, ehloCaps) = readSmtpResponse(reader)

            val supportsStartTls = ehloCaps.any { it.contains("STARTTLS", ignoreCase = true) }
            if (!isTlsActive && supportsStartTls) {
                writeCrlf(writer, "STARTTLS")
                val (startTlsCode, _) = readSmtpResponse(reader)
                if (startTlsCode in 200..299) {
                    val sslSock = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(sock, payload.host, payload.port, true) as SSLSocket
                    sslSock.soTimeout = 4000
                    sslSock.startHandshake()
                    sock = sslSock
                    isTlsActive = true
                    reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                    writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8), false)
                    writeCrlf(writer, "EHLO kwangwoon.ac.kr")
                    readSmtpResponse(reader)
                }
            }

            if (!payload.user.isNullOrBlank() && !payload.pass.isNullOrBlank()) {
                val allowPlaintext = System.getenv("SMTP_ALLOW_INSECURE_PLAINTEXT")
                    ?.trim()?.equals("true", ignoreCase = true) == true
                if (!isTlsActive && !allowPlaintext) {
                    throw SecurityException("TLS/STARTTLS 없이 SMTP AUTH 인증을 전송할 수 없습니다.")
                }
                writeCrlf(writer, "AUTH LOGIN")
                readSmtpResponse(reader)
                writeCrlf(writer, Base64.getEncoder().encodeToString(payload.user.toByteArray(Charsets.UTF_8)))
                readSmtpResponse(reader)
                writeCrlf(writer, Base64.getEncoder().encodeToString(payload.pass.toByteArray(Charsets.UTF_8)))
                readSmtpResponse(reader)
            }

            writeCrlf(writer, "MAIL FROM:<$cleanFrom>")
            readSmtpResponse(reader)
            writeCrlf(writer, "RCPT TO:<$cleanTo>")
            readSmtpResponse(reader)
            writeCrlf(writer, "DATA")
            readSmtpResponse(reader)

            val encodedSubject = "=?UTF-8?B?${Base64.getEncoder().encodeToString(payload.subject.toByteArray(Charsets.UTF_8))}?="
            writeCrlf(writer, "From: <$cleanFrom>")
            writeCrlf(writer, "To: <$cleanTo>")
            val cleanReplyTo = payload.replyTo?.replace(Regex("[\\r\\n<>]"), "")?.trim()
            if (!cleanReplyTo.isNullOrBlank() && cleanReplyTo.contains("@")) {
                writeCrlf(writer, "Reply-To: <$cleanReplyTo>")
            }
            val cleanMessageId = payload.messageId?.replace(Regex("[\\r\\n]"), "")?.trim()
            if (!cleanMessageId.isNullOrBlank()) {
                val wrappedMsgId = if (cleanMessageId.startsWith("<") && cleanMessageId.endsWith(">")) cleanMessageId else "<$cleanMessageId>"
                writeCrlf(writer, "Message-ID: $wrappedMsgId")
            }
            writeCrlf(writer, "Subject: $encodedSubject")
            writeCrlf(writer, "MIME-Version: 1.0")
            writeCrlf(writer, "Content-Type: text/plain; charset=UTF-8")
            writeCrlf(writer, "")
            writeCrlf(writer, dotStuffBody(payload.body))
            writeCrlf(writer, ".")
            readSmtpResponse(reader)
            writeCrlf(writer, "QUIT")
        } finally {
            runCatching { sock.close() }
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
                val param = writer.defaultWriteParam
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = jpegQuality
                val ios = ImageIO.createImageOutputStream(outputStream)
                writer.output = ios
                writer.write(null, IIOImage(resizedImage, null, null), param)
                writer.dispose()
                ios.close()
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

    fun runSmtpSubprocess(payload: SmtpJobPayload, timeoutSeconds: Long = 10L): SmtpJobResult {
        val javaExec = resolveJavaExecutable()
        val classpath = System.getProperty("java.class.path")
        val process = ProcessBuilder(
            javaExec,
            "-Xmx64m",
            "-Djava.net.preferIPv4Stack=true",
            "-cp",
            classpath,
            "com.gonghak.worker.SmtpWorkerMain"
        ).redirectErrorStream(true).start()

        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(json.encodeToString(payload))
            writer.flush()
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return SmtpJobResult(success = false, message = "SMTP subprocess timed out after ${timeoutSeconds}s")
        }

        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        val lastLine = output.lines().lastOrNull { it.isNotBlank() }.orEmpty()
        return runCatching { json.decodeFromString<SmtpJobResult>(lastLine) }
            .getOrElse { SmtpJobResult(success = process.exitValue() == 0, message = output.ifBlank { null }) }
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

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0 || !Files.exists(tempOut)) {
                return null
            }
            val resultBytes = Files.readAllBytes(tempOut)
            if (resultBytes.isNotEmpty()) resultBytes else null
        } finally {
            Files.deleteIfExists(tempIn)
            Files.deleteIfExists(tempOut)
        }
    }
}
