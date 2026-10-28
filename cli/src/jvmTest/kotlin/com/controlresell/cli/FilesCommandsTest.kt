package com.controlresell.cli

import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class FilesCommandsTest {
    private fun signedIn() = CliHarness().apply { signIn() }
    private val registration = """{"id":"f_1","uploadUrl":"https://s3.test/put"}"""
    private val file = """{"id":"f_1","status":"CONFIRMED","url":"https://s3.test/get","createdAt":"2026-10-28T10:00:00Z"}"""

    private fun photo(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        Files.createTempDirectory("controlresell-cli-photo-").resolve(name).also { Files.write(it, bytes) }.toString()

    @Test fun `files upload registers the photo, puts its bytes without the key and prints the id`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Post, "/api/public/v1/files", status = 201, body = registration)
        cli.on(HttpMethod.Put, "/put", body = "")
        val result = cli.run("files", "upload", photo("jacket.JPG"))
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "f_1")
        assertFalse("s3.test" in result.stdout, "the signed address is never printed")
        val register = cli.requests[0]
        assertTrue(register.headers["Idempotency-Key"]!!.isNotBlank())
        val put = cli.requests[1]
        assertEquals(HttpMethod.Put, put.method)
        assertEquals("https://s3.test/put", put.url.toString())
        assertNull(put.headers[HttpHeaders.Authorization])
        assertEquals(ContentType.Image.JPEG, put.body.contentType)
    }

    @Test fun `files upload --json prints only the id`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Post, "/api/public/v1/files", status = 201, body = registration)
        cli.on(HttpMethod.Put, "/put", body = "")
        assertEquals("""{"id":"f_1"}""", cli.run("files", "upload", photo("a.png"), "--json").stdout.trim())
    }

    @Test fun `files upload refuses a typo before anything is sent`() = runTest {
        val cli = signedIn()
        assertEquals(ExitCode.USAGE, cli.run("files", "upload", "/nonexistent/a.jpg").code)
        assertEquals(ExitCode.USAGE, cli.run("files", "upload", photo("a.txt")).code)
        assertEquals(ExitCode.USAGE, cli.run("files", "upload", photo("a.jpg", ByteArray(0))).code)
        assertEquals(ExitCode.USAGE, cli.run("files", "upload", photo("a.jpg"), "--content-type", "text/plain").code)
        assertTrue(cli.requests.isEmpty())
    }

    @Test fun `files upload exits with 3 when the scope is missing`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Post, "/api/public/v1/files", status = 403, body = CliHarness.error("insufficient_scope", "Missing scope."))
        assertEquals(3, cli.run("files", "upload", photo("a.jpg")).code)
    }

    @Test fun `files get prints a sheet, and a missing file exits with 4`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/files/f_1", body = file)
        cli.on(HttpMethod.Get, "/api/public/v1/files/f_2", status = 404, body = CliHarness.error("file_not_found", "No such file."))
        val ok = cli.run("files", "get", "f_1")
        assertEquals(0, ok.code, ok.stderr)
        assertContains(ok.stdout, "CONFIRMED")
        assertEquals(4, cli.run("files", "get", "f_2").code)
        assertContains(cli.run("files", "get", "f_1", "--json").stdout, "\"status\"")
    }
}
