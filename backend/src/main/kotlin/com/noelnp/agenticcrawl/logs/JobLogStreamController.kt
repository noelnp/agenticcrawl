package com.noelnp.agenticcrawl.logs

import com.noelnp.agenticcrawl.job.service.JobNotFoundException
import com.noelnp.agenticcrawl.job.service.JobService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

@RestController
@RequestMapping("/api/jobs")
class JobLogStreamController(
    private val broker: JobLogBroker,
    private val jobService: JobService,
) {

    @GetMapping("/{id}/logs/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable id: UUID): SseEmitter {
        jobService.get(id)

        val emitter = SseEmitter(STREAM_TIMEOUT_MS)
        broker.subscribe(id, emitter)
        return emitter
    }

    companion object {
        private const val STREAM_TIMEOUT_MS = 30L * 60L * 1000L
    }
}
