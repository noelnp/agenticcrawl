package com.noelnp.agenticcrawl.job

enum class JobStatus {
    PENDING,
    RUNNING_LISTING_RECON,
    AWAITING_CONFIRMATION,
    RUNNING_PLAN,
    SUCCEEDED,
    FAILED,
    EXPIRED,
}
