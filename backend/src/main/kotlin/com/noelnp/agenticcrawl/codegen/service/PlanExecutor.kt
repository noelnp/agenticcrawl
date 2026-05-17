package com.noelnp.agenticcrawl.codegen.service

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ViewportSize
import com.noelnp.agenticcrawl.analysis.model.FieldSelector
import com.noelnp.agenticcrawl.analysis.model.ValueSource
import com.noelnp.agenticcrawl.browser.config.BrowserProperties
import com.noelnp.agenticcrawl.browser.consent.ConsentDismisser
import com.noelnp.agenticcrawl.codegen.model.ExtractionPlan
import com.noelnp.agenticcrawl.codegen.model.ExtractionStep
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * In-process executor for an [ExtractionPlan]. Walks the plan tree and drives
 * Playwright directly, returning the collected JSON-friendly result.
 * Functionally mirrors the `.main.kts` emitted by [KotlinScriptRenderer]; that
 * script is the portable downloadable artefact, this class is the runtime path
 * the backend uses.
 *
 * Each [execute] call spins up a fresh Playwright/browser/context pinned to a
 * single-threaded executor (Playwright Java is thread-affine) and tears it
 * down on return. [execute] is synchronous and can take many minutes; callers
 * must schedule it off the request thread.
 *
 * Per-row failures inside [ExtractionStep.ForEachRow] are logged and skipped —
 * a single broken detail page does not abort the run.
 */
