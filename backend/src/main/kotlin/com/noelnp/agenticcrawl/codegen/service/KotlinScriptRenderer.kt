package com.noelnp.agenticcrawl.codegen.service

import com.noelnp.agenticcrawl.analysis.model.FieldSelector
import com.noelnp.agenticcrawl.analysis.model.ValueSource
import com.noelnp.agenticcrawl.codegen.model.ExtractionPlan
import com.noelnp.agenticcrawl.codegen.model.ExtractionStep
import org.springframework.stereotype.Service

/**
 * Deterministic Kotlin codegen for an [ExtractionPlan]. Emits a self-contained
 * `.main.kts` script that pulls its Playwright/Jackson dependencies at runtime
 * via `@file:DependsOn` and writes its result as JSON.
 *
 * Single entry point: [render]. Per-step emission is split across helpers so
 * each [ExtractionStep] variant has one rendering site.
 *
 * The script structure (kept tight so the output is readable):
 *   1. `@file:DependsOn` lines for playwright + jackson
 *   2. Imports
 *   3. Constants, arg parsing, helpers (consent dismiss, polite delay, field
 *      extractor, href resolver)
 *   4. Browser launch + top-level step emission
 *   5. Final JSON write
 *
 * Per-row trajectory inside a [ExtractionStep.ForEachRow] is emitted on a
 * freshly opened detail page when [ExtractionStep.ResolveAndNavigate] appears
 * in the per-row steps, so the listing page is preserved across iterations.
 * The detail page is closed in a `finally` so a failed row never leaks a tab.
 */
@Service
class KotlinScriptRenderer {

    fun render(plan: ExtractionPlan): String {
        val sb = StringBuilder()
        sb.append(PREAMBLE)
        sb.append('\n')

        for (step in plan.steps) {
            renderTopLevelStep(sb, step)
        }

        sb.append(EPILOGUE_TEMPLATE.replace("__ROOT_KEY__", esc(plan.output.rootKey)))
        return sb.toString()
    }

    private fun renderTopLevelStep(sb: StringBuilder, step: ExtractionStep) {
        sb.append("\n    // ${stepCommentHeader(step)}\n")
        when (step) {
            is ExtractionStep.Navigate -> {
                sb.append("    log(\"navigating to ${esc(step.url)}\")\n")
                sb.append("    page.navigate(\"${esc(step.url)}\", Page.NavigateOptions()")
                sb.append(".setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(${NAV_TIMEOUT_MS}.0))\n")
                sb.append("    page.waitForTimeout(${POST_NAV_SETTLE_MS}.0)\n")
            }
            is ExtractionStep.DismissConsent -> {
                sb.append("    dismissConsent(page)\n")
            }
            is ExtractionStep.WaitForSelector -> {
                sb.append("    page.waitForSelector(\"${esc(step.selector)}\", ")
                sb.append("Page.WaitForSelectorOptions().setTimeout(${step.timeoutMs}.0))\n")
            }
            is ExtractionStep.ResolveAndNavigate, is ExtractionStep.Click -> {
                // These only make sense inside ForEachRow.perRowSteps. Render
                // them defensively so a malformed plan produces a script that
                // fails loudly instead of silently skipping.
                sb.append("    error(\"step ${step::class.simpleName} at top level is not supported\")\n")
            }
            is ExtractionStep.ExtractRows -> {
                renderTopLevelExtractRows(sb, step)
            }
            is ExtractionStep.ForEachRow -> {
                renderForEachRow(sb, step)
            }
        }
    }

    private fun renderTopLevelExtractRows(sb: StringBuilder, step: ExtractionStep.ExtractRows) {
        val keyVar = "rows_${step.attachAs}"
        val countVar = "rows_count_${step.attachAs}"
        val cappedVar = "rows_capped_${step.attachAs}"
        sb.append("    val $keyVar = page.locator(\"${esc(step.rowSelector)}\")\n")
        sb.append("    val $countVar = $keyVar.count()\n")
        sb.append("    val $cappedVar = ${cappedExpr(countVar, step.limit)}\n")
        sb.append("    log(\"${esc(step.attachAs)} rows: extracting \$$cappedVar of \$$countVar\")\n")
        sb.append("    val collected_${step.attachAs} = mutableListOf<Map<String, Any?>>()\n")
        sb.append("    for (i in 0 until $cappedVar) {\n")
        sb.append("        try {\n")
        sb.append("            val row = $keyVar.nth(i)\n")
        sb.append("            val record = mutableMapOf<String, Any?>()\n")
        for (field in step.fields) {
            sb.append("            record[\"${esc(field.name)}\"] = ")
            sb.append(fieldExtractorCall("row", field))
            sb.append('\n')
        }
        sb.append("            collected_${step.attachAs}.add(record)\n")
        sb.append("        } catch (e: Exception) {\n")
        sb.append("            log(\"row \${i+1}/\$$cappedVar failed: \${e.message}\")\n")
        sb.append("        }\n")
        sb.append("    }\n")
        sb.append("    result[\"${esc(step.attachAs)}\"] = collected_${step.attachAs}\n")
    }

