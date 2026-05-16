package com.noelnp.agenticcrawl.job

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

enum class ReconLayerKind {
    LISTING,
    FOLLOWED_DETAIL_LINK,
    REVEALED_BY_CLICK,
}

@Converter(autoApply = true)
class ReconLayerKindConverter : AttributeConverter<ReconLayerKind, String> {
    override fun convertToDatabaseColumn(attribute: ReconLayerKind?): String? = attribute?.name
    override fun convertToEntityAttribute(dbData: String?): ReconLayerKind? =
        dbData?.let(ReconLayerKind::valueOf)
}
