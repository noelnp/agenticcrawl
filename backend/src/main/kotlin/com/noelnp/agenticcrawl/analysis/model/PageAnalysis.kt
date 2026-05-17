package com.noelnp.agenticcrawl.analysis.model

data class PageAnalysis(
    val verdict: ValidationVerdict,
    val reasoning: String,
    val target: Target?,
)
