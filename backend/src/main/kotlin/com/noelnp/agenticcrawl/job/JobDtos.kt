package com.noelnp.agenticcrawl.job

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noelnp.agenticcrawl.analysis.ValidationVerdict
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.hibernate.validator.constraints.URL
import java.time.Instant
import java.util.UUID

data class CreateJobRequest(
    @field:NotBlank
    @field:Size(max = 4000)
    val description: String,

    @field:NotBlank
    @field:URL
    @field:Size(max = 2048)
    val url: String,
)

data class JobResponse(
    val id: UUID,
    val description: String,
    val url: String,
    val status: JobStatus,
    val validation: ValidationDto?,
    val target: JsonNode?,
    val containerHtml: String?,
    val errorMessage: String?,
    val hasScreenshot: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(job: Job, objectMapper: ObjectMapper) = JobResponse(
            id = job.id ?: error("job id was null"),
            description = job.description,
            url = job.url,
            status = job.status,
            validation = job.validationVerdict?.let { verdict ->
                ValidationDto(verdict, job.validationReasoning ?: "")
            },
            target = job.targetJson?.takeIf { it.isNotBlank() }?.let { objectMapper.readTree(it) },
            containerHtml = job.containerHtml,
            errorMessage = job.errorMessage,
            hasScreenshot = job.screenshot != null,
            createdAt = job.createdAt,
            updatedAt = job.updatedAt,
        )
    }
}

data class ValidationDto(
    val verdict: ValidationVerdict,
    val reasoning: String,
)
