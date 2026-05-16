package com.noelnp.agenticcrawl.job

import com.fasterxml.jackson.databind.ObjectMapper
import com.noelnp.agenticcrawl.analysis.DetailLinkFinder
import com.noelnp.agenticcrawl.analysis.PageAnalyzer
import com.noelnp.agenticcrawl.analysis.SelectorMapper
import com.noelnp.agenticcrawl.analysis.Target
import com.noelnp.agenticcrawl.analysis.TargetType
import com.noelnp.agenticcrawl.analysis.ValidationVerdict
import com.noelnp.agenticcrawl.browser.BrowserService
import com.noelnp.agenticcrawl.browser.BrowserSessionManager
import com.noelnp.agenticcrawl.browser.LiveSession
import com.noelnp.agenticcrawl.browser.PageCapture
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.concurrent.ExecutorService

@Service
class JobService(
    private val jobRepository: JobRepository,
    private val browserService: BrowserService,
    private val sessionManager: BrowserSessionManager,
    private val pageAnalyzer: PageAnalyzer,
    private val selectorMapper: SelectorMapper,
    private val detailLinkFinder: DetailLinkFinder,
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

    @Transactional(readOnly = true)
    fun get(id: UUID): Job =
        jobRepository.findById(id).orElseThrow { JobNotFoundException(id) }
            .also {
                // Force-initialise lazy collections while still in the transaction
                // so callers outside this method can read them.
                it.layers.size
                it.planSteps.size
            }

    fun confirm(id: UUID): Job {
        val current = get(id)
        if (current.status != JobStatus.AWAITING_CONFIRMATION) {
            throw InvalidJobStateException(id, current.status, JobStatus.AWAITING_CONFIRMATION)
        }
        val session = sessionManager.take(id)
            ?: throw SessionUnavailableException(id)
        val updated = update(id) { it.status = JobStatus.RUNNING_PLAN }
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
            val job = update(id) { it.status = JobStatus.RUNNING_LISTING_RECON }
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
            update(id) { j ->
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
                update(id) {
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

            update(id) { it.status = JobStatus.AWAITING_CONFIRMATION }
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
        try {
            val target = loadListingTarget(id)
                ?: throw IllegalStateException("listing mapping invoked but no target stored on layer 0")

            val groundedValues = target.fields.map { it.text }.filter { it.isNotBlank() }
            log.debug("running locator over {} grounded values (type={})", groundedValues.size, target.type)

            val containerHtml = when (target.type) {
                TargetType.MULTI -> session.findRowContainerHtml(groundedValues)
                TargetType.SINGLE -> groundedValues.firstOrNull()?.let { session.findSingleElementHtml(it) }
            }
            if (containerHtml.isNullOrBlank()) {
                log.warn("locator returned no element — saving listing layer without containerHtml")
            } else {
                log.info("locator captured html chars={}", containerHtml.length)
            }

            val structure = containerHtml
                ?.takeIf { it.isNotBlank() }
                ?.let { html ->
                    val base = selectorMapper.map(html, target.fields, target.type) ?: return@let null
                    if (target.type == TargetType.MULTI) {
                        val link = detailLinkFinder.find(html, target.fields)
                        base.copy(detailLink = link)
                    } else {
                        base
                    }
                }
            val structureJson = structure?.let { objectMapper.writeValueAsString(it) }
            if (structure != null) {
                log.info(
                    "structure rowSelector='{}' fields={} detailLink={}",
                    structure.rowSelector,
                    structure.fields.joinToString { "${it.name}->${it.selector}" },
                    structure.detailLink?.let { "${it.selector}${it.nth?.let { n -> " nth=$n" }.orEmpty()}" } ?: "none",
                )
            } else if (!containerHtml.isNullOrBlank()) {
                log.warn("structure inference returned no result")
            }

            update(id) { j ->
                val layer = j.layers.firstOrNull { it.layerIndex == 0 }
                    ?: error("listing layer missing on job $id")
                layer.containerHtml = containerHtml
                layer.extractedStructureJson = structureJson
            }

            // Until the planner / orchestrator lands, terminate here.
            update(id) {
                it.status = JobStatus.SUCCEEDED
                it.goalSatisfied = true
            }
            log.info("status -> SUCCEEDED")
        } catch (e: Exception) {
            log.error("listing mapping failed: {}", e.message, e)
            markFailed(id, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { session.close() }
        }
    }

    private fun onSessionExpired(id: UUID) {
        withMdc(id) {
            try {
                val job = get(id)
                if (job.status != JobStatus.AWAITING_CONFIRMATION) {
                    log.debug("expiry no-op (status={})", job.status)
                    return@withMdc
                }
                update(id) {
                    it.status = JobStatus.EXPIRED
                    it.errorMessage = "Confirmation timeout"
                }
                log.info("status -> EXPIRED (no confirmation within {}s)", CONFIRMATION_TTL_SECONDS)
            } catch (e: Exception) {
                log.error("failed to mark job EXPIRED", e)
            }
        }
    }

    @Transactional(readOnly = true)
    fun loadListingTarget(id: UUID): Target? {
        val job = jobRepository.findById(id).orElseThrow { JobNotFoundException(id) }
        val layer = job.layers.firstOrNull { it.layerIndex == 0 } ?: return null
        return parseTarget(layer.targetJson)
    }

    @Transactional(readOnly = true)
    fun loadLayerScreenshot(id: UUID, layerIndex: Int): ByteArray? {
        val job = jobRepository.findById(id).orElseThrow { JobNotFoundException(id) }
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

    private fun markFailed(id: UUID, message: String) {
        runCatching {
            update(id) {
                it.status = JobStatus.FAILED
                it.errorMessage = message
            }
            log.warn("status -> FAILED message='{}'", message)
        }.onFailure { log.error("could not mark job failed", it) }
    }

    @Transactional
    fun update(id: UUID, mutator: (Job) -> Unit): Job {
        val job = jobRepository.findById(id).orElseThrow { JobNotFoundException(id) }
        mutator(job)
        return jobRepository.save(job)
    }

    private fun normalizeWhitespace(s: String): String =
        s.replace(WHITESPACE_REGEX, " ").trim()

    private companion object {
        const val GROUNDEDNESS_MIN_MATCHES = 2
        const val CONFIRMATION_TTL_SECONDS = 180L
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
