package com.noelnp.agenticcrawl.job

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

enum class JobStatus {
    PENDING,
    RUNNING_LISTING_RECON,
    AWAITING_CONFIRMATION,
    RUNNING_PLAN,
    SUCCEEDED,
    FAILED,
    EXPIRED,
}

@Converter(autoApply = true)
class JobStatusConverter : AttributeConverter<JobStatus, String> {
    override fun convertToDatabaseColumn(attribute: JobStatus?): String? = attribute?.name
    override fun convertToEntityAttribute(dbData: String?): JobStatus? =
        dbData?.let(JobStatus::valueOf)
}
