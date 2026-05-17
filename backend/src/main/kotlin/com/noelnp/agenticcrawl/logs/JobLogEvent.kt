package com.noelnp.agenticcrawl.logs

import java.time.Instant
import java.util.UUID

data class JobLogEvent(
    val jobId: UUID,
    val seq: Long,
    val timestamp: Instant,
    val level: String,
    val logger: String,
    val message: String,
    val throwable: String? = null,
)
