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
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
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

    @Test
    fun testLegacyNullCreatedAtMigrationDoesNotCrashInit() {
        DatabaseFactory.init()
        val legacyUserId = UUID.randomUUID()
        transaction {
            exec("ALTER TABLE users ALTER COLUMN created_at DROP NOT NULL")
            exec(
                """
                INSERT INTO users (id, name, role, identifier, department, display_tag, is_demo, is_admin, is_withdrawn, created_at)
                VALUES ('$legacyUserId', '레거시사용자', 'STUDENT', '2020999999', '컴퓨터공학과', '컴공20', false, false, false, NULL)
                """.trimIndent()
            )
        }

        // Must backfill NULL created_at and apply DDL without aborting subsequent seeding
        DatabaseFactory.init()

        val migratedCreatedAt = transaction {
            UsersTable.select { UsersTable.id eq legacyUserId }.singleOrNull()?.get(UsersTable.createdAt)
        }
        assertNotNull(migratedCreatedAt, "Legacy user created_at NULL should be backfilled on DatabaseFactory.init()")

        // Also verify adding a missing NOT NULL created_at column onto a non-empty table succeeds
        transaction {
            exec("ALTER TABLE users DROP COLUMN created_at")
        }
        DatabaseFactory.init()
        val reAddedCreatedAt = transaction {
            UsersTable.select { UsersTable.id eq legacyUserId }.singleOrNull()?.get(UsersTable.createdAt)
        }
        assertNotNull(reAddedCreatedAt, "Missing NOT NULL created_at column should be added with DEFAULT CURRENT_TIMESTAMP")
    }

    @Test
    fun testAdminLoopbackSocketCommandWithoutStdin() = runBlocking {
        DatabaseFactory.init()
        val userService = UserService()
        val reg = userService.registerUser(
            com.gonghak.dto.RegisterRequest(
                role = UserRole.STUDENT,
                username = "",
                password = "password1234",
                name = "소켓관리대상",
                department = "소프트웨어학과",
                identifier = "20267788"
            )
        )
        assertFalse(reg.user.isAdmin)

        // Start loopback control socket with explicit port (simulates headless systemd run)
        AdminConsoleManager.stopConsoleListener()
        System.setProperty("admin.console.port", "28089")
        System.setProperty("admin.console.secretFile", "build/tmp/test-admin-ctl.secret")
        try {
            AdminConsoleManager.startConsoleListener(userService)
            // 1. Unauthenticated connection WITHOUT AUTH handshake must be rejected
            var unauthRejected = false
            for (attempt in 1..20) {
                try {
                    Socket("127.0.0.1", 28089).use { sock ->
                        val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true)
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                        writer.println("users")
                        val resp = reader.readLine().orEmpty()
                        unauthRejected = resp.contains("오류") && resp.contains("인증")
                    }
                    break
                } catch (_: Exception) {
                    delay(50)
                }
            }
            assertTrue(unauthRejected, "Unauthenticated loopback socket command must be rejected")

            // 2. Authenticated connection WITH AUTH <token> handshake succeeds
            val controlToken = AdminConsoleManager.readClientControlToken()
            assertNotNull(controlToken, "Control token should be generated and readable locally")
            var connected = false
            var responseLines = listOf<String>()
            for (attempt in 1..20) {
                try {
                    Socket("127.0.0.1", 28089).use { sock ->
                        connected = true
                        val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true)
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                        writer.println("AUTH $controlToken")
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line == "__END_ADMIN_RESPONSE__") break
                        }
                        writer.println("admin 20267788")
                        val lines = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line == "__END_ADMIN_RESPONSE__") break
                            lines.add(line)
                        }
                        responseLines = lines
                    }
                    break
                } catch (_: Exception) {
                    delay(50)
                }
            }
            assertTrue(connected, "Admin loopback socket should accept connection cleanly")
            assertTrue(responseLines.any { it.contains("성공") && it.contains("20267788") })

            val updatedUser = userService.getUserById(UUID.fromString(reg.user.id))
            assertTrue(updatedUser?.isAdmin == true, "Admin flag should be persisted via socket command")

            val revokeOutput = AdminConsoleManager.executeCommand("revoke-admin 20267788", userService)
            assertTrue(revokeOutput.contains("해제"))
            assertFalse(userService.getUserById(UUID.fromString(reg.user.id))?.isAdmin == true)
        } finally {
            AdminConsoleManager.stopConsoleListener()
            java.nio.file.Files.deleteIfExists(AdminConsoleManager.resolveSecretFilePath())
            System.clearProperty("admin.console.port")
            System.clearProperty("admin.console.secretFile")
        }
    }

    @Test
    fun testAiRefineDistinguishesProfessorNameFromStudentName() = runBlocking {
        val workerManager = BackgroundWorkerManager()
        val service = AiRefineService(apiKey = null, workerManager = workerManager)
        try {
            val res1 = service.refineQuestion(
                rawContent = "과제 제출 기한 하루만 연장 가능한가요?",
                rawTitle = "과제 연장 문의",
                courseName = "자료구조",
                category = QuestionCategory.ASSIGNMENT,
                professorName = "박철수",
                studentName = "홍길동",
                studentDepartment = "컴퓨터정보공학부",
                studentIdentifier = "20261234"
            )
            assertTrue(res1.content.startsWith("안녕하십니까, 박철수 교수님."), "Should greet actual professor: ${res1.content}")
            assertFalse(res1.content.contains("홍길동 교수님"), "Must never place student name in professor greeting")
            assertTrue(res1.content.contains("홍길동"), "Student name should appear in self-intro")

            val res2 = service.refineQuestion(
                rawContent = "수업 내용이 헷갈립니다.",
                courseName = "공학설계입문",
                category = QuestionCategory.LECTURE,
                professorName = "이교수",
                studentName = "김학생"
            )
            assertTrue(res2.content.startsWith("안녕하십니까, 이교수님."), "Should format '이교수' cleanly as '이교수님': ${res2.content}")
            assertFalse(res2.content.contains("이교수 교수님"), "Must not duplicate '교수' in greeting")
        } finally {
            service.close()
            workerManager.shutdown()
        }
    }
}
