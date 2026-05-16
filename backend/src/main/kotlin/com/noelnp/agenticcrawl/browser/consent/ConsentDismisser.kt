package com.noelnp.agenticcrawl.browser.consent

import com.microsoft.playwright.Frame
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
            "starting consent dismissal maxPasses={} pause={}ms reject={} accept={} modifiers={} frameWaitMs={}",
            properties.maxPasses,
            properties.pauseBetweenPassesMs,
            properties.rejectPatterns.size,
            properties.acceptPatterns.size,
            properties.rejectModifiers.size,
            properties.frameWaitMs,
        )

        waitForCandidateFrame(page)

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

    private fun waitForCandidateFrame(page: Page) {
        val budget = properties.frameWaitMs
        if (budget <= 0) return
        val deadline = System.currentTimeMillis() + budget
        while (System.currentTimeMillis() < deadline) {
            if (page.frames().any { frameHasMatch(it) }) {
                log.debug("consent: candidate frame ready")
                return
            }
            page.waitForTimeout(200.0)
        }
        log.debug("consent: no candidate frame within {}ms, proceeding anyway", budget)
    }

    private fun frameHasMatch(frame: Frame): Boolean {
        val texts = readCandidateTexts(frame)
        return texts.any { it.isNotBlank() && classify(it) != null }
    }

    private fun tryOnce(page: Page): DismissalRecord? {
        for (frame in page.frames()) {
            val record = tryFrame(frame) ?: continue
            return record
        }
        return null
    }

    private fun tryFrame(frame: Frame): DismissalRecord? {
        val texts = readCandidateTexts(frame)
        if (texts.isEmpty()) return null

        var acceptPick: Pick? = null
        for ((index, text) in texts.withIndex()) {
            if (text.isBlank()) continue
            val classification = classify(text) ?: continue
            when (classification.intent) {
                ConsentIntent.REJECT -> {
                    val record = click(frame, index, classification)
                    if (record != null) return record
                }
                ConsentIntent.ACCEPT -> if (acceptPick == null) acceptPick = Pick(index, classification)
            }
        }
        return acceptPick?.let { click(frame, it.index, it.classification) }
    }

    private fun readCandidateTexts(frame: Frame): List<String> {
        val raw = runCatching { frame.locator(CANDIDATE_SELECTOR).evaluateAll(READ_TEXT_JS) }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        return (raw as? List<String>) ?: emptyList()
    }

    private fun classify(text: String): Classification? {
        for (pattern in properties.rejectPatterns) {
            if (pattern in text) return Classification(ConsentIntent.REJECT, pattern)
        }
        for (pattern in properties.acceptPatterns) {
            if (pattern in text) {
                val modifier = properties.rejectModifiers.firstOrNull { it in text }
                return if (modifier != null) {
                    Classification(ConsentIntent.REJECT, "$pattern+$modifier")
                } else {
                    Classification(ConsentIntent.ACCEPT, pattern)
                }
            }
        }
        return null
    }

    private fun click(frame: Frame, index: Int, classification: Classification): DismissalRecord? {
        val locator = frame.locator(CANDIDATE_SELECTOR).nth(index)
        val role = runCatching { locator.evaluate("e => e.tagName.toLowerCase()") as? String }.getOrNull() ?: "?"
        val frameUrl = runCatching { frame.url() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "(main)"
        log.debug(
            "consent: {} match pattern='{}' role={} frame={}",
            classification.intent, classification.pattern, role, frameUrl,
        )
        val result = runCatching {
            locator.click(Locator.ClickOptions().setTimeout(properties.clickTimeoutMs.toDouble()))
        }
        if (result.isSuccess) {
            log.info(
                "consent: clicked intent={} pattern='{}' role={} frame={}",
                classification.intent, classification.pattern, role, frameUrl,
            )
            return DismissalRecord(intent = classification.intent, pattern = classification.pattern, role = role)
        }
        log.debug(
            "consent: matched '{}' as {} but click failed: {}",
            classification.pattern,
            role,
            result.exceptionOrNull()?.message,
        )
        return null
    }

    private data class Classification(val intent: ConsentIntent, val pattern: String)
    private data class Pick(val index: Int, val classification: Classification)

    private companion object {
        const val CANDIDATE_SELECTOR = "button:visible, [role='button']:visible, a:visible"
        const val READ_TEXT_JS =
            "els => els.map(e => (e.innerText || e.getAttribute('aria-label') || '').toLowerCase().trim())"
    }
}
