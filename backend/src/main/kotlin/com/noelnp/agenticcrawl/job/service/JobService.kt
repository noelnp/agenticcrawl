package com.noelnp.agenticcrawl.job.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noelnp.agenticcrawl.analysis.service.DetailLinkFinder
import com.noelnp.agenticcrawl.analysis.model.ExtractedStructure
import com.noelnp.agenticcrawl.analysis.model.FieldSelector
import com.noelnp.agenticcrawl.analysis.model.PageAnalysis
import com.noelnp.agenticcrawl.analysis.service.PageAnalyzer
import com.noelnp.agenticcrawl.analysis.service.SelectorMapper
import com.noelnp.agenticcrawl.analysis.model.Target
import com.noelnp.agenticcrawl.analysis.model.TargetField
import com.noelnp.agenticcrawl.analysis.model.TargetType
import com.noelnp.agenticcrawl.analysis.model.ValidationVerdict
import com.noelnp.agenticcrawl.analysis.model.ValueSource
import com.noelnp.agenticcrawl.browser.service.BrowserService
import com.noelnp.agenticcrawl.browser.session.BrowserSessionManager
import com.noelnp.agenticcrawl.browser.session.LiveSession
import com.noelnp.agenticcrawl.browser.model.PageCapture
import com.noelnp.agenticcrawl.codegen.service.PlanAssembler
import com.noelnp.agenticcrawl.job.domain.Job
import com.noelnp.agenticcrawl.job.domain.JobStatus
import com.noelnp.agenticcrawl.job.domain.PlanAction
import com.noelnp.agenticcrawl.job.domain.PlanOutcome
import com.noelnp.agenticcrawl.job.domain.PlanStep
import com.noelnp.agenticcrawl.job.domain.ReconLayer
import com.noelnp.agenticcrawl.job.domain.ReconLayerKind
import com.noelnp.agenticcrawl.job.repository.JobRepository
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ExecutorService

