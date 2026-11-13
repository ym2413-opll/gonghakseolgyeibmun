package com.gonghak

import com.gonghak.config.DatabaseFactory
import com.gonghak.models.*
import com.gonghak.service.*
import com.gonghak.worker.SubprocessRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.*

class WorkerSeparationTest {

    @Test
    fun testDedicatedWorkerThreadNamesAndShutdown() = runBlocking {
        val workers = BackgroundWorkerManager()
        try {
            val emailThread = withContext(workers.emailDispatcher) { Thread.currentThread().name }
            val notifThread = withContext(workers.notificationDispatcher) { Thread.currentThread().name }
            val simThread = withContext(workers.similarityDispatcher) { Thread.currentThread().name }
            val attThread = withContext(workers.attachmentDispatcher) { Thread.currentThread().name }
            val aiThread = withContext(workers.aiDispatcher) { Thread.currentThread().name }

            assertTrue(emailThread.startsWith("cistus-email-worker-"), "Unexpected thread: $emailThread")
            assertTrue(notifThread.startsWith("cistus-notif-worker-"), "Unexpected thread: $notifThread")
            assertTrue(simThread.startsWith("cistus-similarity-worker-"), "Unexpected thread: $simThread")
            assertTrue(attThread.startsWith("cistus-attachment-worker-"), "Unexpected thread: $attThread")
            assertTrue(aiThread.startsWith("cistus-ai-worker-"), "Unexpected thread: $aiThread")
        } finally {
            workers.shutdown()
            assertTrue(workers.isShuttingDown)
        }
    }

    @Test
    fun testAsyncEmailOutboxAndDndReleaseBatch() = runBlocking {
        DatabaseFactory.init()
        val workers = BackgroundWorkerManager()
        val emailService = EmailService(workers)
        val notificationService = NotificationService(emailService, workers)

        try {
            val profId = UUID.fromString("22222222-2222-2222-2222-222222222201")
            val qId = UUID.randomUUID()

            val job = transaction {
                emailService.enqueueQuestionEmail(
                    recipientEmail = "worker.test@kw.ac.kr",
                    professorId = profId,
                    professorName = "김교수",
                    courseName = "공학설계입문",
                    questionId = qId,
                    questionTitle = "비동기 워커 이메일 테스트",
                    questionContent = "이메일 큐 적재 후 별도 워커 스레드에서 상태가 전이되는지 확인합니다.",
                    category = QuestionCategory.LECTURE,
                    authorDisplay = "수강생"
                )
            }
            assertNotNull(job)

            val initialStatus = transaction {
                EmailLogsTable.select { EmailLogsTable.id eq job.logId }.single()[EmailLogsTable.status]
            }
            assertEquals("QUEUED", initialStatus)

            val dispatchJob = emailService.dispatchEmailAsync(job)
            dispatchJob.join()

            // Give DB update a brief moment if needed, then verify SENT status
            var finalStatus = initialStatus
            repeat(20) {
                finalStatus = transaction {
                    EmailLogsTable.select { EmailLogsTable.id eq job.logId }.single()[EmailLogsTable.status]
                }
                if (finalStatus != "QUEUED") return@repeat
                delay(25)
            }
            assertEquals("SENT", finalStatus)

            // Verify scheduled DND held notification batch release
            val heldNotifId = UUID.randomUUID()
            transaction {
                NotificationsTable.insert {
                    it[id] = heldNotifId
                    it[userId] = profId
                    it[type] = NotificationType.QUESTION_NEW.name
                    it[title] = "보류된 DND 알림 테스트"
                    it[message] = "방해금지 해제 시 배치 스케줄러가 자동으로 풀어야 합니다."
                    it[referenceId] = qId
                    it[isRead] = false
                    it[isHeld] = true
                    it[releaseAt] = "08:00"
                    it[createdAt] = Instant.now()
                }
            }

            // Ensure DND is disabled for this test check so batch releases the held row
            notificationService.updateProfessorNotificationSetting(
                profId,
                com.gonghak.dto.UpdateProfessorNotificationSettingRequest(quietHoursEnabled = false)
            )
            val released = notificationService.releaseHeldNotificationsBatch()
            assertTrue(released >= 1, "Expected at least 1 held notification released")

            val isHeldAfter = transaction {
                NotificationsTable.select { NotificationsTable.id eq heldNotifId }.single()[NotificationsTable.isHeld]
            }
            assertFalse(isHeldAfter)
        } finally {
            workers.shutdown()
        }
    }

    @Test
    fun testIsolatedImageSubprocessCompression() {
        val image = BufferedImage(2100, 1200, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(40, 120, 200)
        g.fillRect(0, 0, 2100, 1200)
        g.dispose()

        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        val rawPng = baos.toByteArray()

        val compressed = SubprocessRunner.runImageCompressSubprocess(
            originalBytes = rawPng,
            extension = "png",
            maxDimension = 1280,
            jpegQuality = 0.82f,
            timeoutSeconds = 20L
        )
        assertNotNull(compressed, "Subprocess image worker should return compressed PNG bytes")

        val decoded = ImageIO.read(compressed.inputStream())
        assertNotNull(decoded)
        assertTrue(decoded.width <= 1280)
        assertTrue(decoded.height <= 1280)
    }
}
