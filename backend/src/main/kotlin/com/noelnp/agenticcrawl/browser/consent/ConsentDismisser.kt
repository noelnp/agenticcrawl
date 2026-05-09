package com.noelnp.agenticcrawl.browser.consent

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ConsentDismisser(private val properties: ConsentProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun dismiss(page: Page): List<DismissalRecord> {
        if (!properties.enabled) {
            log.debug("consent dismissal disabled by config")
            return emptyList()
        }

        log.debug(
            "starting consent dismissal maxPasses={} pause={}ms reject={} accept={}",
            properties.maxPasses,
            properties.pauseBetweenPassesMs,
            properties.rejectPatterns.size,
            properties.acceptPatterns.size,
        )

        val dismissed = mutableListOf<DismissalRecord>()
        var consecutiveMisses = 0

        for (passIdx in 0 until properties.maxPasses) {
            log.debug("consent pass {}/{}", passIdx + 1, properties.maxPasses)
            val record = tryOnce(page)
            if (record != null) {
                dismissed += record
                consecutiveMisses = 0
            } else {
                consecutiveMisses++
                if (consecutiveMisses >= 2) {
                    log.debug("consent: bailing after {} consecutive misses", consecutiveMisses)
                    break
                }
            }
            if (passIdx < properties.maxPasses - 1) {
                page.waitForTimeout(properties.pauseBetweenPassesMs.toDouble())
            }
        }

        if (dismissed.isEmpty()) {
            log.info("no consent overlay matched (banner may use unknown wording or closed shadow DOM)")
        } else {
            log.info("dismissed {} consent overlay(s): {}", dismissed.size, dismissed)
        }
        return dismissed
    }

    private fun tryOnce(page: Page): DismissalRecord? =
        tryPatterns(page, ConsentIntent.REJECT, properties.rejectPatterns)
            ?: tryPatterns(page, ConsentIntent.ACCEPT, properties.acceptPatterns)

    private fun tryPatterns(
        page: Page,
        intent: ConsentIntent,
        patterns: List<String>,
    ): DismissalRecord? {
        for (pattern in patterns) {
            val match = findClickable(page, pattern) ?: continue
            val (locator, kind) = match
            log.debug("consent: {} match pattern='{}' kind={}", intent, pattern, kind)
            val clicked = runCatching {
                locator.click(Locator.ClickOptions().setTimeout(properties.clickTimeoutMs.toDouble()))
            }
            if (clicked.isSuccess) {
                log.info("consent: clicked intent={} pattern='{}' kind={}", intent, pattern, kind)
                return DismissalRecord(intent = intent, pattern = pattern, role = kind)
            }
            log.debug(
                "consent: matched '{}' as {} but click failed: {}",
                pattern,
                kind,
                clicked.exceptionOrNull()?.message,
            )
        }
        return null
    }

    private fun findClickable(page: Page, pattern: String): Pair<Locator, String>? {
        val escaped = pattern.replace("\\", "\\\\").replace("'", "\\'")
        for ((selector, kind) in CLICKABLE_VARIANTS) {
            val locator = page.locator("$selector:has-text('$escaped'):visible")
            if (locator.count() > 0) {
                return locator.first() to kind
            }
        }
        return null
    }

    private companion object {
        val CLICKABLE_VARIANTS = listOf(
            "button" to "button",
            "[role='button']" to "button",
            "a" to "link",
        )
    }
}
