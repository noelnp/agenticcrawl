package com.noelnp.agenticcrawl.analysis.model

import com.noelnp.agenticcrawl.job.domain.PlanAction

data class PlanDecision(
    val action: PlanAction,
    val reasoning: String,
)
