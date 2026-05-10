package com.noelnp.agenticcrawl.browser

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Component
class BrowserSessionManager {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions = ConcurrentHashMap<UUID, Entry>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "session-expiry").apply { isDaemon = true }
    }

    fun register(jobId: UUID, session: LiveSession, ttlSeconds: Long, onExpire: () -> Unit) {
        val expiry = scheduler.schedule({
            log.warn("session expired jobId={} ttl={}s", jobId, ttlSeconds)
            close(jobId)
            runCatching { onExpire() }.onFailure {
                log.error("onExpire callback failed jobId={}", jobId, it)
            }
        }, ttlSeconds, TimeUnit.SECONDS)

        val previous = sessions.put(jobId, Entry(session, expiry))
        if (previous != null) {
            log.warn("replacing existing session for jobId={}", jobId)
            previous.expiry.cancel(false)
            runCatching { previous.session.close() }
        }
    }

    fun take(jobId: UUID): LiveSession? {
        val entry = sessions.remove(jobId) ?: return null
        entry.expiry.cancel(false)
        return entry.session
    }

    fun close(jobId: UUID) {
        val entry = sessions.remove(jobId) ?: return
        entry.expiry.cancel(false)
        runCatching { entry.session.close() }.onFailure {
            log.warn("error closing session jobId={}: {}", jobId, it.message)
        }
    }

    @PreDestroy
    fun closeAll() {
        log.info("closing {} live sessions on shutdown", sessions.size)
        sessions.keys.toList().forEach { close(it) }
        scheduler.shutdownNow()
    }

    private data class Entry(val session: LiveSession, val expiry: ScheduledFuture<*>)
}
