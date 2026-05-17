package com.noelnp.agenticcrawl.job.api.dto

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noelnp.agenticcrawl.analysis.model.ValidationVerdict
import com.noelnp.agenticcrawl.job.domain.Job
import com.noelnp.agenticcrawl.job.domain.JobStatus
import com.noelnp.agenticcrawl.job.domain.PlanAction
import com.noelnp.agenticcrawl.job.domain.PlanOutcome
import com.noelnp.agenticcrawl.job.domain.PlanStep
import com.noelnp.agenticcrawl.job.domain.ReconLayer
import com.noelnp.agenticcrawl.job.domain.ReconLayerKind
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
    val goalSatisfied: Boolean?,
    val errorMessage: String?,
    val layers: List<ReconLayerDto>,
    val planSteps: List<PlanStepDto>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(job: Job, objectMapper: ObjectMapper) = JobResponse(
            id = job.id ?: error("job id was null"),
            description = job.description,
            url = job.url,
            status = job.status,
            goalSatisfied = job.goalSatisfied,
            errorMessage = job.errorMessage,
            layers = job.layers.map { ReconLayerDto.from(it, objectMapper) },
            planSteps = job.planSteps.map { PlanStepDto.from(it, objectMapper) },
            createdAt = job.createdAt,
            updatedAt = job.updatedAt,
        )
    }
}

data class ReconLayerDto(
    val layerIndex: Int,
    val atUrl: String,
    val layerKind: ReconLayerKind,
    val validation: ValidationDto?,
    val target: JsonNode?,
    val containerHtml: String?,
    val extractedStructure: JsonNode?,
    val hasScreenshot: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun from(layer: ReconLayer, objectMapper: ObjectMapper) = ReconLayerDto(
            layerIndex = layer.layerIndex,
            atUrl = layer.atUrl,
            layerKind = layer.layerKind,
            validation = layer.validationVerdict?.let { v ->
                ValidationDto(v, layer.validationReasoning ?: "")
            },
            target = layer.targetJson?.takeIf { it.isNotBlank() }
                ?.let { objectMapper.readTree(it) },
            containerHtml = layer.containerHtml,
            extractedStructure = layer.extractedStructureJson
                ?.takeIf { it.isNotBlank() }
                ?.let { objectMapper.readTree(it) },
            hasScreenshot = layer.screenshot != null,
            createdAt = layer.createdAt,
        )
    }
}

data class PlanStepDto(
    val stepIndex: Int,
    val action: PlanAction,
    val reasoning: String,
    val outcome: PlanOutcome,
    val detailMessage: String?,
    val actionData: JsonNode?,
    val createdAt: Instant,
) {
    companion object {
        fun from(step: PlanStep, objectMapper: ObjectMapper) = PlanStepDto(
            stepIndex = step.stepIndex,
            action = step.action,
            reasoning = step.reasoning,
            outcome = step.outcome,
            detailMessage = step.detailMessage,
            actionData = step.actionDataJson?.takeIf { it.isNotBlank() }
                ?.let { objectMapper.readTree(it) },
            createdAt = step.createdAt,
        )
    }
}

data class ValidationDto(
    val verdict: ValidationVerdict,
    val reasoning: String,
)
