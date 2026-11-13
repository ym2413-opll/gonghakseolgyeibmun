package com.gonghak.service

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Interactive & systemd-compatible administrator control manager.
 * - Reads stdin commands when attached to a TTY (`admin <ID>`, `revoke-admin <ID>`, `users`, `help`).
 * - Also exposes a strict loopback (`127.0.0.1`) TCP control listener so background services
 *   (e.g. systemd `cistus-be`) can still be administered locally via `--admin` / `admin-ctl.sh`.
 */
object AdminConsoleManager {
    private val logger = LoggerFactory.getLogger(AdminConsoleManager::class.java)
    private const val DEFAULT_CONTROL_PORT = 18089
    private const val READY_PROMPT = "ADMIN> "

    private val stdinStarted = AtomicBoolean(false)
    private val socketStarted = AtomicBoolean(false)
    private val activeUserService = AtomicReference<UserService?>(null)
    private val secureRandom = SecureRandom()
    @Volatile
    private var controlServerSocket: ServerSocket? = null
    @Volatile
    private var activeControlToken: String? = null

    fun resolveSecretFilePath(): Path {
        val raw = System.getProperty("admin.console.secretFile")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: System.getenv("ADMIN_CTL_SECRET_FILE")?.trim()?.takeIf { it.isNotEmpty() }
            ?: ".admin-ctl.secret"
        return Paths.get(raw)
    }

