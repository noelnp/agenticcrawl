package com.noelnp.agenticcrawl.job.service

import com.noelnp.agenticcrawl.job.domain.Job
import com.noelnp.agenticcrawl.job.repository.JobRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Transactional façade around [JobRepository] used by [JobService] and
 * [Orchestrator]. Lives as its own bean so that callers always reach it
 * through Spring's transactional proxy — self-invocation from another
 * method on the same bean would bypass [Transactional].
 */
@Component
class JobMutator(private val repo: JobRepository) {

    @Transactional(readOnly = true)
    fun load(id: UUID): Job {
        val job = repo.findById(id).orElseThrow { JobNotFoundException(id) }
        // Force-initialise lazy collections inside this transaction so callers
        // can read them once the transaction commits.
        job.layers.size
        job.planSteps.size
        return job
    }

    @Transactional
    fun mutate(id: UUID, mutator: (Job) -> Unit): Job {
        val job = repo.findById(id).orElseThrow { JobNotFoundException(id) }
        mutator(job)
        return repo.save(job)
    }
}
