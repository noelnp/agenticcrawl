package com.noelnp.agenticcrawl.analysis

import com.noelnp.agenticcrawl.job.PlanAction

data class PlanDecision(
    val action: PlanAction,
    val reasoning: String,
)
