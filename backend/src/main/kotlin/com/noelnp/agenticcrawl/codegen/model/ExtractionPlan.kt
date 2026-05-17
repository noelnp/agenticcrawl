package com.noelnp.agenticcrawl.codegen.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.noelnp.agenticcrawl.analysis.model.FieldSelector

const val EXTRACTION_PLAN_VERSION = 1

/**
 * Materialised post-recon contract handed to [com.noelnp.agenticcrawl.codegen.service.KotlinScriptRenderer].
 * Describes the full trajectory a generated Playwright script must execute, from the
 * initial navigation through nested per-row extractions.
 *
 * Produced by `PlanAssembler` after the planner picks FINISH. The contract is
 * self-describing JSON (Jackson type discriminators on [ExtractionStep]) so it
 * can be inspected in the UI, downloaded, and replayed independently of the
 * recon job that produced it.
 */
data class ExtractionPlan(
    val version: Int = EXTRACTION_PLAN_VERSION,
    val targetUrl: String,
    val userRequest: String,
    val description: String,
    val output: OutputSpec,
    val steps: List<ExtractionStep>,
)

/** Top-level output configuration. Output is nested JSON keyed under [rootKey]. */
data class OutputSpec(
    val format: String = "json",
    val rootKey: String,
)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "@type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = ExtractionStep.Navigate::class, name = "Navigate"),
    JsonSubTypes.Type(value = ExtractionStep.DismissConsent::class, name = "DismissConsent"),
    JsonSubTypes.Type(value = ExtractionStep.WaitForSelector::class, name = "WaitForSelector"),
    JsonSubTypes.Type(value = ExtractionStep.ResolveAndNavigate::class, name = "ResolveAndNavigate"),
    JsonSubTypes.Type(value = ExtractionStep.Click::class, name = "Click"),
    JsonSubTypes.Type(value = ExtractionStep.ExtractRows::class, name = "ExtractRows"),
    JsonSubTypes.Type(value = ExtractionStep.ForEachRow::class, name = "ForEachRow"),
)
sealed class ExtractionStep {
    abstract val description: String

    /** Navigate to an absolute URL. The script's entry point. */
    data class Navigate(
        val url: String,
        override val description: String,
    ) : ExtractionStep()

    /** Run consent dismissal across the page and its iframes. No-op if no banner is found. */
    data class DismissConsent(
        override val description: String = "Dismiss any cookie or consent banner if present.",
    ) : ExtractionStep()

    /** Block until [selector] is present in the DOM (or [timeoutMs] elapses). */
    data class WaitForSelector(
        val selector: String,
        val timeoutMs: Int = DEFAULT_WAIT_TIMEOUT_MS,
        override val description: String,
    ) : ExtractionStep()

    /**
     * Resolve a relative or absolute href from the element matched by
     * [detailLinkSelector] (scoped to the current row when this step appears
     * inside [ForEachRow.perRowSteps]) and navigate to it.
     */
    data class ResolveAndNavigate(
        val detailLinkSelector: String,
        val nth: Int? = null,
        override val description: String,
    ) : ExtractionStep()

    /**
     * Click an element. [text] (when set) narrows the locator with a has-text
     * filter; [nth] disambiguates if multiple elements match. Mirrors the same
     * shape as the recon-side `ClickTarget`.
     */
    data class Click(
        val selector: String,
        val text: String? = null,
        val nth: Int? = null,
        override val description: String,
    ) : ExtractionStep()

    /**
     * Iterate every element matching [rowSelector] (scoped to the current DOM
     * context — page root, or the active row inside [ForEachRow]) and extract
     * [fields] from each. Results attach as [attachAs] on the surrounding
     * record (root output or parent row). When [limit] is non-null, iteration
     * stops after that many rows even if more are present in the DOM.
     */
    data class ExtractRows(
        val rowSelector: String,
        val fields: List<FieldSelector>,
        val attachAs: String,
        override val description: String,
        val limit: Int? = null,
    ) : ExtractionStep()

    /**
     * Outer-loop iterator. Extracts [fields] from each row matching
     * [rowSelector] AND runs [perRowSteps] with that row as the active DOM
     * context. Inner [ExtractRows] inside [perRowSteps] attach under the
     * outer row's record at their own `attachAs` key. When [limit] is
     * non-null, iteration stops after that many outer rows.
     */
    data class ForEachRow(
        val rowSelector: String,
        val fields: List<FieldSelector>,
        val attachAs: String,
        val perRowSteps: List<ExtractionStep>,
        override val description: String,
        val limit: Int? = null,
    ) : ExtractionStep()

    companion object {
        const val DEFAULT_WAIT_TIMEOUT_MS = 15_000
    }
}
