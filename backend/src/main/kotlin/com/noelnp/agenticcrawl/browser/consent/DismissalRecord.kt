package com.noelnp.agenticcrawl.browser.consent

data class DismissalRecord(
    val intent: ConsentIntent,
    val pattern: String,
    val role: String,
)
