package com.noelnp.agenticcrawl.job.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.noelnp.agenticcrawl.job.api.dto.CreateJobRequest
import com.noelnp.agenticcrawl.job.api.dto.JobResponse
import com.noelnp.agenticcrawl.job.service.InvalidJobStateException
import com.noelnp.agenticcrawl.job.service.JobNotFoundException
import com.noelnp.agenticcrawl.job.service.JobService
import com.noelnp.agenticcrawl.job.service.LayerNotFoundException
import com.noelnp.agenticcrawl.job.service.SessionUnavailableException
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/jobs")
class JobController(
    private val jobService: JobService,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping
    fun create(@Valid @RequestBody request: CreateJobRequest): ResponseEntity<JobResponse> {
        val job = jobService.create(request.description, request.url)
        val body = JobResponse.from(job, objectMapper)
        return ResponseEntity
            .created(URI.create("/api/jobs/${body.id}"))
            .body(body)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): JobResponse =
        JobResponse.from(jobService.get(id), objectMapper)

    @PostMapping("/{id}/confirm")
    fun confirm(@PathVariable id: UUID): JobResponse =
        JobResponse.from(jobService.confirm(id), objectMapper)

    @GetMapping(
        "/{id}/layers/{layerIndex}/screenshot",
        produces = [MediaType.IMAGE_PNG_VALUE],
    )
    fun layerScreenshot(
        @PathVariable id: UUID,
        @PathVariable layerIndex: Int,
    ): ResponseEntity<ByteArray> {
        val bytes = jobService.loadLayerScreenshot(id, layerIndex)
            ?: throw ScreenshotNotAvailableException(id, layerIndex)
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .body(bytes)
    }

    @ExceptionHandler(
        JobNotFoundException::class,
        LayerNotFoundException::class,
        ScreenshotNotAvailableException::class,
    )
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(e: RuntimeException): Map<String, String?> =
        mapOf("error" to e.message)

    @ExceptionHandler(InvalidJobStateException::class, SessionUnavailableException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleConflict(e: RuntimeException): Map<String, String?> =
        mapOf("error" to e.message)
}

class ScreenshotNotAvailableException(jobId: UUID, layerIndex: Int) :
    RuntimeException("Screenshot not yet available for job $jobId layer $layerIndex")
