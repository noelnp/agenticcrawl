package com.noelnp.agenticcrawl.logs

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import java.time.Instant
import java.util.UUID

/**
 * Forwards SLF4J events tagged with MDC key `jobId` into the per-job log
 * broker so SSE clients can subscribe to them.
 *
 * Auto-installed on context refresh — no logback.xml needed. The MDC key is
 * already set by JobService / ScriptRunner around their job-scoped work, so
 * every downstream log line (orchestrator, planner, executor) is captured
 * with no further service-code changes.
 */
@Configuration
class JobLogAppenderConfig(private val broker: JobLogBroker) {

    @EventListener(ContextRefreshedEvent::class)
    fun install() {
        val ctx = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val root = ctx.getLogger(Logger.ROOT_LOGGER_NAME)

        // Spring DevTools restarts the app context but logback's LoggerContext
        // is shared across restarts via the base classloader. Without removing
        // the previous instance first, a stale appender bound to the dead
        // broker survives and swallows events.
        root.detachAppender(APPENDER_NAME)

        val appender = JobLogAppender(broker).apply {
            name = APPENDER_NAME
            context = ctx
            start()
        }
        root.addAppender(appender)
    }

    companion object {
        const val APPENDER_NAME = "job-log-broker"
    }
}

class JobLogAppender(
    private val broker: JobLogBroker,
) : AppenderBase<ILoggingEvent>() {

    override fun append(event: ILoggingEvent) {
        val raw = event.mdcPropertyMap?.get(MDC_KEY) ?: return
        val jobId = runCatching { UUID.fromString(raw) }.getOrNull() ?: return

        broker.publish(
            jobId = jobId,
            timestamp = Instant.ofEpochMilli(event.timeStamp),
            level = event.level.toString(),
            logger = event.loggerName.substringAfterLast('.'),
            message = event.formattedMessage,
            throwable = event.throwableProxy?.let(::formatThrowable),
        )
    }

    private fun formatThrowable(proxy: IThrowableProxy): String {
        val builder = StringBuilder()
        builder.append(proxy.className)
        proxy.message?.let { builder.append(": ").append(it) }
        return builder.toString()
    }

    companion object {
        const val MDC_KEY = "jobId"
    }
}
