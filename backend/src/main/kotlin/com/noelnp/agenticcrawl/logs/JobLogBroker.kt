package com.noelnp.agenticcrawl.logs

import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds per-job log history and active SSE subscribers.
 *
 * The appender publishes here; the SSE controller subscribes here. The two
 * sides never reference each other directly, which keeps logback config
 * independent from the web layer.
 *
 * Ring buffer is bounded so a long-running job can't exhaust heap. Late
 * subscribers (e.g. browser reconnects, opening a tab on an in-progress
 * job) replay the buffer before live events begin.
 */
@Component
class JobLogBroker {

    private val buffers = ConcurrentHashMap<UUID, ArrayDeque<JobLogEvent>>()
    private val subscribers = ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>()
    private val seq = AtomicLong(0)

    fun publish(
        jobId: UUID,
        timestamp: java.time.Instant,
        level: String,
        logger: String,
        message: String,
        throwable: String?,
    ) {
        val event = JobLogEvent(
            jobId = jobId,
            seq = seq.incrementAndGet(),
            timestamp = timestamp,
            level = level,
            logger = logger,
            message = message,
            throwable = throwable,
        )

        val buf = buffers.computeIfAbsent(jobId) { ArrayDeque(BUFFER_CAPACITY) }
        synchronized(buf) {
            if (buf.size >= BUFFER_CAPACITY) buf.removeFirst()
            buf.addLast(event)
        }

        val subs = subscribers[jobId] ?: return
        val dead = mutableListOf<SseEmitter>()
        for (emitter in subs) {
            try {
                emitter.send(
                    SseEmitter.event()
                        .id(event.seq.toString())
                        .name("log")
                        .data(event),
                )
            } catch (_: Exception) {
                dead += emitter
            }
        }
        if (dead.isNotEmpty()) subs.removeAll(dead)
    }

    fun subscribe(jobId: UUID, emitter: SseEmitter) {
        val subs = subscribers.computeIfAbsent(jobId) { CopyOnWriteArrayList() }
        subs += emitter

        val cleanup = Runnable { subs.remove(emitter) }
        emitter.onCompletion(cleanup)
        emitter.onTimeout(cleanup)
        emitter.onError { cleanup.run() }

        val snapshot = buffers[jobId]?.let { buf -> synchronized(buf) { buf.toList() } }
            ?: emptyList()
        for (event in snapshot) {
            try {
                emitter.send(
                    SseEmitter.event()
                        .id(event.seq.toString())
                        .name("log")
                        .data(event),
                )
            } catch (_: Exception) {
                subs.remove(emitter)
                return
            }
        }
    }

    fun snapshot(jobId: UUID): List<JobLogEvent> =
        buffers[jobId]?.let { buf -> synchronized(buf) { buf.toList() } } ?: emptyList()

    companion object {
        private const val BUFFER_CAPACITY = 500
    }
}
