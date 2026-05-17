package com.noelnp.agenticcrawl.analysis.model

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

enum class ValidationVerdict {
    PRESENT,
    PARTIAL,
    ABSENT,
}

@Converter(autoApply = true)
class ValidationVerdictConverter : AttributeConverter<ValidationVerdict, String> {
    override fun convertToDatabaseColumn(attribute: ValidationVerdict?): String? = attribute?.name
    override fun convertToEntityAttribute(dbData: String?): ValidationVerdict? =
        dbData?.let(ValidationVerdict::valueOf)
}
