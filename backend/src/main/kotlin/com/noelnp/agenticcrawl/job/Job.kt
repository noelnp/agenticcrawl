package com.noelnp.agenticcrawl.job

import com.noelnp.agenticcrawl.analysis.ValidationVerdict
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
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
    @Column(nullable = false, length = 32)
    var status: JobStatus = JobStatus.PENDING

    @Lob
    @Column(name = "screenshot")
    var screenshot: ByteArray? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_verdict", length = 16)
    var validationVerdict: ValidationVerdict? = null

    @Column(name = "validation_reasoning", columnDefinition = "TEXT")
    var validationReasoning: String? = null

    @Column(name = "example_container_type", columnDefinition = "TEXT")
    var exampleContainerType: String? = null

    @Column(name = "example_fields_json", columnDefinition = "TEXT")
    var exampleFieldsJson: String? = null

    @Lob
    @Column(name = "container_html")
    var containerHtml: String? = null

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Job) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()
}
