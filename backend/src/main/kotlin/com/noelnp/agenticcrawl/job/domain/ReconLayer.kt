package com.noelnp.agenticcrawl.job.domain

import com.noelnp.agenticcrawl.analysis.model.ValidationVerdict
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Lob
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "recon_layers")
class ReconLayer(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    var job: Job,

    @Column(name = "layer_index", nullable = false)
    val layerIndex: Int,

    @Column(name = "layer_kind", nullable = false, length = 32)
    val layerKind: ReconLayerKind,

    @Column(name = "at_url", nullable = false, length = 2048)
    val atUrl: String,
) {
    @Id
    @GeneratedValue
    @UuidGenerator
    var id: UUID? = null

    @Lob
    @Column(name = "screenshot")
    var screenshot: ByteArray? = null

    @Column(name = "validation_verdict", length = 16)
    var validationVerdict: ValidationVerdict? = null

    @Column(name = "validation_reasoning", columnDefinition = "TEXT")
    var validationReasoning: String? = null

    @Column(name = "target_json", columnDefinition = "TEXT")
    var targetJson: String? = null

    @Lob
    @Column(name = "container_html")
    var containerHtml: String? = null

    @Column(name = "extracted_structure_json", columnDefinition = "TEXT")
    var extractedStructureJson: String? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReconLayer) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()
}
