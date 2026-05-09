package com.noelnp.agenticcrawl.analysis

data class PageAnalysis(
    val verdict: ValidationVerdict,
    val reasoning: String,
    val example: ExtractionExample?,
)
