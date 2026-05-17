package com.noelnp.agenticcrawl.browser.session

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.noelnp.agenticcrawl.analysis.service.ClickTarget
import com.noelnp.agenticcrawl.analysis.model.DetailLinkSelector
import com.noelnp.agenticcrawl.browser.config.BrowserProperties
import com.noelnp.agenticcrawl.browser.consent.ConsentDismisser
import com.noelnp.agenticcrawl.browser.model.ClickResult
import com.noelnp.agenticcrawl.browser.model.PageCapture
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

    fun collectClickableCandidates(): String = onSessionThread {
        runCatching { page.evaluate(CLICKABLE_CANDIDATES_JS) as? String }
            .onFailure { log.warn("collectClickableCandidates failed: {}", it.message) }
            .getOrNull()
            .orEmpty()
    }

    fun clickElement(target: ClickTarget, settleMs: Double = properties.postLoadSettleMs): ClickResult = onSessionThread {
        val before = page.url()
        val baseLocator = page.locator(target.selector)
        val filtered = if (target.text != null) {
            baseLocator.filter(Locator.FilterOptions().setHasText(target.text))
        } else {
            baseLocator
        }
        val final = if (target.nth != null) filtered.nth(target.nth) else filtered.first()
        val clicked = try {
            final.click(Locator.ClickOptions().setTimeout(5_000.0))
            true
        } catch (e: Exception) {
            log.warn(
                "clickElement(selector='{}' text={} nth={}) failed: {}",
                target.selector, target.text, target.nth, e.message,
            )
            false
        }
        if (clicked) page.waitForTimeout(settleMs)
        val after = page.url()
        ClickResult(
            previousUrl = before,
            currentUrl = after,
            urlChanged = before != after,
            clicked = clicked,
        )
    }

    fun resolveDetailLinkHref(detailLink: DetailLinkSelector): String? = onSessionThread {
        val locator = page.locator(detailLink.selector)
        val target = when (val nth = detailLink.nth) {
            null -> locator.first()
            else -> locator.nth(nth)
        }
        runCatching { target.getAttribute("href") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

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

    /**
     * Fetch the outerHTML of up to [count] elements matching [rowSelector],
     * skipping the first [skipFirst] (typically the example row recon already
     * analysed). Used for cross-row selector verification — checking that a
     * structure derived from one row also works on its peers.
     */
    fun sampleRowHtmls(rowSelector: String, count: Int, skipFirst: Int = 1): List<String> = onSessionThread {
        val locator = page.locator(rowSelector)
        val total = locator.count()
        if (total <= skipFirst) return@onSessionThread emptyList()
        val available = total - skipFirst
        val toTake = minOf(count, available)
        val collected = mutableListOf<String>()
        for (i in 0 until toTake) {
            val html = runCatching {
                locator.nth(skipFirst + i).evaluate("el => el.outerHTML") as? String
            }.getOrNull()
            if (!html.isNullOrBlank()) collected += html
        }
        collected
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

        const val CLICKABLE_CANDIDATES_JS = """
            () => {
                const sel = 'button, a[href], [role="button"], [role="tab"], ' +
                            '[role="link"], [role="menuitem"], [role="menuitemcheckbox"], ' +
                            'input[type="button"], input[type="submit"], summary';
                const visible = Array.from(document.querySelectorAll(sel)).filter(el => {
                    const r = el.getBoundingClientRect();
                    if (r.width < 5 || r.height < 5) return false;
                    const style = window.getComputedStyle(el);
                    if (style.visibility === 'hidden' || style.display === 'none') return false;
                    if (parseFloat(style.opacity) < 0.1) return false;
                    return true;
                });
                return visible.slice(0, 150).map((el, idx) => {
                    const tag = el.tagName.toLowerCase();
                    const role = el.getAttribute('role') || '';
                    const testid = el.getAttribute('data-testid') || '';
                    const aria = el.getAttribute('aria-label') || '';
                    const title = el.getAttribute('title') || '';
                    const cls = Array.from(el.classList).slice(0, 4).join(' ');
                    const text = (el.innerText || '').trim().replace(/\s+/g, ' ').substring(0, 60);
                    const parts = ['tag=' + tag];
                    if (role) parts.push('role=' + role);
                    if (testid) parts.push('data-testid="' + testid + '"');
                    if (cls) parts.push('class="' + cls + '"');
                    if (aria) parts.push('aria-label="' + aria + '"');
                    if (title) parts.push('title="' + title + '"');
                    if (text) parts.push('text="' + text + '"');
                    return '#' + idx + '  ' + parts.join('  ');
                }).join('\n');
            }
        """

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
