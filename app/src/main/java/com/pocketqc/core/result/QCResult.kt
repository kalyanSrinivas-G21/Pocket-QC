package com.pocketqc.core.result

/**
 * Result abstraction for hardware and pipeline operations, encapsulating success and failure.
 */
sealed interface QCResult<out T> {
    data class Success<out T>(val data: T) : QCResult<T>
    data class Error(val message: String, val throwable: Throwable? = null) : QCResult<Nothing>
    data object Loading : QCResult<Nothing>
}
