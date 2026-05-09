package com.noelnp.agenticcrawl.job

import com.noelnp.agenticcrawl.validation.ValidationVerdict
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "jobs")
class Job(
    @Column(nullable = false, columnDefinition = "TEXT")
    val description: String,

    @Column(nullable = false, length = 2048)
    val url: String,
) {
    @Id
    @GeneratedValue
    @UuidGenerator
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: JobStatus = JobStatus.PENDING

    @Lob
    @Column(name = "screenshot")
    var screenshot: ByteArray? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_verdict", length = 16)
    var validationVerdict: ValidationVerdict? = null

    @Column(name = "validation_reasoning", columnDefinition = "TEXT")
    var validationReasoning: String? = null

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
