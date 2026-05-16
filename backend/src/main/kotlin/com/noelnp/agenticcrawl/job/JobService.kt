package com.noelnp.agenticcrawl.job

import com.fasterxml.jackson.databind.ObjectMapper
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
import java.util.UUID
import java.util.concurrent.ExecutorService

@Service
class JobService(
    private val jobRepository: JobRepository,
    private val browserService: BrowserService,
    private val sessionManager: BrowserSessionManager,
    private val pageAnalyzer: PageAnalyzer,
    private val selectorMapper: SelectorMapper,
    private val jobExecutor: ExecutorService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(description: String, url: String): Job {
        val saved = jobRepository.save(Job(description = description, url = url))
        val id = saved.id ?: error("job id was null after save")
        log.info("created job url={} descriptionPreview='{}'", url, description.take(80))
        jobExecutor.submit { withMdc(id) { runAnalysis(id) } }
        return saved
    }

    fun get(id: UUID): Job =
        jobRepository.findById(id).orElseThrow { JobNotFoundException(id) }

    fun confirm(id: UUID): Job {
        val job = get(id)
        if (job.status != JobStatus.AWAITING_CONFIRMATION) {
            throw InvalidJobStateException(id, job.status, JobStatus.AWAITING_CONFIRMATION)
        }
        val session = sessionManager.take(id)
            ?: throw SessionUnavailableException(id)
        val updated = update(id) { it.status = JobStatus.RUNNING }
        log.info("status -> RUNNING (confirmed)")
        jobExecutor.submit { withMdc(id) { runLocator(id, session) } }
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

    private fun runAnalysis(id: UUID) {
        var session: LiveSession? = null
        try {
            val job = update(id) { it.status = JobStatus.RUNNING }
            log.info("status -> RUNNING url={}", job.url)

            log.debug("opening browser session")
            session = browserService.openSession(job.url)

            log.debug("starting capture")
            val capture = session.capture()
            update(id) { it.screenshot = capture.screenshot }
            log.info("capture complete bytes={} visibleTextChars={}", capture.screenshot.size, capture.visibleText.length)

            log.debug("starting analysis")
            val analysis = pageAnalyzer.analyze(job.description, capture.screenshot)
            update(id) {
                it.validationVerdict = analysis.verdict
                it.validationReasoning = analysis.reasoning
            }
            log.info("analysis verdict={} reasoning='{}'", analysis.verdict, analysis.reasoning)

            if (analysis.verdict == ValidationVerdict.ABSENT) {
                update(id) { it.status = JobStatus.SUCCEEDED }
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
                groundedness.matched,
                groundedness.total,
                groundedness.required,
                groundedness.matchedNames,
                groundedness.unmatchedNames,
            )
            if (!groundedness.passes) {
                markFailed(
                    id,
                    "Target appears hallucinated: only ${groundedness.matched} of ${groundedness.total} values were found on the page",
                )
                return
            }

            update(id) {
                it.targetJson = objectMapper.writeValueAsString(target)
                it.status = JobStatus.AWAITING_CONFIRMATION
            }
            sessionManager.register(id, session, CONFIRMATION_TTL_SECONDS) {
                onSessionExpired(id)
            }
            session = null
            log.info("status -> AWAITING_CONFIRMATION (ttl={}s)", CONFIRMATION_TTL_SECONDS)
        } catch (e: Exception) {
            log.error("analysis failed: {}", e.message, e)
            markFailed(id, e.message ?: e.javaClass.simpleName)
        } finally {
            session?.let { runCatching { it.close() } }
        }
    }

    private fun runLocator(id: UUID, session: LiveSession) {
        try {
            val targetJson = jobRepository.findById(id).orElseThrow { JobNotFoundException(id) }
                .targetJson
            val target = parseTarget(targetJson)
                ?: throw IllegalStateException("locator invoked but no target stored on job")

            val groundedValues = target.fields.map { it.text }.filter { it.isNotBlank() }
            log.debug("running locator over {} grounded values (type={})", groundedValues.size, target.type)

            val containerHtml = when (target.type) {
                TargetType.MULTI -> session.findRowContainerHtml(groundedValues)
                TargetType.SINGLE -> groundedValues.firstOrNull()?.let { session.findSingleElementHtml(it) }
            }
            if (containerHtml.isNullOrBlank()) {
                log.warn("locator returned no element — saving job without containerHtml")
            } else {
                log.info("locator captured html chars={}", containerHtml.length)
            }

            val structure = containerHtml
                ?.takeIf { it.isNotBlank() }
                ?.let { selectorMapper.map(it, target.fields, target.type) }
            val structureJson = structure?.let { objectMapper.writeValueAsString(it) }
            if (structure != null) {
                log.info(
                    "structure rowSelector='{}' fields={}",
                    structure.rowSelector,
                    structure.fields.joinToString { "${it.name}->${it.selector}" },
                )
            } else if (!containerHtml.isNullOrBlank()) {
                log.warn("structure inference returned no result")
            }

            update(id) {
                it.containerHtml = containerHtml
                it.extractedStructureJson = structureJson
                it.status = JobStatus.SUCCEEDED
            }
            log.info("status -> SUCCEEDED")
        } catch (e: Exception) {
            log.error("locator failed: {}", e.message, e)
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

    private fun update(id: UUID, mutator: (Job) -> Unit): Job {
        val job = jobRepository.findById(id).orElseThrow { JobNotFoundException(id) }
        mutator(job)
        return jobRepository.save(job)
    }

    private fun normalizeWhitespace(s: String): String =
        s.replace(WHITESPACE_REGEX, " ").trim()

    private companion object {
        const val GROUNDEDNESS_MIN_MATCHES = 2
        const val CONFIRMATION_TTL_SECONDS = 180L
        // \p{Zs} = Unicode space-separator category (NBSP, NARROW NBSP, THIN SPACE, etc.)
        // Combined with \s to also catch tab/newline.
        val WHITESPACE_REGEX = Regex("[\\p{Zs}\\s]+")
    }
}

class JobNotFoundException(id: UUID) : RuntimeException("Job not found: $id")

class ScreenshotNotAvailableException(id: UUID) : RuntimeException("Screenshot not yet available for job $id")

class InvalidJobStateException(id: UUID, actual: JobStatus, expected: JobStatus) :
    RuntimeException("Job $id is in state $actual but expected $expected")

class SessionUnavailableException(id: UUID) :
    RuntimeException("No live session available for job $id (it may have expired)")
