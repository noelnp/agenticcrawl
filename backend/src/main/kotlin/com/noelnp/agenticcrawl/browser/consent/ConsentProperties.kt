package com.noelnp.agenticcrawl.browser.consent

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "agenticcrawl.consent")
data class ConsentProperties(
    val enabled: Boolean = true,
    val maxPasses: Int = 3,
    val pauseBetweenPassesMs: Long = 500,
    val clickTimeoutMs: Long = 2_000,
    val frameWaitMs: Long = 2_000,
    val rejectPatterns: List<String> = emptyList(),
    val acceptPatterns: List<String> = emptyList(),
    val rejectModifiers: List<String> = emptyList(),
)
