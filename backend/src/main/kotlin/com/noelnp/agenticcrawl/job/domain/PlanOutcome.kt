package com.noelnp.agenticcrawl.job.domain

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

enum class PlanOutcome {
    SUCCESS,
    FAILED,
    SKIPPED,
}

@Converter(autoApply = true)
class PlanOutcomeConverter : AttributeConverter<PlanOutcome, String> {
    override fun convertToDatabaseColumn(attribute: PlanOutcome?): String? = attribute?.name
    override fun convertToEntityAttribute(dbData: String?): PlanOutcome? =
        dbData?.let(PlanOutcome::valueOf)
}
