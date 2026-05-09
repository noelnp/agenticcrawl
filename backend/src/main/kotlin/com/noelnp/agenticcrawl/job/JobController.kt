package com.noelnp.agenticcrawl.job

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
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/jobs")
class JobController(private val jobService: JobService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateJobRequest): JobResponse =
        JobResponse.from(jobService.create(request.description, request.url))

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): JobResponse =
        JobResponse.from(jobService.get(id))

    @GetMapping("/{id}/screenshot", produces = [MediaType.IMAGE_PNG_VALUE])
    fun screenshot(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val job = jobService.get(id)
        val bytes = job.screenshot
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Screenshot not yet available")
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .body(bytes)
    }

    @ExceptionHandler(JobNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(e: JobNotFoundException): Map<String, String?> =
        mapOf("error" to e.message)
}