@Service
class JobService(
    private val jobRepository: JobRepository,
    private val jobMutator: JobMutator,
    private val browserService: BrowserService,
    private val sessionManager: BrowserSessionManager,
    private val pageAnalyzer: PageAnalyzer,
    private val selectorMapper: SelectorMapper,
    private val detailLinkFinder: DetailLinkFinder,
    private val planAssembler: PlanAssembler,
    @Lazy private val orchestrator: Orchestrator,
    private val jobExecutor: ExecutorService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(description: String, url: String): Job {
        val saved = jobRepository.save(Job(description = description, url = url))
        val id = saved.id ?: error("job id was null after save")
        log.info("created job url={} descriptionPreview='{}'", url, description.take(80))
        jobExecutor.submit { withMdc(id) { runListingRecon(id) } }
        return saved
    }

    fun get(id: UUID): Job = jobMutator.load(id)

    fun confirm(id: UUID): Job {
        val current = jobMutator.load(id)
        if (current.status != JobStatus.AWAITING_CONFIRMATION) {
            throw InvalidJobStateException(id, current.status, JobStatus.AWAITING_CONFIRMATION)
        }
        val session = sessionManager.take(id)
            ?: throw SessionUnavailableException(id)
        val updated = jobMutator.mutate(id) { it.status = JobStatus.RUNNING_PLAN }
        log.info("status -> RUNNING_PLAN (confirmed)")
        jobExecutor.submit { withMdc(id) { runListingMapping(id, session) } }
        return updated
    }

    private fun withMdc(id: UUID, block: () -> Unit) {
        MDC.put("jobId", id.toString())
        try {
            block()
        } finally {
            MDC.remove("jobId")
        }
    }

    private fun runListingRecon(id: UUID) {
        var session: LiveSession? = null
        try {
            val job = jobMutator.mutate(id) { it.status = JobStatus.RUNNING_LISTING_RECON }
            log.info("status -> RUNNING_LISTING_RECON url={}", job.url)

            log.debug("opening browser session")
            session = browserService.openSession(job.url)

            log.debug("starting capture")
            val capture = session.capture()
            log.info(
                "capture complete bytes={} visibleTextChars={}",
                capture.screenshot.size, capture.visibleText.length,
            )

            log.debug("starting analysis")
            val analysis = pageAnalyzer.analyze(job.description, capture.screenshot)
            log.info(
                "analysis verdict={} reasoning='{}'",
                analysis.verdict, analysis.reasoning,
            )

            // Persist Layer 0 (LISTING) with the screenshot + analysis result.
            jobMutator.mutate(id) { j ->
                val layer = ReconLayer(
                    job = j,
                    layerIndex = 0,
                    layerKind = ReconLayerKind.LISTING,
                    atUrl = j.url,
                )
                layer.screenshot = capture.screenshot
                layer.validationVerdict = analysis.verdict
                layer.validationReasoning = analysis.reasoning
                layer.targetJson = analysis.target?.let { objectMapper.writeValueAsString(it) }
                j.layers.add(layer)
            }

            if (analysis.verdict == ValidationVerdict.ABSENT) {
                jobMutator.mutate(id) {
                    it.status = JobStatus.SUCCEEDED
                    it.goalSatisfied = false
                }
                log.info("status -> SUCCEEDED (verdict ABSENT — skipping target)")
                session.close()
                session = null
                return
            }

            val target = analysis.target
            if (target == null) {
                markFailed(id, "Analyzer returned verdict ${analysis.verdict} but did not produce a target")
                return
            }

            logTarget(target)

            val groundedness = checkGroundedness(target, capture)
            log.info(
                "groundedness {}/{} matched required={} matched={} unmatched={}",
                groundedness.matched, groundedness.total, groundedness.required,
                groundedness.matchedNames, groundedness.unmatchedNames,
            )
            if (!groundedness.passes) {
                markFailed(
                    id,
                    "Target appears hallucinated: only ${groundedness.matched} of ${groundedness.total} values were found on the page",
                )
                return
            }

            jobMutator.mutate(id) { it.status = JobStatus.AWAITING_CONFIRMATION }
            sessionManager.register(id, session, CONFIRMATION_TTL_SECONDS) {
                onSessionExpired(id)
            }
            session = null
            log.info("status -> AWAITING_CONFIRMATION (ttl={}s)", CONFIRMATION_TTL_SECONDS)
        } catch (e: Exception) {
            log.error("listing recon failed: {}", e.message, e)
            markFailed(id, e.message ?: e.javaClass.simpleName)
        } finally {
            session?.let { runCatching { it.close() } }
        }
    }

    private fun runListingMapping(id: UUID, session: LiveSession) {
        var handedOff = false
        try {
            val target = loadListingTarget(id)
                ?: throw IllegalStateException("listing mapping invoked but no target stored on layer 0")

            val mapping = mapTargetToStructure(session, target, includeDetailLink = true)

            jobMutator.mutate(id) { j ->
                val layer = j.layers.firstOrNull { it.layerIndex == 0 }
                    ?: error("listing layer missing on job $id")
                layer.containerHtml = mapping.containerHtml
                layer.extractedStructureJson = mapping.structureJson
            }

            // Hand off to the orchestrator. It owns the planner loop and the
            // session lifecycle from this point on.
            handedOff = true
            orchestrator.runPlannerLoop(id, session)
        } catch (e: Exception) {
            log.error("listing mapping failed: {}", e.message, e)
            markFailed(id, e.message ?: e.javaClass.simpleName)
        } finally {
            if (!handedOff) runCatching { session.close() }
        }
    }

    /**
     * Run the selector-mapping pipeline against the live page for a verified
     * [target]. Shared by the listing layer (with [includeDetailLink] true
     * to also probe for per-row detail links) and by follow-up layers
     * captured during the planner loop (with it false — the orchestrator
     * does not navigate further from inside a stat/spec row).
     */
    fun mapTargetToStructure(
        session: LiveSession,
        target: Target,
        includeDetailLink: Boolean,
    ): MappingResult {
        val groundedValues = target.fields.map { it.text }.filter { it.isNotBlank() }
        log.debug(
            "running locator over {} grounded values (type={}, includeDetailLink={})",
            groundedValues.size, target.type, includeDetailLink,
        )

        val containerHtml = when (target.type) {
            TargetType.MULTI -> session.findRowContainerHtml(groundedValues)
            TargetType.SINGLE -> session.findSingleScopeHtml(groundedValues)
        }
        if (containerHtml.isNullOrBlank()) {
            log.warn("locator returned no element — no structure mapped")
            return MappingResult(null, null, null)
        }
        log.info("locator captured html chars={}", containerHtml.length)

        val initial = selectorMapper.map(containerHtml, target.fields, target.type)
        if (initial == null) {
            log.warn("structure inference returned no result")
            return MappingResult(containerHtml, null, null)
        }

        val refined = when (target.type) {
            TargetType.MULTI -> refineAcrossRows(session, initial, containerHtml, target)
            TargetType.SINGLE -> verifySingleRowSelectorPageUnique(session, initial, containerHtml, target)
        }
        if (refined == null) {
            log.warn("post-mapping verification rejected the structure — recon will surface this layer without selectors")
            return MappingResult(containerHtml, null, null)
        }

        val structure = if (target.type == TargetType.MULTI && includeDetailLink) {
            refined.copy(detailLink = detailLinkFinder.find(containerHtml, target.fields))
        } else {
            refined
        }
        log.info(
            "structure rowSelector='{}' fields={} detailLink={}",
            structure.rowSelector,
            structure.fields.joinToString { "${it.name}->${it.selector}" },
            structure.detailLink?.let { "${it.selector}${it.nth?.let { n -> " nth=$n" }.orEmpty()}" } ?: "none",
        )
        return MappingResult(
            containerHtml = containerHtml,
            structure = structure,
            structureJson = objectMapper.writeValueAsString(structure),
        )
    }

    data class MappingResult(
        val containerHtml: String?,
        val structure: ExtractedStructure?,
        val structureJson: String?,
    )

    /**
     * Sample several sibling rows on the live page and check how each field's
     * selector behaves there. A selector is healthy on a row when it resolves
     * to a non-empty leaf AND that leaf is not also the resolved leaf of some
     * other field — when two fields point at the same DOM node, one of them
     * is using a class/attribute that's conditionally shared by both leaves
     * on this row variant (a common failure mode with utility/styling-based
     * class names whose presence depends on the row's data). If anything's
     * weak, ask [selectorMapper] to refine with feedback about the specific
     * failure and a contrasting row's HTML. Returns the best structure we
     * landed on, even if refinement didn't fully close the gap.
     */
    private fun refineAcrossRows(
        session: LiveSession,
        initial: ExtractedStructure,
        primaryRowHtml: String,
        target: Target,
    ): ExtractedStructure? {
        val samples = session.sampleRowHtmls(
            rowSelector = initial.rowSelector,
            count = CROSS_ROW_SAMPLE_SIZE,
            skipFirst = 1,
        )
        if (samples.isEmpty()) {
            log.debug("cross-row verification: no additional rows available, skipping")
            return initial
        }

        var current = initial
        for (attempt in 0 until CROSS_ROW_MAX_REFINEMENTS) {
            val stats = computeCrossRowStats(current.fields, samples)
            logCrossRowStats(attempt, samples.size, stats)
            val weakFields = stats.hitRates.filter { it.value < WEAK_FIELD_THRESHOLD }
            if (weakFields.isEmpty() && stats.collisions.isEmpty()) break

            val contrastRow = pickContrastRow(samples, current.fields, weakFields, stats.collisions)
            if (contrastRow != null) {
                log.info(
                    "providing contrast row HTML to LLM ({} chars){}",
                    contrastRow.length,
                    if (stats.collisions.isNotEmpty()) " — row exposes a selector collision" else "",
                )
            } else {
                log.info("no contrast row available — refining with selector feedback only")
            }

            val problemFields = (weakFields.keys + stats.collisions.flatMap { listOf(it.fieldA, it.fieldB) }).toSet()
            log.warn("cross-row problems on fields: {} — asking LLM to refine", problemFields)

            val feedback = buildCrossRowFeedback(current.fields, weakFields, stats.collisions, contrastRow)
            val refined = selectorMapper.map(
                rowHtml = primaryRowHtml,
                fields = target.fields,
                type = target.type,
                externalFeedback = feedback,
            ) ?: break
            if (refined.fields.map { it.selector to it.nth } == current.fields.map { it.selector to it.nth }) {
                log.info("refinement returned identical selectors — accepting current structure")
                current = refined
                break
            }
            current = refined
        }

        // Final sanity gate. If half or more of the fields hit 0% across all
        // sampled rows, the rowSelector is matching the wrong elements
        // entirely (either UI chrome, or the row container plus children).
        // Persisting that produces garbage data at runtime (the user sees
        // filter labels and section headers shown as products) so reject the
        // structure outright and let the caller signal a recon failure.
        val finalStats = computeCrossRowStats(current.fields, samples)
        val zeroHitFields = finalStats.hitRates.filterValues { it == 0.0 }
        val halfOrMore = current.fields.size > 0 && zeroHitFields.size >= (current.fields.size + 1) / 2
        if (halfOrMore) {
            log.warn(
                "rejecting structure: {} of {} fields had 0% cross-row hit rate ({}) — rowSelector likely matches non-row elements",
                zeroHitFields.size, current.fields.size, zeroHitFields.keys,
            )
            return null
        }
        return current
    }

    /**
     * SINGLE-target post-mapping check: the rowSelector picked by the LLM is
     * only verified against the captured HTML, so a structurally valid pick
     * can still match many elements (or none) on the live page. For a
     * SINGLE-item view we need exactly one. On failure, compute which of
     * the captured root's own identifiers ARE page-unique and feed them to
     * SelectorMapper as a hint, then retry. Reject if no retry lands on a
     * unique anchor.
     */
    private fun verifySingleRowSelectorPageUnique(
        session: LiveSession,
        initial: ExtractedStructure,
        containerHtml: String,
        target: Target,
    ): ExtractedStructure? {
        var current = initial
        for (attempt in 0..SINGLE_UNIQUENESS_MAX_REFINEMENTS) {
            val count = session.countSelectorMatches(current.rowSelector)
            if (count == 1) {
                if (attempt > 0) {
                    log.info(
                        "SINGLE rowSelector '{}' became page-unique after {} refinement(s)",
                        current.rowSelector, attempt,
                    )
                }
                return current
            }
            if (attempt == SINGLE_UNIQUENESS_MAX_REFINEMENTS) {
                log.warn(
                    "SINGLE rowSelector '{}' matched {} elements after {} refinement(s) — rejecting",
                    current.rowSelector, count, attempt,
                )
                return null
            }
            val uniqueOptions = pageUniqueRootIdentifiers(session, containerHtml)
            log.warn(
                "SINGLE rowSelector '{}' matched {} elements on live page (need 1); refining with hints={}",
                current.rowSelector, count, uniqueOptions,
            )
            val feedback = buildSingleUniquenessFeedback(current.rowSelector, count, uniqueOptions)
            val refined = selectorMapper.map(
                rowHtml = containerHtml,
                fields = target.fields,
                type = target.type,
                externalFeedback = feedback,
            ) ?: return null
            if (refined.rowSelector == current.rowSelector) {
                log.info("SINGLE refinement returned the same rowSelector — giving up")
                return null
            }
            current = refined
        }
        return null
    }

    /**
     * For the captured row root, collect each identifier on the root itself
     * (id, classes, data-* attributes) that matches exactly one element on
     * the current live page. These are the safe options for the
     * SINGLE-item rowSelector.
     */
    private fun pageUniqueRootIdentifiers(session: LiveSession, rowHtml: String): List<String> {
        val root = runCatching { Jsoup.parseBodyFragment(rowHtml).body().firstElementChild() }
            .getOrNull() ?: return emptyList()
        val candidates = buildList<String> {
            root.id().takeIf { it.isNotBlank() }?.let { add("#$it") }
            root.classNames().forEach { cls -> if (cls.isNotBlank()) add(".$cls") }
            root.attributes().asList().forEach { attr ->
                if (attr.key.startsWith("data-") && attr.value.isNotBlank()) {
                    add("[${attr.key}='${attr.value}']")
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()
        val counts = session.selectorMatchCounts(candidates)
        return candidates.filter { counts[it] == 1 }
    }

    private fun buildSingleUniquenessFeedback(
        badSelector: String,
        matchCount: Int,
        uniqueOptions: List<String>,
    ): String {
        val header = "rowSelector '$badSelector' matched $matchCount elements on the FULL live page; " +
            "a SINGLE-item view requires the rowSelector to match exactly ONE element."
        return if (uniqueOptions.isEmpty()) {
            "$header The captured root has no own class/id/data-* attribute that's page-unique. " +
                "Pick something more specific — combine identifiers with a descendant combinator " +
                "(e.g. 'parentAttr X'), or accept that no unique anchor exists in the captured scope."
        } else {
            "$header These identifiers on the captured root ARE page-unique — pick exactly one of them " +
                "for rowSelector: ${uniqueOptions.joinToString(", ")}"
        }
    }

    private data class CrossRowStats(
        val hitRates: Map<String, Double>,
        val collisions: List<CollisionPair>,
    )

    private data class CollisionPair(
        val fieldA: String,
        val fieldB: String,
        val rowCount: Int,
    )

    /**
     * Per-field hit rate across [sampleRowHtmls] plus the pairs of fields whose
     * selectors resolve to the same DOM element on at least one sampled row.
     * A field counts as "hit" on a row only when (a) its selector resolves to
     * a non-empty leaf and (b) that leaf is unique to this field on that row.
     * Element identity is referential (Jsoup's [Element] defines equals as
     * identity, so the same node appears as the same instance in select()).
     */
    private fun computeCrossRowStats(
        fields: List<FieldSelector>,
        sampleRowHtmls: List<String>,
    ): CrossRowStats {
        val hits = mutableMapOf<String, Int>().apply { fields.forEach { put(it.name, 0) } }
        val collisionCounts = mutableMapOf<Pair<String, String>, Int>()
        val fieldOrder = fields.map { it.name }

        for (html in sampleRowHtmls) {
            val resolved = fields.associate { it.name to resolveFieldElement(html, it) }
            val grouped = mutableMapOf<Element, MutableList<String>>()
            for ((name, el) in resolved) {
                if (el != null) grouped.getOrPut(el) { mutableListOf() }.add(name)
            }
            val collidingNames = grouped.values.asSequence()
                .filter { it.size > 1 }
                .flatten()
                .toSet()
            for (group in grouped.values) {
                if (group.size < 2) continue
                val ordered = fieldOrder.filter { it in group }
                for (i in ordered.indices) for (j in i + 1 until ordered.size) {
                    val key = ordered[i] to ordered[j]
                    collisionCounts.merge(key, 1, Int::plus)
                }
            }
            for ((name, el) in resolved) {
                if (el != null && name !in collidingNames) {
                    hits[name] = (hits[name] ?: 0) + 1
                }
            }
        }

        val total = sampleRowHtmls.size.toDouble()
        val hitRates = hits.mapValues { it.value / total }
        val collisions = collisionCounts.entries
            .map { (pair, count) -> CollisionPair(pair.first, pair.second, count) }
            .sortedByDescending { it.rowCount }
        return CrossRowStats(hitRates, collisions)
    }

    /**
     * Resolve the leaf that [field]'s selector (with its nth and source) would
     * extract from [rowHtml], or null if the selector matches nothing useful
     * (no elements, out-of-range nth, or an empty value at the resolved leaf).
     */
    private fun resolveFieldElement(rowHtml: String, field: FieldSelector): Element? {
        val root = runCatching { Jsoup.parseBodyFragment(rowHtml).body().firstElementChild() }
            .getOrNull() ?: return null
        val matches = runCatching { root.select(field.selector) }.getOrNull().orEmpty()
            .asSequence().filter { it !== root }.toList()
        val element = when (val n = field.nth) {
            null -> matches.firstOrNull()
            else -> matches.getOrNull(n)
        } ?: return null
        val hasValue = when (val src = field.source) {
            ValueSource.Text -> element.text().trim().isNotEmpty()
            is ValueSource.Attribute -> element.attr(src.name).isNotBlank()
        }
        return if (hasValue) element else null
    }

    /**
     * Pick one peer-row HTML to show the LLM during refinement. Prefer a row
     * that triggers an element collision (those are the rows that prove the
     * current selector pair is non-distinguishing). Otherwise fall back to a
     * row where the weakest-hit field returned nothing.
     */
    private fun pickContrastRow(
        samples: List<String>,
        fields: List<FieldSelector>,
        weakFields: Map<String, Double>,
        collisions: List<CollisionPair>,
    ): String? {
        if (collisions.isNotEmpty()) {
            val collidingNames = collisions.flatMap { listOf(it.fieldA, it.fieldB) }.toSet()
            val row = samples.firstOrNull { html ->
                val resolved = fields.associate { it.name to resolveFieldElement(html, it) }
                val grouped = mutableMapOf<Element, MutableList<String>>()
                for ((name, el) in resolved) {
                    if (el != null) grouped.getOrPut(el) { mutableListOf() }.add(name)
                }
                grouped.values.any { g -> g.size > 1 && g.any { it in collidingNames } }
            }
            if (row != null) return row
        }
        val weakestName = weakFields.minByOrNull { it.value }?.key ?: return null
        val weakest = fields.firstOrNull { it.name == weakestName } ?: return null
        return samples.firstOrNull { resolveFieldElement(it, weakest) == null }
    }

    private fun logCrossRowStats(attempt: Int, sampleSize: Int, stats: CrossRowStats) {
        val hits = stats.hitRates.entries.joinToString { (n, r) -> "$n=${(r * 100).toInt()}%" }
        if (stats.collisions.isEmpty()) {
            log.info(
                "cross-row verification (attempt {}): sampled={} hits={}",
                attempt + 1, sampleSize, hits,
            )
        } else {
            val collisionLog = stats.collisions.joinToString {
                "${it.fieldA}<->${it.fieldB}=${it.rowCount}/$sampleSize"
            }
            log.info(
                "cross-row verification (attempt {}): sampled={} hits={} collisions={}",
                attempt + 1, sampleSize, hits, collisionLog,
            )
        }
    }

    private fun buildCrossRowFeedback(
        currentFields: List<FieldSelector>,
        weakFields: Map<String, Double>,
        collisions: List<CollisionPair>,
        contrastRowHtml: String?,
    ): String {
        val byName = currentFields.associateBy { it.name }
        val sections = mutableListOf<String>()

        if (collisions.isNotEmpty()) {
            val lines = collisions.map { c ->
                val a = byName[c.fieldA]
                val b = byName[c.fieldB]
                val aDesc = "'${c.fieldA}' (selector '${a?.selector ?: "?"}'${a?.nth?.let { " nth=$it" }.orEmpty()})"
                val bDesc = "'${c.fieldB}' (selector '${b?.selector ?: "?"}'${b?.nth?.let { " nth=$it" }.orEmpty()})"
                "- $aDesc and $bDesc resolved to the SAME DOM element on ${c.rowCount} of the sampled peer rows. " +
                    "When two field selectors land on the same node, at least one of them is using a class or " +
                    "attribute that's conditionally shared between both fields' leaves on some row variant. " +
                    "Replace both with selectors that name each leaf's permanent role, not a styling/utility " +
                    "class whose presence depends on the row's data."
            }
            sections += "Selector collisions across peer rows (these must be eliminated):\n" + lines.joinToString("\n")
        }

        if (weakFields.isNotEmpty()) {
            val lines = weakFields.entries.map { (name, rate) ->
                val pct = (rate * 100).toInt()
                val current = byName[name]
                val nthClause = current?.nth?.let { " (nth=$it)" }.orEmpty()
                val sel = current?.selector ?: "?"
                "- field '$name': selector '$sel'$nthClause was usable on only $pct% of sampled sibling rows. " +
                    "The selector either misses on standard rows, or collides with another field's leaf on them. " +
                    "Pick a selector that targets the same logical leaf on every row variant."
            }
            sections += "Fields with weak cross-row hit rate:\n" + lines.joinToString("\n")
        }

        val contrastBlock = contrastRowHtml?.let { html ->
            val trimmed = html.take(CONTRAST_ROW_HTML_CHAR_CAP)
            "\n\nHere is the outerHTML of a sibling row that exposes the problem above. Compare its DOM " +
                "shape and class set with the row you mapped against — pay attention to whether the same " +
                "class names appear on DIFFERENT elements between the two rows — and choose selectors that " +
                "work on BOTH variants:\n```\n$trimmed\n```"
        }.orEmpty()

        return sections.joinToString("\n\n") + contrastBlock +
            "\n\nReturn a structure whose selectors hit every standard row and never resolve two fields to the same element."
    }

    private fun onSessionExpired(id: UUID) {
        withMdc(id) {
            try {
                val job = jobMutator.load(id)
                if (job.status != JobStatus.AWAITING_CONFIRMATION) {
                    log.debug("expiry no-op (status={})", job.status)
                    return@withMdc
                }
                jobMutator.mutate(id) {
                    it.status = JobStatus.EXPIRED
                    it.errorMessage = "Confirmation timeout"
                }
                log.info("status -> EXPIRED (no confirmation within {}s)", CONFIRMATION_TTL_SECONDS)
            } catch (e: Exception) {
                log.error("failed to mark job EXPIRED", e)
            }
        }
    }

    fun loadListingTarget(id: UUID): Target? {
        val job = jobMutator.load(id)
        val layer = job.layers.firstOrNull { it.layerIndex == 0 } ?: return null
        return parseTarget(layer.targetJson)
    }

    fun loadLayerScreenshot(id: UUID, layerIndex: Int): ByteArray? {
        val job = jobMutator.load(id)
        val layer = job.layers.firstOrNull { it.layerIndex == layerIndex }
            ?: throw LayerNotFoundException(id, layerIndex)
        return layer.screenshot
    }

    private fun parseTarget(json: String?): Target? {
        if (json.isNullOrBlank()) return null
        return objectMapper.readValue(json, Target::class.java)
    }

    private fun logTarget(target: Target) {
        log.info("target type={} fields={}", target.type, target.fields.size)
        target.fields.forEach { log.info("  field {}='{}'", it.name, it.text) }
    }

    private data class GroundednessResult(
        val matched: Int,
        val total: Int,
        val required: Int,
        val matchedNames: List<String>,
        val unmatchedNames: List<String>,
    ) {
        val passes: Boolean get() = matched >= required
    }

    private fun checkGroundedness(target: Target, capture: PageCapture): GroundednessResult {
        val haystack = normalizeWhitespace(capture.visibleText)
        val (matched, unmatched) = target.fields.partition {
            val needle = normalizeWhitespace(it.text)
            needle.isNotBlank() && haystack.contains(needle, ignoreCase = true)
        }
        val total = target.fields.size
        val required = when (target.type) {
            TargetType.SINGLE -> 1
            TargetType.MULTI -> maxOf(GROUNDEDNESS_MIN_MATCHES, (total + 1) / 2)
        }
        return GroundednessResult(
            matched = matched.size,
            total = total,
            required = required,
            matchedNames = matched.map { it.name },
            unmatchedNames = unmatched.map { it.name },
        )
    }

    fun markFailed(id: UUID, message: String) {
        runCatching {
            jobMutator.mutate(id) {
                it.status = JobStatus.FAILED
                it.errorMessage = message
            }
            log.warn("status -> FAILED message='{}'", message)
        }.onFailure { log.error("could not mark job failed", it) }
    }

    fun markGoalSatisfied(id: UUID) {
        jobMutator.mutate(id) { job ->
            job.status = JobStatus.SUCCEEDED
            job.goalSatisfied = true
            val plan = runCatching { planAssembler.assemble(job) }
                .onFailure { log.warn("plan assembly threw: {}", it.message, it) }
                .getOrNull()
            if (plan != null) {
                job.extractionPlanJson = objectMapper.writeValueAsString(plan)
                log.info(
                    "extraction plan assembled: {} top-level steps, rootKey='{}'",
                    plan.steps.size, plan.output.rootKey,
                )
            } else {
                log.warn("extraction plan could not be assembled from job state")
            }
        }
        log.info("status -> SUCCEEDED (goal satisfied)")
    }

    fun recordPlanStep(
        id: UUID,
        action: PlanAction,
        reasoning: String,
        outcome: PlanOutcome,
        detail: String? = null,
        actionDataJson: String? = null,
    ) {
        jobMutator.mutate(id) { j ->
            val step = PlanStep(
                job = j,
                stepIndex = j.planSteps.size,
                action = action,
                reasoning = reasoning,
            )
            step.outcome = outcome
            step.detailMessage = detail
            step.actionDataJson = actionDataJson
            j.planSteps.add(step)
        }
    }

    fun appendLayer(
        id: UUID,
        atUrl: String,
        layerKind: ReconLayerKind,
        screenshot: ByteArray,
        analysis: PageAnalysis,
        containerHtml: String? = null,
        structureJson: String? = null,
    ): Int {
        var newIndex = -1
        jobMutator.mutate(id) { j ->
            val idx = j.layers.size
            val layer = ReconLayer(
                job = j,
                layerIndex = idx,
                layerKind = layerKind,
                atUrl = atUrl,
            )
            layer.screenshot = screenshot
            layer.validationVerdict = analysis.verdict
            layer.validationReasoning = analysis.reasoning
            layer.targetJson = analysis.target?.let { objectMapper.writeValueAsString(it) }
            layer.containerHtml = containerHtml
            layer.extractedStructureJson = structureJson
            j.layers.add(layer)
            newIndex = idx
        }
        return newIndex
    }

    /**
     * Filter [fields] to only those whose [TargetField.text] literally appears
     * in [visibleText] (whitespace-normalised, case-insensitive). Safety net
     * against vision-LLM hallucinations on follow-up layers — e.g. an
     * aggregated string like "A — B" that the page renders as two separate
     * elements. Such values cannot be located in the DOM, and feeding them
     * into selector mapping produces garbage selectors. Filtered before
     * mapping runs.
     */
    fun groundFields(fields: List<TargetField>, visibleText: String): List<TargetField> {
        val haystack = normalizeWhitespace(visibleText)
        return fields.filter { f ->
            val needle = normalizeWhitespace(f.text)
            needle.isNotBlank() && haystack.contains(needle, ignoreCase = true)
        }
    }

    private fun normalizeWhitespace(s: String): String =
        s.replace(WHITESPACE_REGEX, " ").trim()

    private companion object {
        const val GROUNDEDNESS_MIN_MATCHES = 2
        const val CONFIRMATION_TTL_SECONDS = 180L
        const val CROSS_ROW_SAMPLE_SIZE = 5
        const val CROSS_ROW_MAX_REFINEMENTS = 2
        const val WEAK_FIELD_THRESHOLD = 0.5
        const val CONTRAST_ROW_HTML_CHAR_CAP = 4_000
        const val SINGLE_UNIQUENESS_MAX_REFINEMENTS = 2
        val WHITESPACE_REGEX = Regex("[\\p{Zs}\\s]+")
    }
}

class JobNotFoundException(id: UUID) : RuntimeException("Job not found: $id")

class LayerNotFoundException(jobId: UUID, layerIndex: Int) :
    RuntimeException("Layer $layerIndex not found on job $jobId")

class InvalidJobStateException(id: UUID, actual: JobStatus, expected: JobStatus) :
    RuntimeException("Job $id is in state $actual but expected $expected")

class SessionUnavailableException(id: UUID) :
    RuntimeException("No live session available for job $id (it may have expired)")