    private fun renderForEachRow(sb: StringBuilder, step: ExtractionStep.ForEachRow) {
        val hasNavigation = step.perRowSteps.any { it is ExtractionStep.ResolveAndNavigate }

        sb.append("    val outerRows = page.locator(\"${esc(step.rowSelector)}\")\n")
        sb.append("    val outerCount = outerRows.count()\n")
        sb.append("    val outerCapped = ${cappedExpr("outerCount", step.limit)}\n")
        sb.append("    log(\"${esc(step.attachAs)} rows: iterating \$outerCapped of \$outerCount\")\n")
        sb.append("    val collected = mutableListOf<Map<String, Any?>>()\n")
        sb.append("    val listingBaseUrl = page.url()\n")
        sb.append("    for (i in 0 until outerCapped) {\n")
        sb.append("        try {\n")
        sb.append("            val row = outerRows.nth(i)\n")
        sb.append("            val record = mutableMapOf<String, Any?>()\n")
        for (field in step.fields) {
            sb.append("            record[\"${esc(field.name)}\"] = ")
            sb.append(fieldExtractorCall("row", field))
            sb.append('\n')
        }
        if (hasNavigation) {
            sb.append("            val detailPage = context.newPage()\n")
            sb.append("            try {\n")
            renderPerRowSteps(sb, step.perRowSteps, indent = "                ")
            sb.append("            } finally {\n")
            sb.append("                runCatching { detailPage.close() }\n")
            sb.append("            }\n")
        } else {
            renderPerRowStepsInRowScope(sb, step.perRowSteps, indent = "            ")
        }
        sb.append("            collected.add(record)\n")
        sb.append("            log(\"${esc(step.attachAs)} \${i+1}/\$outerCapped extracted\")\n")
        sb.append("        } catch (e: Exception) {\n")
        sb.append("            log(\"${esc(step.attachAs)} \${i+1}/\$outerCapped failed: \${e.message}\")\n")
        sb.append("        }\n")
        if (hasNavigation) {
            sb.append("        if (i < outerCapped - 1) pauseBetweenRequests()\n")
        }
        sb.append("    }\n")
        sb.append("    result[\"${esc(step.attachAs)}\"] = collected\n")
    }

    private fun cappedExpr(countVar: String, limit: Int?): String =
        if (limit == null) countVar else "minOf($countVar, $limit)"

    private fun renderPerRowSteps(sb: StringBuilder, steps: List<ExtractionStep>, indent: String) {
        for (step in steps) {
            sb.append("$indent// ${stepCommentHeader(step)}\n")
            when (step) {
                is ExtractionStep.ResolveAndNavigate -> {
                    val nthExpr = step.nth?.let { ".nth($it)" } ?: ".first()"
                    sb.append("${indent}val href = row.locator(\"${esc(step.detailLinkSelector)}\")$nthExpr.getAttribute(\"href\")\n")
                    sb.append("${indent}if (href.isNullOrBlank()) error(\"detail link resolved to empty href on row \$i\")\n")
                    sb.append("${indent}val resolved = resolveHref(listingBaseUrl, href)\n")
                    sb.append("${indent}log(\"detail navigate -> \$resolved\")\n")
                    sb.append("${indent}detailPage.navigate(resolved, Page.NavigateOptions()")
                    sb.append(".setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(${NAV_TIMEOUT_MS}.0))\n")
                    sb.append("${indent}detailPage.waitForTimeout(${POST_NAV_SETTLE_MS}.0)\n")
                }
                is ExtractionStep.DismissConsent -> {
                    sb.append("${indent}dismissConsent(detailPage)\n")
                }
                is ExtractionStep.WaitForSelector -> {
                    sb.append("${indent}detailPage.waitForSelector(\"${esc(step.selector)}\", ")
                    sb.append("Page.WaitForSelectorOptions().setTimeout(${step.timeoutMs}.0))\n")
                }
                is ExtractionStep.Click -> {
                    sb.append(clickCall(receiver = "detailPage", step = step, indent = indent))
                }
                is ExtractionStep.ExtractRows -> {
                    renderInnerExtractRows(sb, step, indent = indent, scope = "detailPage")
                }
                is ExtractionStep.Navigate -> {
                    sb.append("${indent}error(\"absolute Navigate inside ForEachRow is not supported\")\n")
                }
                is ExtractionStep.ForEachRow -> {
                    sb.append("${indent}error(\"nested ForEachRow is not supported\")\n")
                }
            }
        }
    }

