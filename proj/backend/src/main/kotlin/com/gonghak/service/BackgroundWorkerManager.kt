package com.gonghak.service

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central coordinator for dedicated background worker threads and periodic schedulers.
 * Isolates blocking I/O (SMTP, Gemini HTTP, disk writes), CPU-bound work (ImageIO, vectors),
 * and scheduled maintenance (DND notification release, orphan attachment cleanup)
 * from Ktor HTTP request threads and database transaction pools.
 */
class BackgroundWorkerManager {
    private val logger = LoggerFactory.getLogger(BackgroundWorkerManager::class.java)

    @Volatile
    var isShuttingDown: Boolean = false
        private set

    private val emailExecutor: ExecutorService = newNamedDaemonPool("cistus-email-worker", threads = 2)
    private val notificationExecutor: ExecutorService = newNamedDaemonPool("cistus-notif-worker", threads = 2)
    private val similarityExecutor: ExecutorService = newNamedDaemonPool("cistus-similarity-worker", threads = 2)
    private val attachmentExecutor: ExecutorService = newNamedDaemonPool("cistus-attachment-worker", threads = 2)
    private val aiExecutor: ExecutorService = newNamedDaemonPool("cistus-ai-worker", threads = 4)
    private val schedulerExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(
        2,
        NamedDaemonThreadFactory("cistus-scheduler")
    )

    val emailDispatcher: ExecutorCoroutineDispatcher = emailExecutor.asCoroutineDispatcher()
    val notificationDispatcher: ExecutorCoroutineDispatcher = notificationExecutor.asCoroutineDispatcher()
    val similarityDispatcher: ExecutorCoroutineDispatcher = similarityExecutor.asCoroutineDispatcher()
    val attachmentDispatcher: ExecutorCoroutineDispatcher = attachmentExecutor.asCoroutineDispatcher()
    val aiDispatcher: ExecutorCoroutineDispatcher = aiExecutor.asCoroutineDispatcher()

    private val supervisorJob = SupervisorJob()
    private val emailScope = CoroutineScope(supervisorJob + emailDispatcher)
    private val notificationScope = CoroutineScope(supervisorJob + notificationDispatcher)
    private val similarityScope = CoroutineScope(supervisorJob + similarityDispatcher)
    private val attachmentScope = CoroutineScope(supervisorJob + attachmentDispatcher)

    private var schedulersStarted = false

    fun launchEmailTask(taskName: String, block: suspend () -> Unit): Job {
        return emailScope.launch {
            runGuarded(taskName, block)
        }
    }

    fun launchNotificationTask(taskName: String, block: suspend () -> Unit): Job {
        return notificationScope.launch {
            runGuarded(taskName, block)
        }
    }

    fun launchSimilarityTask(taskName: String, block: suspend () -> Unit): Job {
        return similarityScope.launch {
            runGuarded(taskName, block)
        }
    }

    fun launchAttachmentTask(taskName: String, block: suspend () -> Unit): Job {
        return attachmentScope.launch {
            runGuarded(taskName, block)
        }
    }

    @Synchronized
    fun startSchedulers(
        notificationService: NotificationService,
        attachmentService: AttachmentService
    ) {
        if (schedulersStarted || isShuttingDown) return
        schedulersStarted = true

        // 1. DND quiet-hours release scheduler (runs every 60s on cistus-scheduler thread)
        schedulerExecutor.scheduleWithFixedDelay({
            if (isShuttingDown) return@scheduleWithFixedDelay
            runBlocking {
                runGuarded("dnd-release-scheduler") {
                    val releasedCount = notificationService.releaseHeldNotificationsBatch()
                    if (releasedCount > 0) {
                        logger.info("[Scheduler] Released $releasedCount held DND notifications.")
                    }
                }
            }
        }, 15L, 60L, TimeUnit.SECONDS)

        // 2. Orphan temporary attachment cleanup scheduler (runs every 30m on cistus-scheduler thread)
        schedulerExecutor.scheduleWithFixedDelay({
            if (isShuttingDown) return@scheduleWithFixedDelay
            runBlocking {
                runGuarded("attachment-cleanup-scheduler") {
                    val deletedCount = attachmentService.cleanupOrphanAttachments()
                    if (deletedCount > 0) {
                        logger.info("[Scheduler] Cleaned up $deletedCount orphaned attachment(s).")
                    }
                }
            }
        }, 300L, 1800L, TimeUnit.SECONDS)

        logger.info("[WorkerManager] Started background worker pools (email, notification, similarity, attachment, ai) and periodic schedulers.")
    }

    @Synchronized
    fun startImapPollingScheduler(poller: EmailReplyImapPoller) {
        if (isShuttingDown || !poller.isEnabled) return
        val intervalSeconds = poller.config.pollIntervalSeconds
        schedulerExecutor.scheduleWithFixedDelay({
            if (isShuttingDown) return@scheduleWithFixedDelay
            runBlocking {
                runGuarded("imap-reply-poller") {
                    val count = poller.pollOnce()
                    if (count > 0) {
                        logger.info("[IMAP Poller] Registered $count professor email reply(s).")
                    }
                }
            }
        }, 5L, intervalSeconds, TimeUnit.SECONDS)
        logger.info(
            "[IMAP Poller] Started inbound email reply polling on {}:{} ({}s interval).",
            poller.config.host,
            poller.config.port,
            intervalSeconds
        )
    }

    fun shutdown(timeoutSeconds: Long = 2L) {
        if (isShuttingDown) return
        isShuttingDown = true

        supervisorJob.cancel()

        val allExecutors = listOf(
            schedulerExecutor,
            emailExecutor,
            notificationExecutor,
            similarityExecutor,
            attachmentExecutor,
            aiExecutor
        )

        allExecutors.forEach { it.shutdown() }
        allExecutors.forEach { exec ->
            try {
                if (!exec.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                    exec.shutdownNow()
                }
            } catch (_: InterruptedException) {
                exec.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        logger.info("[WorkerManager] All background worker pools and schedulers shut down cleanly.")
    }

    private suspend fun runGuarded(taskName: String, block: suspend () -> Unit) {
        if (isShuttingDown) return
        try {
            block()
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected on shutdown
        } catch (e: Exception) {
            if (!isShuttingDown) {
                logger.warn("[Worker:$taskName] Background task failed: ${e.message}")
            }
        }
    }

    private fun newNamedDaemonPool(prefix: String, threads: Int): ExecutorService {
        return Executors.newFixedThreadPool(threads, NamedDaemonThreadFactory(prefix))
    }

    private class NamedDaemonThreadFactory(private val prefix: String) : ThreadFactory {
        private val index = AtomicInteger(1)

        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "$prefix-${index.getAndIncrement()}")
            t.isDaemon = true
            return t
        }
    }
}
