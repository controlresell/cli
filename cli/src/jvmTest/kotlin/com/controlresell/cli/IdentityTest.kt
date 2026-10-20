package com.controlresell.cli

import com.controlresell.cli.CliHarness.Companion.KEY
import com.controlresell.cli.CliHarness.Companion.ME
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class IdentityTest {
    @Test fun `login checks the key with GET me, then saves it privately`() = runTest {
        val cli = CliHarness()
        cli.on(HttpMethod.Get, "/api/public/v1/me", body = ME)
        val result = cli.run("login", stdin = KEY)
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "Signed in as vintage-shop.")
        assertEquals("Bearer $KEY", cli.requests.single().headers[HttpHeaders.Authorization])
        assertEquals("https://api.controlresell.com/api/public/v1/me", cli.requests.single().url.toString())
        val saved = Files.readString(cli.configFile)
        assertContains(saved, "\"apiKey\":\"$KEY\"")
        assertContains(saved, "\"apiUrl\":\"https://api.controlresell.com\"")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(cli.configFile)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(cli.configFile.parent)))
        assertFalse(KEY in result.stdout + result.stderr)
    }

    @Test fun `login saves the API URL given on the command line`() = runTest {
        val cli = CliHarness()
        cli.on(HttpMethod.Get, "/api/public/v1/me", body = ME)
        assertEquals(0, cli.run("login", "--api-url", "http://127.0.0.1:8080", stdin = KEY).code)
        assertEquals("127.0.0.1:8080", cli.requests.single().url.hostWithPort)
        assertContains(Files.readString(cli.configFile), "\"apiUrl\":\"http://127.0.0.1:8080\"")
    }

    @Test fun `a rejected key is never saved`() = runTest {
        val cli = CliHarness()
        cli.on(HttpMethod.Get, "/api/public/v1/me", status = 401, body = CliHarness.error("invalid_token", "The API key is invalid or revoked."))
        val result = cli.run("login", stdin = KEY)
        assertEquals(ExitCode.AUTHENTICATION, result.code)
        assertContains(result.stderr, "error: invalid_token: The API key is invalid or revoked. (request req_test)")
        assertFalse(Files.exists(cli.configFile))
    }

    @Test fun `something that is not an API key is refused without a network call`() = runTest {
        val cli = CliHarness()
        val result = cli.run("login", stdin = "my password")
        assertEquals(ExitCode.AUTHENTICATION, result.code)
        assertTrue(cli.requests.isEmpty())
        assertFalse(Files.exists(cli.configFile))
    }

    @Test fun `login refuses a plaintext remote API URL`() = runTest {
        val cli = CliHarness()
        val result = cli.run("login", "--api-url", "http://api.example", stdin = KEY)
        assertEquals(ExitCode.FAILURE, result.code)
        assertTrue(cli.requests.isEmpty())
    }

    @Test fun `whoami prints the seller, or the API JSON as is`() = runTest {
        val cli = CliHarness()
        cli.signIn()
        cli.on(HttpMethod.Get, "/api/public/v1/me", body = ME)
        val text = cli.run("whoami")
        assertEquals(0, text.code)
        assertEquals("Username  vintage-shop\nID        42\nCurrency  EUR\n", text.stdout)
        val json = cli.run("whoami", "--json")
        assertEquals("$ME\n", json.stdout)
        assertEquals("", json.stderr)
    }

    @Test fun `without credentials a command asks to sign in`() = runTest {
        val result = CliHarness().run("whoami")
        assertEquals(ExitCode.AUTHENTICATION, result.code)
        assertContains(result.stderr, "Not signed in. Run cr login")
    }

    @Test fun `the environment key takes precedence over the saved one`() = runTest {
        val cli = CliHarness(mapOf(API_KEY_VARIABLE to "cr_test_fromenvironment"))
        cli.signIn(apiUrl = "http://127.0.0.1:9000")
        cli.on(HttpMethod.Get, "/api/public/v1/me", body = ME)
        assertEquals(0, cli.run("whoami").code)
        assertEquals("Bearer cr_test_fromenvironment", cli.requests.single().headers[HttpHeaders.Authorization])
        assertEquals("127.0.0.1:9000", cli.requests.single().url.hostWithPort)
        val alone = CliHarness(mapOf(API_KEY_VARIABLE to "cr_test_fromenvironment"))
        alone.on(HttpMethod.Get, "/api/public/v1/me", body = ME)
        assertEquals(0, alone.run("whoami").code)
        assertEquals("api.controlresell.com", alone.requests.single().url.host)
    }

    @Test fun `API failures map to their exit codes`() = runTest {
        val cases = listOf(
            Triple(402, "plan_required", ExitCode.PLAN),
            Triple(403, "insufficient_scope", ExitCode.AUTHENTICATION),
            Triple(429, "rate_limited", ExitCode.RATE_LIMITED),
            Triple(500, "internal_error", ExitCode.FAILURE),
        )
        for ((status, code, exit) in cases) {
            val cli = CliHarness()
            cli.signIn()
            cli.on(HttpMethod.Get, "/api/public/v1/me", status = status, body = CliHarness.error(code, "Refused."),
                headers = if (status == 429) mapOf(HttpHeaders.RetryAfter to "12") else emptyMap())
            val result = cli.run("whoami")
            assertEquals(exit, result.code, code)
            assertContains(result.stderr, "error: $code: Refused. (request req_test)")
            if (status == 429) assertContains(result.stderr, "Retry after 12 seconds.")
            assertEquals("", result.stdout)
        }
    }

    @Test fun `logout removes the local configuration`() = runTest {
        val cli = CliHarness()
        cli.signIn()
        val result = cli.run("logout")
        assertEquals(0, result.code)
        assertFalse(Files.exists(cli.configFile))
        assertContains(result.stdout, "revoke it in the ControlResell app")
    }

    @Test fun `a usage error exits with code 2`() = runTest {
        assertEquals(ExitCode.USAGE, CliHarness().run("whoami", "--unknown").code)
        assertEquals(ExitCode.USAGE, CliHarness().run("nope").code)
    }
}
