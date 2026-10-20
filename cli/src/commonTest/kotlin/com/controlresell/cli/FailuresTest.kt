package com.controlresell.cli

import com.controlresell.sdk.*
import kotlin.test.*

class FailuresTest {
    private fun error(status: Int) = ControlResellError(status, "code_$status", "Message $status", emptyMap(), "req_1")

    @Test fun `each API status family has its own exit code`() {
        assertEquals(ExitCode.AUTHENTICATION, exitCodeFor(error(401)))
        assertEquals(ExitCode.AUTHENTICATION, exitCodeFor(error(403)))
        assertEquals(ExitCode.PLAN, exitCodeFor(error(402)))
        assertEquals(ExitCode.NOT_FOUND, exitCodeFor(error(404)))
        assertEquals(ExitCode.RATE_LIMITED, exitCodeFor(error(429)))
        for (status in listOf(400, 409, 422)) assertEquals(ExitCode.REFUSED, exitCodeFor(error(status)))
        for (status in listOf(500, 502, 418)) assertEquals(ExitCode.FAILURE, exitCodeFor(error(status)))
    }

    @Test fun `the message gives the code and the API message and the request id`() {
        assertEquals("error: code_404: Message 404 (request req_1)", describe(error(404)))
        val limited = RateLimitException("rate_limited", "Slow down", emptyMap(), "req_2", retryAfterSeconds = 30)
        assertEquals("error: rate_limited: Slow down (request req_2)\nRetry after 30 seconds.", describe(limited))
    }

    @Test fun `remote text cannot inject terminal control sequences`() {
        val hostile = ControlResellError(400, "bad\u001b[2J", "line\nbreak\u0007", emptyMap(), null)
        assertEquals("error: bad[2J: linebreak", describe(hostile))
    }
}