@Service
class PlanExecutor(
    private val consentDismisser: ConsentDismisser,
    private val properties: BrowserProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun execute(plan: ExtractionPlan): Map<String, Any?> {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "plan-executor").apply { isDaemon = true }
        }
        try {
            return executor.submit<Map<String, Any?>> { runOnSessionThread(plan) }.get()
        } finally {
            executor.shutdown()
            if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        }
    }

    private fun runOnSessionThread(plan: ExtractionPlan): Map<String, Any?> {
        log.info(
            "executing plan targetUrl={} userRequest='{}' steps={}",
            plan.targetUrl, plan.userRequest.take(80), plan.steps.size,
        )
        val result = mutableMapOf<String, Any?>()
        val state = ExecutionState()
        Playwright.create().use { pw ->
            val browser = pw.chromium().launch(
                BrowserType.LaunchOptions()
                    .setHeadless(properties.headless)
                    .setArgs(
                        listOf(
                            "--disable-blink-features=AutomationControlled",
                            "--disable-features=IsolateOrigins,site-per-process",
                        ),
                    ).also { opts ->
                        properties.channel?.takeIf { it.isNotBlank() }?.let(opts::setChannel)
                    },
            )
            val context = browser.newContext(
                Browser.NewContextOptions()
                    .setViewportSize(ViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT))
                    .setDeviceScaleFactor(1.0)
                    .setUserAgent(properties.userAgent)
                    .setLocale(properties.locale)
                    .setTimezoneId(properties.timezoneId)
                    .setExtraHTTPHeaders(mapOf("Accept-Language" to properties.acceptLanguage)),
            )
            try {
                val page = context.newPage()
                for (step in plan.steps) {
                    runTopLevel(step, page, context, result, state)
                }
            } finally {
                runCatching { context.close() }
                runCatching { browser.close() }
            }
        }
        return result
    }

    private fun runTopLevel(
        step: ExtractionStep,
        page: Page,
        context: BrowserContext,
        result: MutableMap<String, Any?>,
        state: ExecutionState,
    ) {
        log.debug("step: {} {}", step::class.simpleName, step.description.take(120))
        when (step) {
            is ExtractionStep.Navigate -> {
                page.navigate(
                    step.url,
                    Page.NavigateOptions()
                        .setWaitUntil(properties.waitUntil)
                        .setTimeout(properties.navigationTimeoutMs),
                )
                page.waitForTimeout(properties.postLoadSettleMs)
            }
            is ExtractionStep.DismissConsent -> {
                dismissConsentOnce(page, state)
            }
            is ExtractionStep.WaitForSelector -> {
                page.waitForSelector(
                    step.selector,
                    Page.WaitForSelectorOptions().setTimeout(step.timeoutMs.toDouble()),
                )
            }
            is ExtractionStep.ExtractRows -> {
                result[step.attachAs] = collectRowsAtTopLevel(page, step)
            }
            is ExtractionStep.ForEachRow -> {
                result[step.attachAs] = collectForEachRow(page, context, step, state)
            }
            is ExtractionStep.ResolveAndNavigate,
            is ExtractionStep.Click -> {
                throw IllegalStateException(
                    "Step ${step::class.simpleName} is not supported at the top level of an ExtractionPlan",
                )
            }
        }
    }

    private fun dismissConsentOnce(page: Page, state: ExecutionState) {
        if (state.consentHandled) {
            log.debug("consent already handled in this context — skipping")
            return
        }
        val dismissed = consentDismisser.dismiss(page)
        if (dismissed.isNotEmpty()) page.waitForTimeout(properties.postDismissSettleMs)
        state.consentHandled = true
    }

    private class ExecutionState {
        var consentHandled: Boolean = false
    }

    private fun collectRowsAtTopLevel(page: Page, step: ExtractionStep.ExtractRows): List<Map<String, Any?>> {
        val rows = page.locator(step.rowSelector)
        val total = rows.count()
        val capped = capCount(total, step.limit, step.attachAs)
        log.info("extracting {} of {} row(s) for '{}'", capped, total, step.attachAs)
        val collected = mutableListOf<Map<String, Any?>>()
        for (i in 0 until capped) {
            try {
                val row = rows.nth(i)
                collected += extractRecord(row, step.fields)
            } catch (e: Exception) {
                log.warn("row {}/{} failed: {}", i + 1, capped, e.message)
            }
        }
        return collected
    }

    private fun collectForEachRow(
        page: Page,
        context: BrowserContext,
        step: ExtractionStep.ForEachRow,
        state: ExecutionState,
    ): List<Map<String, Any?>> {
        val rows = page.locator(step.rowSelector)
        val total = rows.count()
        val capped = capCount(total, step.limit, step.attachAs)
        log.info("for-each-row '{}': iterating {} of {} row(s)", step.attachAs, capped, total)
        val collected = mutableListOf<Map<String, Any?>>()
        val listingBaseUrl = page.url()
        val hasNavigation = step.perRowSteps.any { it is ExtractionStep.ResolveAndNavigate }

        for (i in 0 until capped) {
            try {
                val row = rows.nth(i)
                val record = extractRecord(row, step.fields)

                if (hasNavigation) {
                    val detailPage = context.newPage()
                    try {
                        runPerRowStepsOnDetailPage(
                            steps = step.perRowSteps,
                            row = row,
                            detailPage = detailPage,
                            listingBaseUrl = listingBaseUrl,
                            record = record,
                            state = state,
                        )
                    } finally {
                        runCatching { detailPage.close() }
                    }
                } else {
                    runPerRowStepsInRowScope(step.perRowSteps, row, record)
                }

                collected += record
                log.info("'{}' {}/{} extracted", step.attachAs, i + 1, capped)
            } catch (e: Exception) {
                log.warn("'{}' {}/{} failed: {}", step.attachAs, i + 1, capped, e.message)
            }
            if (hasNavigation && i < capped - 1) pauseBetweenRequests()
        }
        return collected
    }

    private fun capCount(total: Int, limit: Int?, attachAs: String): Int {
        if (limit == null || limit >= total) return total
        log.info("'{}' limit={} applied (DOM had {} row(s))", attachAs, limit, total)
        return limit
    }

    private fun runPerRowStepsOnDetailPage(
        steps: List<ExtractionStep>,
        row: Locator,
        detailPage: Page,
        listingBaseUrl: String,
        record: MutableMap<String, Any?>,
        state: ExecutionState,
    ) {
        for (step in steps) {
            when (step) {
                is ExtractionStep.ResolveAndNavigate -> {
                    val linkLocator = row.locator(step.detailLinkSelector).let {
                        if (step.nth != null) it.nth(step.nth) else it.first()
                    }
                    val href = linkLocator.getAttribute("href")
                    require(!href.isNullOrBlank()) {
                        "detail link '${step.detailLinkSelector}' returned an empty href"
                    }
                    val resolved = URI.create(listingBaseUrl).resolve(href).toString()
                    detailPage.navigate(
                        resolved,
                        Page.NavigateOptions()
                            .setWaitUntil(properties.waitUntil)
                            .setTimeout(properties.navigationTimeoutMs),
                    )
                    detailPage.waitForTimeout(properties.postLoadSettleMs)
                }
                is ExtractionStep.DismissConsent -> {
                    dismissConsentOnce(detailPage, state)
                }
                is ExtractionStep.WaitForSelector -> {
                    detailPage.waitForSelector(
                        step.selector,
                        Page.WaitForSelectorOptions().setTimeout(step.timeoutMs.toDouble()),
                    )
                }
                is ExtractionStep.Click -> {
                    executeClick(detailPage.locator(step.selector), step)
                    detailPage.waitForTimeout(POST_CLICK_SETTLE_MS.toDouble())
                }
                is ExtractionStep.ExtractRows -> {
                    val nested = collectRowsAtTopLevel(detailPage, step)
                    record[step.attachAs] = nested
                }
                is ExtractionStep.Navigate ->
                    throw IllegalStateException("absolute Navigate inside ForEachRow is not supported")
                is ExtractionStep.ForEachRow ->
                    throw IllegalStateException("nested ForEachRow is not supported")
            }
        }
    }

    private fun runPerRowStepsInRowScope(
        steps: List<ExtractionStep>,
        row: Locator,
        record: MutableMap<String, Any?>,
    ) {
        for (step in steps) {
            when (step) {
                is ExtractionStep.Click -> {
                    executeClick(row.locator(step.selector), step)
                }
                is ExtractionStep.WaitForSelector -> {
                    row.locator(step.selector).first().waitFor(
                        Locator.WaitForOptions().setTimeout(step.timeoutMs.toDouble()),
                    )
                }
                is ExtractionStep.ExtractRows -> {
                    val nested = mutableListOf<Map<String, Any?>>()
                    val inner = row.locator(step.rowSelector)
                    val cnt = inner.count()
                    for (j in 0 until cnt) {
                        try {
                            nested += extractRecord(inner.nth(j), step.fields)
                        } catch (e: Exception) {
                            log.warn("inner '{}' {}/{} failed: {}", step.attachAs, j + 1, cnt, e.message)
                        }
                    }
                    record[step.attachAs] = nested
                }
                else -> throw IllegalStateException(
                    "Step ${step::class.simpleName} is not supported inside row-only per-row scope",
                )
            }
        }
    }

    private fun executeClick(baseLocator: Locator, step: ExtractionStep.Click) {
        val filtered = if (step.text != null) {
            baseLocator.filter(Locator.FilterOptions().setHasText(step.text))
        } else {
            baseLocator
        }
        val final = if (step.nth != null) filtered.nth(step.nth) else filtered.first()
        final.click(Locator.ClickOptions().setTimeout(CLICK_TIMEOUT_MS.toDouble()))
    }

    private fun extractRecord(scope: Locator, fields: List<FieldSelector>): MutableMap<String, Any?> {
        val record = mutableMapOf<String, Any?>()
        for (field in fields) {
            record[field.name] = extractField(scope, field)
        }
        return record
    }

    private fun extractField(scope: Locator, field: FieldSelector): String? {
        val base = scope.locator(field.selector)
        val target = if (field.nth != null) base.nth(field.nth) else base.first()
        return try {
            if (target.count() == 0) return null
            when (val src = field.source) {
                ValueSource.Text -> target.innerText().trim().ifEmpty { null }
                is ValueSource.Attribute -> target.getAttribute(src.name)
            }
        } catch (e: Exception) {
            log.debug("extractField('{}') failed: {}", field.selector, e.message)
            null
        }
    }

    private fun pauseBetweenRequests() {
        val ms = ThreadLocalRandom.current().nextLong(REQUEST_PAUSE_MIN_MS, REQUEST_PAUSE_MAX_MS)
        Thread.sleep(ms)
    }

    private companion object {
        const val VIEWPORT_WIDTH = 1440
        const val VIEWPORT_HEIGHT = 1600
        const val CLOSE_TIMEOUT_SECONDS = 15L
        const val REQUEST_PAUSE_MIN_MS = 500L
        const val REQUEST_PAUSE_MAX_MS = 1500L
        const val CLICK_TIMEOUT_MS = 10_000
        const val POST_CLICK_SETTLE_MS = 800
    }
}
