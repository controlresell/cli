package com.controlresell.cli

import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FavoritesCommandsTest {
    private fun favorite(n: Int, item: String = """{"id":42,"name":"Jacket","price":{"amount":"12.50","currency":"EUR"},"sold":false}""") =
        """{"id":"fav_$n","accountId":"acc_1","platform":"VINTED","buyerId":"buyer_$n","createdAt":"2026-10-24T10:00:00Z",""" +
            """"messageSentAt":null,"listingId":"L$n","item":$item}"""

    private fun signedIn() = CliHarness().apply { signIn() }

    @Test fun `favorites list prints a table, sends the filters and gives the next cursor`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/favorites", body = """{"data":[${favorite(1)},${favorite(2, "null")}],"nextCursor":"c2"}""")
        val result = cli.run("favorites", "list", "--account", "acc_1", "--item", "42", "--from", "2026-10-01T00:00:00Z",
            "--to", "2026-11-01T00:00:00Z", "--limit", "2", "--cursor", "c1")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "buyer_1")
        assertContains(result.stdout, "Jacket")
        assertContains(result.stdout, "12.50 EUR")
        assertContains(result.stdout, "buyer_2")
        assertEquals("Next page: run the same command with --cursor c2\n", result.stderr)
        cli.requests.single().url.parameters.let {
            assertEquals("acc_1", it["accountId"]); assertEquals("42", it["itemId"]); assertEquals("2", it["limit"]); assertEquals("c1", it["cursor"])
            assertTrue(it["from"]!!.startsWith("2026-10-01")); assertTrue(it["to"]!!.startsWith("2026-11-01"))
        }
    }

    @Test fun `favorites list --all follows the cursors, and an empty list says so`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/favorites", body = """{"data":[${favorite(1)}],"nextCursor":"p2"}""", query = mapOf("cursor" to null))
        cli.on(HttpMethod.Get, "/api/public/v1/favorites", body = """{"data":[${favorite(2)}],"nextCursor":null}""", query = mapOf("cursor" to "p2"))
        val json = cli.run("favorites", "list", "--all", "--json")
        assertEquals(0, json.code, json.stderr)
        assertContains(json.stdout, "fav_1"); assertContains(json.stdout, "fav_2")
        val empty = signedIn()
        empty.on(HttpMethod.Get, "/api/public/v1/favorites", body = """{"data":[],"nextCursor":null}""")
        assertEquals("No favorites.\n", empty.run("favorites", "list").stdout)
    }

    @Test fun `favorites list refuses a typo before anything is sent`() = runTest {
        val cli = signedIn()
        assertEquals(ExitCode.USAGE, cli.run("favorites", "list", "--item", "x").code)
        assertEquals(ExitCode.USAGE, cli.run("favorites", "list", "--from", "yesterday").code)
        assertEquals(ExitCode.USAGE, cli.run("favorites", "list", "--from", "2026-10-02T00:00:00Z", "--to", "2026-10-01T00:00:00Z").code)
        assertTrue(cli.requests.isEmpty())
    }

    @Test fun `items favorites reads the favorites of one item, and a missing item exits with 4`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/items/42/favorites", body = """{"data":[${favorite(1)}],"nextCursor":null}""")
        cli.on(HttpMethod.Get, "/api/public/v1/items/43/favorites", status = 404, body = CliHarness.error("item_not_found", "No such item."))
        val result = cli.run("items", "favorites", "42", "--from", "2026-10-01T00:00:00Z")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "buyer_1")
        assertTrue(cli.requests.single().url.parameters["from"]!!.startsWith("2026-10-01"))
        assertEquals(ExitCode.NOT_FOUND, cli.run("items", "favorites", "43").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "favorites", "x").code)
    }
}