    private fun renderPerRowStepsInRowScope(sb: StringBuilder, steps: List<ExtractionStep>, indent: String) {
        for (step in steps) {
            sb.append("$indent// ${stepCommentHeader(step)}\n")
            when (step) {
                is ExtractionStep.Click -> {
                    sb.append(clickCall(receiver = "row", step = step, indent = indent))
                }
                is ExtractionStep.WaitForSelector -> {
                    sb.append("${indent}row.locator(\"${esc(step.selector)}\").first().waitFor(")
                    sb.append("Locator.WaitForOptions().setTimeout(${step.timeoutMs}.0))\n")
                }
                is ExtractionStep.ExtractRows -> {
                    renderInnerExtractRows(sb, step, indent = indent, scope = "row")
                }
                is ExtractionStep.ResolveAndNavigate, is ExtractionStep.Navigate,
                is ExtractionStep.DismissConsent, is ExtractionStep.ForEachRow -> {
                    sb.append("${indent}error(\"step ${step::class.simpleName} is not supported in row-only per-row scope\")\n")
                }
            }
        }
    }

    private fun renderInnerExtractRows(
        sb: StringBuilder,
        step: ExtractionStep.ExtractRows,
        indent: String,
        scope: String,
    ) {
        sb.append("${indent}val innerRows = $scope.locator(\"${esc(step.rowSelector)}\")\n")
        sb.append("${indent}val innerCount = innerRows.count()\n")
        sb.append("${indent}val nested = mutableListOf<Map<String, Any?>>()\n")
        sb.append("${indent}for (j in 0 until innerCount) {\n")
        sb.append("$indent    try {\n")
        sb.append("$indent        val innerRow = innerRows.nth(j)\n")
        sb.append("$indent        val sub = mutableMapOf<String, Any?>()\n")
        for (field in step.fields) {
            sb.append("$indent        sub[\"${esc(field.name)}\"] = ")
            sb.append(fieldExtractorCall("innerRow", field))
            sb.append('\n')
        }
        sb.append("$indent        nested.add(sub)\n")
        sb.append("$indent    } catch (e: Exception) {\n")
        sb.append("$indent        log(\"inner ${esc(step.attachAs)} \${j+1}/\$innerCount failed: \${e.message}\")\n")
        sb.append("$indent    }\n")
        sb.append("$indent}\n")
        sb.append("${indent}record[\"${esc(step.attachAs)}\"] = nested\n")
    }

    private fun clickCall(receiver: String, step: ExtractionStep.Click, indent: String): String {
        val sb = StringBuilder()
        sb.append("${indent}run {\n")
        sb.append("$indent    var clickLoc: Locator = $receiver.locator(\"${esc(step.selector)}\")\n")
        if (step.text != null) {
            sb.append("$indent    clickLoc = clickLoc.filter(Locator.FilterOptions().setHasText(\"${esc(step.text)}\"))\n")
        }
        val nthExpr = step.nth?.let { ".nth($it)" } ?: ".first()"
        sb.append("$indent    clickLoc = clickLoc$nthExpr\n")
        sb.append("$indent    clickLoc.click(Locator.ClickOptions().setTimeout(${CLICK_TIMEOUT_MS}.0))\n")
        sb.append("$indent    $receiver.waitForTimeout(${POST_CLICK_SETTLE_MS}.0)\n")
        sb.append("$indent}\n")
        return sb.toString()
    }

