package com.basira.core.result

import com.basira.core.error.AppError

/**
 * Minimal typed result used across layers instead of exceptions for expected failures.
 *
 * @param T type of the successful value.
 */
sealed interface AppResult<out T> {

    /**
     * Successful outcome.
     *
     * @property value the produced value.
     */
    data class Success<out T>(val value: T) : AppResult<T>

    /**
     * Failed outcome.
     *
     * @property error typed, log-safe description of the failure.
     */
    data class Failure(val error: AppError) : AppResult<Nothing>
}

/** Returns the successful value or `null` when this is a [AppResult.Failure]. */
fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value

/** Returns the failure or `null` when this is a [AppResult.Success]. */
fun AppResult<*>.errorOrNull(): AppError? = (this as? AppResult.Failure)?.error

/**
 * Transforms the successful value with [transform] and keeps failures unchanged.
 *
 * @param transform mapping applied to a successful value.
 * @return a new result with the mapped value or the original failure.
 */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

/**
 * Chains another fallible operation on a successful value.
 *
 * @param transform operation executed only when this result is successful.
 * @return the result of [transform], or the original failure.
 */
inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Success -> transform(value)
    is AppResult.Failure -> this
}
