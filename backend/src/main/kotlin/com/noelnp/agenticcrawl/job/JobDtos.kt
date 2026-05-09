package com.noelnp.agenticcrawl.job

import com.fasterxml.jackson.core.type.TypeReference
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
    val example: ExampleDto?,
    val errorMessage: String?,
    val hasScreenshot: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        private val FIELDS_TYPE = object : TypeReference<LinkedHashMap<String, String>>() {}

        fun from(job: Job, objectMapper: ObjectMapper) = JobResponse(
            id = job.id ?: error("job id was null"),
            description = job.description,
            url = job.url,
            status = job.status,
            validation = job.validationVerdict?.let { verdict ->
                ValidationDto(verdict, job.validationReasoning ?: "")
            },
            example = job.exampleContainerType?.let { containerType ->
                ExampleDto(
                    containerType = containerType,
                    fields = parseFields(job.exampleFieldsJson, objectMapper),
                )
            },
            errorMessage = job.errorMessage,
            hasScreenshot = job.screenshot != null,
            createdAt = job.createdAt,
            updatedAt = job.updatedAt,
        )

        private fun parseFields(json: String?, objectMapper: ObjectMapper): Map<String, String> =
            if (json.isNullOrBlank()) emptyMap()
            else objectMapper.readValue(json, FIELDS_TYPE)
    }
}

data class ValidationDto(
    val verdict: ValidationVerdict,
    val reasoning: String,
)

data class ExampleDto(
    val containerType: String,
    val fields: Map<String, String>,
)
