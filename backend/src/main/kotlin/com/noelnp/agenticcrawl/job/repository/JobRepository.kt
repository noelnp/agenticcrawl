package com.noelnp.agenticcrawl.job.repository

import com.noelnp.agenticcrawl.job.domain.Job
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface JobRepository : JpaRepository<Job, UUID>
