package com.noelnp.agenticcrawl.job.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "plan_steps")
class PlanStep(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    var job: Job,

    @Column(name = "step_index", nullable = false)
    val stepIndex: Int,

    @Column(name = "action", nullable = false, length = 64)
    val action: PlanAction,

    @Column(name = "reasoning", nullable = false, columnDefinition = "TEXT")
    val reasoning: String,
) {
    @Id
    @GeneratedValue
    @UuidGenerator
    var id: UUID? = null

    @Column(name = "outcome", nullable = false, length = 16)
    var outcome: PlanOutcome = PlanOutcome.SUCCESS

    @Column(name = "detail_message", columnDefinition = "TEXT")
    var detailMessage: String? = null

    @Column(name = "action_data_json", columnDefinition = "TEXT")
    var actionDataJson: String? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlanStep) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()
}
