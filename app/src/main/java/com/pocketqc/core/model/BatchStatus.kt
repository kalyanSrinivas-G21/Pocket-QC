package com.pocketqc.core.model

/**
 * Lifecycle state of a production inspection batch.
 */
enum class BatchStatus {
    IN_PROGRESS,
    COMPLETED,
    APPROVED,
    REJECTED
}
