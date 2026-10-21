package com.controlresell.cli

import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CustomersCommandsTest {
    private fun customer(n: Int) = """{"id":"cu_$n","firstName":"Ann$n","lastName":"Doe","email":"ann$n@example.com",""" +
        """"address":{"line1":"1 rue X","city":"Paris","country":"FR"},"createdAt":"2026-10-21T10:00:00Z"}"""

    private fun signedIn() = CliHarness().apply { signIn() }

    @Test fun `customers list prints a table, sends the filters and gives the next cursor`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/customers", body = """{"data":[${customer(1)},${customer(2)}],"nextCursor":"c2"}""")
        val result = cli.run("customers", "list", "--account", "acc_1", "--from", "2026-10-01T00:00:00Z",
            "--to", "2026-11-01T00:00:00Z", "--limit", "2", "--cursor", "c1")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "cu_1")
        assertContains(result.stdout, "Ann1 Doe")
        assertEquals("Next page: run the same command with --cursor c2\n", result.stderr)
        cli.requests.single().url.parameters.let {
            assertEquals("acc_1", it["accountId"]); assertEquals("2", it["limit"]); assertEquals("c1", it["cursor"])
            assertTrue(it["from"]!!.startsWith("2026-10-01")); assertTrue(it["to"]!!.startsWith("2026-11-01"))
        }
    }

    @Test fun `customers list --all follows the cursors, and an empty list says so`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/customers", body = """{"data":[${customer(1)}],"nextCursor":"p2"}""", query = mapOf("cursor" to null))
        cli.on(HttpMethod.Get, "/api/public/v1/customers", body = """{"data":[${customer(2)}],"nextCursor":null}""", query = mapOf("cursor" to "p2"))
        val json = cli.run("customers", "list", "--all", "--json")
        assertEquals(0, json.code, json.stderr)
        assertContains(json.stdout, "cu_1"); assertContains(json.stdout, "cu_2")
        val empty = signedIn()
        empty.on(HttpMethod.Get, "/api/public/v1/customers", body = """{"data":[],"nextCursor":null}""")
        assertEquals("No customers.\n", empty.run("customers", "list").stdout)
    }

    @Test fun `customers list refuses a typo before anything is sent`() = runTest {
        val cli = signedIn()
        assertEquals(ExitCode.USAGE, cli.run("customers", "list", "--from", "yesterday").code)
        assertEquals(ExitCode.USAGE, cli.run("customers", "list", "--from", "2026-10-02T00:00:00Z", "--to", "2026-10-01T00:00:00Z").code)
        assertTrue(cli.requests.isEmpty())
    }

    @Test fun `customers get prints a sheet, and a missing customer exits with 4`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/customers/cu_1", body = customer(1))
        cli.on(HttpMethod.Get, "/api/public/v1/customers/cu_2", status = 404, body = CliHarness.error("customer_not_found", "No such customer."))
        val result = cli.run("customers", "get", "cu_1")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "ann1@example.com")
        assertContains(result.stdout, "1 rue X, Paris, FR")
        assertEquals(ExitCode.NOT_FOUND, cli.run("customers", "get", "cu_2").code)
    }
}