    private fun fieldExtractorCall(scope: String, field: FieldSelector): String {
        val nthExpr = field.nth?.toString() ?: "null"
        val sourceExpr = when (val src = field.source) {
            ValueSource.Text -> "Source.Text"
            is ValueSource.Attribute -> "Source.Attribute(\"${esc(src.name)}\")"
        }
        return "extractField($scope, \"${esc(field.selector)}\", $nthExpr, $sourceExpr)"
    }

    private fun stepCommentHeader(step: ExtractionStep): String {
        val name = step::class.simpleName ?: "Step"
        val desc = step.description.replace('\n', ' ').trim().take(140)
        return "$name: $desc"
    }

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("$", "\\$")

    private companion object {
        const val NAV_TIMEOUT_MS = 30_000
        const val POST_NAV_SETTLE_MS = 1_000
        const val CLICK_TIMEOUT_MS = 10_000
        const val POST_CLICK_SETTLE_MS = 800

        // The leading `@file:` lines must be at the very top of the script;
        // everything else (imports, helpers, top-level code) follows below.
        const val PREAMBLE = """@file:Repository("https://repo.maven.apache.org/maven2/")
@file:DependsOn("com.microsoft.playwright:playwright:1.49.0")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Frame
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.WaitUntilState
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.net.URI
import java.util.concurrent.ThreadLocalRandom

// ---------------------------------------------------------------------------
// Generated by AgenticCrawl. Self-contained Playwright scraper.
// Run with: kotlin <thisFile>.main.kts --out result.json [--headed] [--debug]
// ---------------------------------------------------------------------------

val outPath: String = run {
    val idx = args.indexOf("--out")
    if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else "result.json"
}
val headed: Boolean = args.contains("--headed")
val debug: Boolean = args.contains("--debug")

val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

fun log(msg: String) {
    System.err.println("[" + java.time.LocalTime.now() + "] " + msg)
}

fun pauseBetweenRequests() {
    val ms = ThreadLocalRandom.current().nextLong(500L, 1500L)
    Thread.sleep(ms)
}

fun resolveHref(baseUrl: String, href: String): String =
    URI.create(baseUrl).resolve(href).toString()

sealed class Source {
    object Text : Source()
    data class Attribute(val name: String) : Source()
}

fun extractField(scope: Locator, selector: String, nth: Int?, source: Source): String? {
    val base = scope.locator(selector)
    val target = if (nth != null) base.nth(nth) else base.first()
    return try {
        if (target.count() == 0) return null
        when (source) {
            is Source.Text -> target.innerText().trim().ifEmpty { null }
            is Source.Attribute -> target.getAttribute(source.name)
        }
    } catch (e: Exception) {
        if (debug) log("extractField('" + selector + "') failed: " + e.message)
        null
    }
}

// Consent cookies persist across navigations within the same context, so
// running the dismisser more than once just wastes the wait-for-frame time.
var consentHandled: Boolean = false

fun dismissConsent(page: Page) {
    if (consentHandled) {
        log("consent already handled in this context — skipping")
        return
    }
    val patterns = listOf(
        "Reject all", "Reject", "Decline", "Disagree", "I do not agree",
        "Accept all", "Accept", "I agree", "Got it", "OK"
    )
    val frames = listOf(page.mainFrame()) + page.frames().filter { it != page.mainFrame() }
    for (frame in frames) {
        for (pattern in patterns) {
            val byRole = frame.getByRole(
                AriaRole.BUTTON,
                Frame.GetByRoleOptions().setName(java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)),
            )
            if (byRole.count() == 0) continue
            try {
                byRole.first().click(Locator.ClickOptions().setTimeout(2_000.0))
                page.waitForTimeout(300.0)
                log("consent: dismissed via pattern '" + pattern + "'")
                consentHandled = true
                return
            } catch (_: Exception) {
                // try the next pattern
            }
        }
    }
    // Mark handled even on no-click: a no-banner page tells us the cookie is
    // already in place, so future calls would just re-scan for nothing.
    consentHandled = true
}

Playwright.create().use { pw ->
    val browser: Browser = pw.chromium().launch(
        BrowserType.LaunchOptions().setHeadless(!headed)
    )
    val context: BrowserContext = browser.newContext()
    val page: Page = context.newPage()

    val result: MutableMap<String, Any?> = mutableMapOf()
"""

        const val EPILOGUE_TEMPLATE = """
    File(outPath).writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result))
    log("wrote result to " + outPath + " (rootKey='__ROOT_KEY__')")

    runCatching { context.close() }
    runCatching { browser.close() }
}
"""
    }
}
