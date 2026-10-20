package com.controlresell.cli

import com.controlresell.sdk.ControlResellError
import com.controlresell.sdk.RateLimitException

/** Exit codes, one per family of failure. */
object ExitCode {
    const val SUCCESS = 0
    /** Network, TLS, files, 5xx or anything unexpected. */
    const val FAILURE = 1
    /** An invalid option or a missing argument. */
    const val USAGE = 2
    /** No credentials, an invalid API key (401) or a missing scope (403). */
    const val AUTHENTICATION = 3
    /** The resource does not exist, or belongs to another seller (404). */
    const val NOT_FOUND = 4
    /** The seller's plan does not include the API (402). */
    const val PLAN = 5
    /** The rate limit of the API key is reached (429). */
    const val RATE_LIMITED = 6
    /** The API refused the request: validation (400), conflict (409) or business refusal (422). */
    const val REFUSED = 7
}

/** A failure whose message is a fixed text written by this CLI, never a remote or platform string. */
class CliFailure(val exitCode: Int, val safeMessage: String) : Exception()

internal fun exitCodeFor(error: ControlResellError): Int = when (error.status) {
    401, 403 -> ExitCode.AUTHENTICATION
    402 -> ExitCode.PLAN
    404 -> ExitCode.NOT_FOUND
    429 -> ExitCode.RATE_LIMITED
    400, 409, 422 -> ExitCode.REFUSED
    else -> ExitCode.FAILURE
}

/** `error: <code>: <message> (request <requestId>)`, from the API's public error envelope only. */
internal fun describe(error: ControlResellError): String = buildString {
    append("error: ").append(printable(error.code)).append(": ").append(printable(error.message.orEmpty()))
    error.requestId?.let { append(" (request ").append(printable(it)).append(')') }
    when {
        error is RateLimitException -> error.retryAfterSeconds?.let { append("\nRetry after $it seconds.") }
        error.status == 401 -> append("\nRun cr login with a valid API key.")
    }
}

/** Remote text never drives the terminal: control characters are dropped. */
internal fun printable(value: String): String = value.filter { it.code >= 32 && it.code != 127 }.take(1_000)
