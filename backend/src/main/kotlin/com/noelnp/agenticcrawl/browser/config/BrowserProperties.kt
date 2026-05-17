package com.noelnp.agenticcrawl.browser.config

import com.microsoft.playwright.options.WaitUntilState
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "agenticcrawl.browser")
data class BrowserProperties(
    val headless: Boolean = true,
    val channel: String? = null,
    val navigationTimeoutMs: Double = 60_000.0,
    val waitUntil: WaitUntilState = WaitUntilState.DOMCONTENTLOADED,
    val postLoadSettleMs: Double = 1_500.0,
    val postDismissSettleMs: Double = 300.0,
    val userAgent: String =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    val locale: String = "sv-SE",
    val timezoneId: String = "Europe/Stockholm",
    val acceptLanguage: String = "sv-SE,sv;q=0.9,en-US;q=0.8,en;q=0.7",
)
