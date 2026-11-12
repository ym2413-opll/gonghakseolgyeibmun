package com.gonghak.service

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

object AdminConsoleManager {
    private val logger = LoggerFactory.getLogger(AdminConsoleManager::class.java)
    @Volatile
    private var started = false

    fun startConsoleListener(userService: UserService) {
        if (started) return
        // Avoid starting interactive stdin reader during JUnit test runs
        val isTest = Thread.currentThread().stackTrace.any {
            it.className.startsWith("org.junit") || it.className.startsWith("io.ktor.server.testing")
        }
        if (isTest) return

        started = true
        thread(isDaemon = true, name = "admin-console-listener") {
            logger.info("==================================================================")
            logger.info("[ADMIN CONSOLE] 서버 콘솔 관리자 명령어 입력기가 활성화되었습니다.")
            logger.info("  • 관리자 권한 부여: admin <로그인ID> (또는 grant-admin <로그인ID>)")
            logger.info("  • 관리자 권한 해제: revoke-admin <로그인ID>")
            logger.info("  • 가입 계정 목록 조회: users")
            logger.info("==================================================================")

            try {
                val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue

                    val parts = trimmed.split("\\s+".toRegex())
                    val cmd = parts[0].lowercase()
                    val arg = parts.getOrNull(1)

                    runBlocking {
                        try {
                            when (cmd) {
                                "admin", "grant-admin" -> {
                                    if (arg.isNullOrBlank()) {
                                        println("[ADMIN CONSOLE] 사용법: admin <로그인ID>")
                                    } else {
                                        val user = userService.grantOrRevokeAdminByUsername(arg, grant = true)
                                        println("[ADMIN CONSOLE] 성공: 계정 '${user.username ?: user.name}' (UUID: ${user.id})에 관리자 권한이 부여되었습니다.")
                                    }
                                }
                                "revoke-admin", "unadmin" -> {
                                    if (arg.isNullOrBlank()) {
                                        println("[ADMIN CONSOLE] 사용법: revoke-admin <로그인ID>")
                                    } else {
                                        val user = userService.grantOrRevokeAdminByUsername(arg, grant = false)
                                        println("[ADMIN CONSOLE] 성공: 계정 '${user.username ?: user.name}'의 관리자 권한이 해제되었습니다.")
                                    }
                                }
                                "users", "list-users" -> {
                                    val users = userService.getAllUsersForAdmin()
                                    if (users.isEmpty()) {
                                        println("[ADMIN CONSOLE] 현재 가입된 일반 계정이 없습니다.")
                                    } else {
                                        println("=== 가입 계정 목록 (${users.size}명) ===")
                                        users.forEach { u ->
                                            println(" - ID: ${u.username ?: "-"} | 이름: ${u.name} | 역할: ${u.role} | 학과: ${u.department} | 관리자: ${u.isAdmin} | 탈퇴: ${u.isWithdrawn}")
                                        }
                                    }
                                }
                                "help" -> {
                                    println("사용 가능한 명령어: admin <ID>, revoke-admin <ID>, users, help")
                                }
                                else -> {
                                    println("[ADMIN CONSOLE] 알 수 없는 명령어입니다 ('$cmd'). 'help'를 입력해 보세요.")
                                }
                            }
                        } catch (e: Exception) {
                            println("[ADMIN CONSOLE] 오류: ${e.message}")
                        }
                    }
                }
            } catch (_: Exception) {
                // Standard input closed or unavailable
            }
        }
    }
}