    internal fun ensureControlToken(): String {
        val explicit = System.getProperty("admin.console.token")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: System.getenv("ADMIN_CTL_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) {
            activeControlToken = explicit
            return explicit
        }

        val existing = activeControlToken
        if (!existing.isNullOrBlank()) return existing

        val tokenBytes = ByteArray(32)
        secureRandom.nextBytes(tokenBytes)
        val generated = tokenBytes.joinToString("") { "%02x".format(it) }
        activeControlToken = generated

        val secretPath = resolveSecretFilePath()
        runCatching {
            secretPath.parent?.let { Files.createDirectories(it) }
            Files.write(
                secretPath,
                (generated + "\n").toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            runCatching {
                Files.setPosixFilePermissions(secretPath, PosixFilePermissions.fromString("rw-------"))
            }
        }.onFailure { err ->
            logger.warn("[ADMIN CONSOLE] 제어 토큰 파일 저장 실패 (${secretPath}): ${err.message}")
        }
        return generated
    }

    internal fun readClientControlToken(): String? {
        System.getProperty("admin.console.token")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        System.getenv("ADMIN_CTL_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        activeControlToken?.takeIf { it.isNotBlank() }?.let { return it }

        val secretPath = resolveSecretFilePath()
        if (Files.isReadable(secretPath)) {
            return Files.readString(secretPath, StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    fun resolveControlPort(): Int {
        val raw = System.getProperty("admin.console.port")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: System.getenv("ADMIN_CONSOLE_PORT")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return DEFAULT_CONTROL_PORT
        if (raw.equals("off", ignoreCase = true) || raw.equals("false", ignoreCase = true)) {
            return 0
        }
        return raw.toIntOrNull() ?: DEFAULT_CONTROL_PORT
    }

    fun startConsoleListener(userService: UserService) {
        activeUserService.set(userService)
        startStdinListener()
        startLoopbackControlListener(resolveControlPort())
    }

    fun stopConsoleListener() {
        runCatching { controlServerSocket?.close() }
        controlServerSocket = null
        activeControlToken = null
        socketStarted.set(false)
    }

    private fun startStdinListener() {
        if (!stdinStarted.compareAndSet(false, true)) return

        thread(isDaemon = true, name = "admin-console-listener") {
            logger.info("[ADMIN CONSOLE] 서버 콘솔 관리자 명령어 입력기가 활성화되었습니다.")
            logger.info("  • 관리자 권한 부여: admin <로그인ID 또는 학번> (또는 grant-admin <ID>)")
            logger.info("  • 관리자 권한 해제: revoke-admin <로그인ID 또는 학번>")
            logger.info("  • 가입 계정 목록: users")

            try {
                val reader = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    val service = activeUserService.get() ?: continue
                    val output = runBlocking { executeCommand(line, service) }
                    if (output.isNotBlank()) {
                        println(output)
                    }
                }
            } catch (e: Exception) {
                logger.debug("[ADMIN CONSOLE] Stdin listener stopped: ${e.message}")
            } finally {
                stdinStarted.set(false)
            }
        }
    }

    fun startLoopbackControlListener(port: Int) {
        if (port <= 0) return
        if (!socketStarted.compareAndSet(false, true)) return
        ensureControlToken()

        thread(isDaemon = true, name = "admin-control-socket") {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                }
                controlServerSocket = server
                logger.info("[ADMIN CONSOLE] 백그라운드(systemd) 관리자 제어 소켓 대기 중: 127.0.0.1:${server.localPort}")

                while (!server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    thread(isDaemon = true, name = "admin-control-client") {
                        handleSocketClient(client)
                    }
                }
            } catch (e: Exception) {
                logger.warn("[ADMIN CONSOLE] 로컬 관리자 소켓 바인딩 생략/종료 (127.0.0.1:$port): ${e.message}")
            } finally {
                socketStarted.set(false)
            }
        }
    }

    private fun handleSocketClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 300_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
            val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true)

            val expectedToken = ensureControlToken()
            val authLine = reader.readLine()?.trim()
            if (authLine == null || !authLine.startsWith("AUTH ")) {
                writer.println("[ADMIN CONSOLE] 오류: 로컬 제어 소켓 인증(AUTH <token>)이 필요합니다.")
                writer.println(END_OF_RESPONSE_MARKER)
                return
            }
            val providedToken = authLine.removePrefix("AUTH ").trim()
            val authValid = MessageDigest.isEqual(
                expectedToken.toByteArray(StandardCharsets.UTF_8),
                providedToken.toByteArray(StandardCharsets.UTF_8)
            )
            if (!authValid) {
                writer.println("[ADMIN CONSOLE] 오류: 제어 소켓 인증 토큰이 유효하지 않습니다.")
                writer.println(END_OF_RESPONSE_MARKER)
                return
            }
            writer.println("[ADMIN CONSOLE] 로컬 제어 소켓 인증 성공")
            writer.println(END_OF_RESPONSE_MARKER)

            while (true) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.equals("exit", ignoreCase = true) || trimmed.equals("quit", ignoreCase = true)) {
                    writer.println("[ADMIN CONSOLE] 연결을 종료합니다.")
                    writer.println(END_OF_RESPONSE_MARKER)
                    break
                }
                val service = activeUserService.get()
                val response = if (service == null) {
                    "[ADMIN CONSOLE] 오류: UserService가 아직 초기화되지 않았습니다."
                } else {
                    runBlocking { executeCommand(trimmed, service) }
                }
                if (response.isNotBlank()) {
                    writer.println(response)
                }
                writer.println(END_OF_RESPONSE_MARKER)
            }
        }
    }

    suspend fun executeCommand(rawLine: String, userService: UserService): String {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return ""

        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim().orEmpty()

        return try {
            when (cmd) {
                "admin", "grant-admin" -> {
                    if (arg.isBlank()) {
                        "[ADMIN CONSOLE] 사용법: admin <로그인ID 또는 학번>"
                    } else {
                        val user = userService.grantOrRevokeAdminByUsername(arg, grant = true)
                        "[ADMIN CONSOLE] 성공: 계정 '${user.username ?: user.name}' (학번/교번: ${user.identifier}, UUID: ${user.id})에 관리자 권한이 부여되었습니다."
                    }
                }
                "revoke-admin", "unadmin" -> {
                    if (arg.isBlank()) {
                        "[ADMIN CONSOLE] 사용법: revoke-admin <로그인ID 또는 학번>"
                    } else {
                        val user = userService.grantOrRevokeAdminByUsername(arg, grant = false)
                        "[ADMIN CONSOLE] 성공: 계정 '${user.username ?: user.name}' (UUID: ${user.id})의 관리자 권한이 해제되었습니다."
                    }
                }
                "users" -> {
                    val users = userService.getAllUsersForAdmin()
                    if (users.isEmpty()) {
                        "[ADMIN CONSOLE] 현재 가입된 일반 계정이 없습니다."
                    } else {
                        buildString {
                            appendLine("[ADMIN CONSOLE] 가입 계정 목록 (${users.size}명):")
                            users.forEach { u ->
                                appendLine(" - ID: ${u.username ?: "-"} | 학번/교번: ${u.identifier} | 이름: ${u.name} | 역할: ${u.role} | 학과: ${u.department} | 관리자: ${u.isAdmin} | 탈퇴: ${u.isWithdrawn}")
                            }
                        }.trimEnd()
                    }
                }
                "help" -> {
                    "사용 가능한 명령어: admin <ID|학번>, revoke-admin <ID|학번>, users, help"
                }
                else -> {
                    "[ADMIN CONSOLE] 알 수 없는 명령어입니다 ('$cmd'). 'help'를 입력해 보세요."
                }
            }
        } catch (e: Exception) {
            "[ADMIN CONSOLE] 오류: ${e.message}"
        }
    }

    private const val END_OF_RESPONSE_MARKER = "__END_ADMIN_RESPONSE__"

    fun runCli(args: Array<String>): Int {
        val port = resolveControlPort().takeIf { it > 0 } ?: DEFAULT_CONTROL_PORT
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 3000)
                socket.soTimeout = 10_000
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))

                fun sendAndPrint(command: String, suppressOkOutput: Boolean = false): Boolean {
                    writer.println(command)
                    var hasError = false
                    while (true) {
                        val respLine = reader.readLine() ?: break
                        if (respLine == END_OF_RESPONSE_MARKER) break
                        if (respLine.startsWith("[ADMIN CONSOLE] 오류:")) {
                            hasError = true
                        }
                        if (!suppressOkOutput || hasError) {
                            println(respLine)
                        }
                    }
                    return !hasError
                }

                val token = readClientControlToken()
                if (token.isNullOrBlank()) {
                    System.err.println("[ADMIN CLI] 제어 소켓 인증 토큰을 찾을 수 없습니다 (${resolveSecretFilePath()}). ADMIN_CTL_TOKEN 환경변수 또는 서버 권한을 확인해 주세요.")
                    return 1
                }
                if (!sendAndPrint("AUTH $token", suppressOkOutput = true)) {
                    return 1
                }

                if (args.isNotEmpty()) {
                    val command = args.joinToString(" ").trim()
                    return if (sendAndPrint(command)) 0 else 1
                }

                println("cistus-be 관리자 콘솔 연결됨 (127.0.0.1:$port). 'help' 또는 'exit'를 입력하세요.")
                val consoleReader = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))
                while (true) {
                    print(READY_PROMPT)
                    System.out.flush()
                    val line = consoleReader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    sendAndPrint(trimmed)
                    if (trimmed.equals("exit", ignoreCase = true) || trimmed.equals("quit", ignoreCase = true)) {
                        break
                    }
                }
                0
            }
        } catch (e: Exception) {
            System.err.println("[ADMIN CLI] 백엔드 서버(127.0.0.1:$port)에 연결할 수 없습니다: ${e.message}")
            System.err.println("  • cistus-be 서비스가 실행 중인지 확인하거나 ADMIN_CONSOLE_PORT 환경변수를 확인해 주세요.")
            1
        }
    }
}
