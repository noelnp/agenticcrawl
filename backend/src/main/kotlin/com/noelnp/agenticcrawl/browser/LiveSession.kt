package com.noelnp.agenticcrawl.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.noelnp.agenticcrawl.browser.consent.ConsentDismisser
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class LiveSession internal constructor(
    private val executor: ExecutorService,
    private val playwright: Playwright,
    private val browser: Browser,
    private val context: BrowserContext,
    private val page: Page,
    private val consentDismisser: ConsentDismisser,
    private val properties: BrowserProperties,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var closed = false

    fun currentUrl(): String = onSessionThread { page.url() }

    fun navigateTo(url: String): Boolean = onSessionThread {
        log.info("navigating to {}", url)
        try {
            page.navigate(
                url,
                Page.NavigateOptions()
                    .setWaitUntil(properties.waitUntil)
                    .setTimeout(properties.navigationTimeoutMs),
            )
            page.waitForTimeout(properties.postLoadSettleMs)
            val dismissed = consentDismisser.dismiss(page)
            if (dismissed.isNotEmpty()) {
                page.waitForTimeout(properties.postDismissSettleMs)
            }
            true
        } catch (e: Exception) {
            log.error("navigateTo({}) failed: {}", url, e.message, e)
            false
        }
    }

    fun capture(): PageCapture = onSessionThread {
        val screenshot = page.screenshot(
            Page.ScreenshotOptions()
                .setFullPage(false)
                .setType(com.microsoft.playwright.options.ScreenshotType.PNG),
        )
        val visibleText = page.innerText("body")
        PageCapture(screenshot = screenshot, visibleText = visibleText)
    }

    fun findRowContainerHtml(values: List<String>): String? = onSessionThread {
        val nonBlank = values.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return@onSessionThread null

        val targets = nonBlank.mapNotNull { value ->
            val resolved = resolveTolerantText(value)
            if (resolved == null) {
                log.warn("value has no match on page even with tolerant fallback: '{}'", value)
                null
            } else {
                if (resolved != value) log.debug("tolerant match: '{}' -> '{}'", value, resolved)
                resolved
            }
        }
        if (targets.isEmpty()) {
            log.warn("none of the grounded values matched on the page: {}", nonBlank)
            return@onSessionThread null
        }

        var loc: Locator = page.locator(ROW_CONTAINER_SELECTOR)
        for (value in targets) {
            loc = loc.filter(Locator.FilterOptions().setHas(page.getByText(value)))
        }

        val count = loc.count()
        log.debug("findRowContainerHtml candidates={} values={}", count, targets)
        if (count == 0) return@onSessionThread null

        for (i in count - 1 downTo 0) {
            val cand = loc.nth(i)
            val distinct = runCatching {
                cand.evaluate(DISTINCT_LEAVES_JS, targets) as? Boolean
            }.getOrElse {
                log.debug("distinct-leaves eval failed for candidate {}: {}", i, it.message)
                null
            } ?: false
            if (!distinct) continue
            val html = runCatching { cand.evaluate("el => el.outerHTML") as? String }.getOrNull()
            if (!html.isNullOrBlank()) {
                log.debug("findRowContainerHtml picked candidate {} (distinct leaves)", i)
                return@onSessionThread html
            }
        }

        log.warn(
            "no candidate has distinct text leaves for values={} — falling back to .last() " +
                "(structure inference will likely be degenerate)",
            targets,
        )
        return@onSessionThread runCatching { loc.last().evaluate("el => el.outerHTML") as? String }
            .onFailure { log.warn("findRowContainerHtml fallback failed: {}", it.message) }
            .getOrNull()
    }

    private fun resolveTolerantText(value: String): String? {
        if (page.getByText(value).count() > 0) return value
        val tokens = value.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        if (tokens.size <= 1) return null
        val minTokens = maxOf(2, tokens.size / 2)
        for (n in (tokens.size - 1) downTo minTokens) {
            val prefix = tokens.subList(0, n).joinToString(" ")
            if (prefix.length < MIN_TOLERANT_PREFIX_LENGTH) continue
            if (page.getByText(prefix).count() > 0) return prefix
        }
        return null
    }

    fun findSingleElementHtml(text: String): String? = onSessionThread {
        if (text.isBlank()) return@onSessionThread null
        val target = page.getByText(text).first()
        runCatching { target.evaluate("el => el.outerHTML") as? String }
            .onFailure { log.warn("findSingleElementHtml failed: {}", it.message) }
            .getOrNull()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            executor.submit {
                runCatching { page.close() }
                runCatching { context.close() }
                runCatching { browser.close() }
                runCatching { playwright.close() }
            }.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.onFailure { log.warn("session close did not complete cleanly: {}", it.message) }
        executor.shutdown()
        if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }

    private fun <T> onSessionThread(block: () -> T): T =
        executor.submit<T>(block).get()

    private companion object {
        const val CLOSE_TIMEOUT_SECONDS = 10L
        const val ROW_CONTAINER_SELECTOR = "div, article, li, tr, section"
        const val MIN_TOLERANT_PREFIX_LENGTH = 8
        val WHITESPACE_REGEX = Regex("\\s+")

        const val DISTINCT_LEAVES_JS = """
            (el, values) => {
                const ownText = (e) => {
                    let s = '';
                    for (const n of e.childNodes) if (n.nodeType === 3) s += n.textContent;
                    return s.replace(/\s+/g, ' ').trim();
                };
                const norm = (s) => s.replace(/\s+/g, ' ').trim();
                const findLeaf = (root, text) => {
                    const target = norm(text);
                    if (!target) return null;
                    let best = null, bestDepth = -1;
                    for (const e of root.querySelectorAll('*')) {
                        if (!norm(ownText(e)).includes(target)) continue;
                        let d = 0, cur = e;
                        while (cur && cur !== root) { d++; cur = cur.parentElement; }
                        if (d > bestDepth) { best = e; bestDepth = d; }
                    }
                    return best;
                };
                const leaves = values.map(v => findLeaf(el, v));
                if (leaves.some(l => l === null)) return false;
                return new Set(leaves).size === leaves.length;
            }
        """
    }
}
