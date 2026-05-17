package com.noelnp.agenticcrawl.job.domain

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

enum class PlanAction {
    NAVIGATE_VIA_DETAIL_LINK,
    CLICK_TO_REVEAL,
    FINISH,
}

@Converter(autoApply = true)
class PlanActionConverter : AttributeConverter<PlanAction, String> {
    override fun convertToDatabaseColumn(attribute: PlanAction?): String? = attribute?.name
    override fun convertToEntityAttribute(dbData: String?): PlanAction? =
        dbData?.let(PlanAction::valueOf)
}
