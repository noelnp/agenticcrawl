package com.noelnp.agenticcrawl.job.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
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

    @Column(nullable = false, length = 32)
    var status: JobStatus = JobStatus.PENDING

    @Column(name = "goal_satisfied")
    var goalSatisfied: Boolean? = null

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null

    @OneToMany(
        mappedBy = "job",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY,
    )
    @OrderBy("layerIndex ASC")
    val layers: MutableList<ReconLayer> = mutableListOf()

    @OneToMany(
        mappedBy = "job",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY,
    )
    @OrderBy("stepIndex ASC")
    val planSteps: MutableList<PlanStep> = mutableListOf()

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
