package com.controlresell.cli

import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ReadCommandsTest {
    private fun account(n: Int) = """{"id":"acc_$n","platform":"vinted","name":"Shop $n","status":"CONNECTED"}"""
    private fun item(n: Int, sold: Boolean = false) = """{"id":$n,"name":"Jacket $n","photos":[],"colorIds":[],"materialIds":[],"enumValues":[],"attributes":[],"customFields":[],"labels":["denim"],"sold":$sold,""" +
        """"createdAt":"2026-10-01T10:00:00Z","updatedAt":"2026-10-02T10:00:00Z","sellingPrice":{"amount":"12.50","currency":"EUR"}}"""

    private fun signedIn() = CliHarness().apply { signIn() }

    @Test fun `a list prints a table and gives the next cursor on stderr`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/accounts", body = """{"data":[${account(1)},${account(2)}],"nextCursor":"c2"}""")
        val result = cli.run("accounts", "list", "--limit", "2")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "Shop 1")
        assertContains(result.stdout, "acc_2")
        assertEquals("Next page: run the same command with --cursor c2\n", result.stderr)
        assertEquals("2", cli.requests.single().url.parameters["limit"])
    }

    @Test fun `json prints the page as the API answered it`() = runTest {
        val cli = signedIn()
        val page = """{"data":[${account(1)}],"nextCursor":null,"futureField":true}"""
        cli.on(HttpMethod.Get, "/api/public/v1/accounts", body = page)
        val result = cli.run("accounts", "list", "--json")
        assertEquals("$page\n", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test fun `all follows the cursors and merges the pages`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/items", body = """{"data":[${item(1)}],"nextCursor":"p2"}""", query = mapOf("cursor" to null))
        cli.on(HttpMethod.Get, "/api/public/v1/items", body = """{"data":[${item(2, sold = true)}],"nextCursor":null}""", query = mapOf("cursor" to "p2"))
        val json = cli.run("items", "list", "--all", "--json")
        assertEquals(0, json.code, json.stderr)
        val merged = Json.parseToJsonElement(json.stdout).jsonObject
        assertEquals(listOf(1L, 2L), merged["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long })
        assertEquals(JsonNull, merged["nextCursor"])
        val text = cli.run("items", "list", "--all")
        assertContains(text.stdout, "Jacket 1")
        assertContains(text.stdout, "Jacket 2")
        assertContains(text.stdout, "12.50 EUR")
        assertContains(text.stdout, "sold")
        assertEquals("", text.stderr)
    }

    /** Pages of [size] accounts, chained by the cursors c1, c2, ... without end. */
    private fun CliHarness.endlessAccounts(size: Int, pages: Int) {
        for (n in 0 until pages) {
            val data = (1..size).joinToString(",") { account(n * size + it) }
            on(HttpMethod.Get, "/api/public/v1/accounts", body = """{"data":[$data],"nextCursor":"c${n + 1}"}""",
                query = mapOf("cursor" to if (n == 0) null else "c$n"))
        }
    }

    @Test fun `all stops at its ceiling and says how to continue`() = runTest {
        val cli = signedIn()
        cli.endlessAccounts(size = 100, pages = 101)
        val result = cli.run("accounts", "list", "--all", "--json")
        assertEquals(0, result.code, result.stderr)
        val merged = Json.parseToJsonElement(result.stdout).jsonObject
        assertEquals(10_000, merged["data"]!!.jsonArray.size)
        assertEquals("c100", merged["nextCursor"]!!.jsonPrimitive.content)
        assertContains(result.stderr, "Stopped after 10000 elements. Continue: run the same command with --cursor c100")
        assertEquals(100, cli.requests.size)
        assertTrue(cli.requests.all { it.url.parameters["limit"] == "100" })
    }

    @Test fun `all never asks for more than the ceiling`() = runTest {
        val cli = signedIn()
        cli.endlessAccounts(size = 30, pages = 335)
        val result = cli.run("accounts", "list", "--all", "--limit", "30", "--json")
        assertEquals(0, result.code, result.stderr)
        // 333 full pages make 9,990 elements: the last request asks for the 10 left, so the cursor skips nothing.
        assertEquals(334, cli.requests.size)
        assertEquals("10", cli.requests.last().url.parameters["limit"])
    }

    @Test fun `all starts from a given cursor and refuses a repeated one`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/items", body = """{"data":[${item(2)}],"nextCursor":null}""", query = mapOf("cursor" to "p2"))
        assertEquals(0, cli.run("items", "list", "--all", "--cursor", "p2").code)
        assertEquals("p2", cli.requests.single().url.parameters["cursor"])
        cli.on(HttpMethod.Get, "/api/public/v1/items", body = """{"data":[${item(3)}],"nextCursor":"p3"}""", query = mapOf("cursor" to "p3"))
        val looping = cli.run("items", "list", "--all", "--cursor", "p3")
        assertEquals(ExitCode.FAILURE, looping.code)
        assertContains(looping.stderr, "same page cursor twice")
    }

    @Test fun `an unexpected body in JSON mode is a clean failure`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/accounts", body = "<html>gateway</html>")
        val result = cli.run("accounts", "list", "--json")
        assertEquals(ExitCode.FAILURE, result.code)
        assertEquals("The API answered with an unexpected body.\n", result.stderr)
    }

    @Test fun `filters are sent as query parameters`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/items", body = """{"data":[],"nextCursor":null}""")
        cli.on(HttpMethod.Get, "/api/public/v1/conversations", body = """{"data":[],"nextCursor":null}""")
        assertEquals("No items.\n", cli.run("items", "list", "--sold", "false", "--account", "acc_1", "--cursor", "c9").stdout)
        cli.requests.single().url.parameters.let {
            assertEquals("false", it["sold"]); assertEquals("acc_1", it["accountId"]); assertEquals("c9", it["cursor"])
        }
        assertEquals("No conversations.\n", cli.run("conversations", "list", "--unread", "true").stdout)
        assertEquals("true", cli.requests.last().url.parameters["unread"])
    }

    @Test fun `invalid paging options are usage errors`() = runTest {
        val cli = signedIn()
        assertEquals(ExitCode.USAGE, cli.run("items", "list", "--limit", "101").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "get", "not-a-number").code)
        assertTrue(cli.requests.isEmpty())
    }

    @Test fun `get prints a sheet, and a missing resource exits with code 4`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/items/7", body = item(7))
        cli.on(HttpMethod.Get, "/api/public/v1/items/8", status = 404, body = CliHarness.error("item_not_found", "No such item.", "req_8"))
        val found = cli.run("items", "get", "7")
        assertContains(found.stdout, "Name           Jacket 7")
        assertContains(found.stdout, "Selling price  12.50 EUR")
        assertContains(found.stdout, "Labels         denim")
        val missing = cli.run("items", "get", "8")
        assertEquals(ExitCode.NOT_FOUND, missing.code)
        assertEquals("error: item_not_found: No such item. (request req_8)\n", missing.stderr)
    }

    @Test fun `accounts get, listings, conversations and messages read their routes`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/accounts/acc_1", body = account(1).dropLast(1) + ""","availableBalance":{"amount":"80.00","currency":"EUR"}}""")
        cli.on(HttpMethod.Get, "/api/public/v1/items/7/listings", body = """{"data":[{"id":"lst_1","itemId":7,"accountId":"acc_1","platformId":"9001",""" +
            """"url":"https://example.com/9001","price":{"amount":"12.50","currency":"EUR"},"status":"ONLINE","publishedAt":"2026-10-01T11:00:00Z",""" +
            """"updatedAt":"2026-10-01T11:00:00Z"}],"nextCursor":null}""")
        cli.on(HttpMethod.Get, "/api/public/v1/conversations/conv_1", body = """{"id":"conv_1","accountId":"acc_1","unread":true,"pinned":false,""" +
            """"updatedAt":"2026-10-03T09:00:00Z","buyerLogin":"buyer1"}""")
        cli.on(HttpMethod.Get, "/api/public/v1/conversations/conv_1/messages", body = """{"data":[{"id":"m1","conversationId":"conv_1","author":"BUYER",""" +
            """"type":"OFFER","status":"SENT","createdAt":"2026-10-03T09:00:00Z","offer":{"price":{"amount":"10.00","currency":"EUR"},""" +
            """"originalPrice":{"amount":"12.50","currency":"EUR"},"status":"PENDING"}}],"nextCursor":null}""")
        assertContains(cli.run("accounts", "get", "acc_1").stdout, "Available balance  80.00 EUR")
        assertContains(cli.run("items", "listings", "7").stdout, "https://example.com/9001")
        assertContains(cli.run("conversations", "get", "conv_1").stdout, "Buyer    buyer1")
        assertContains(cli.run("conversations", "messages", "conv_1").stdout, "Offer 10.00 EUR")
        assertEquals("ONLINE", Json.parseToJsonElement(cli.run("items", "listings", "7", "--json").stdout)
            .jsonObject["data"]!!.jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test fun `conversations list and messages send the from and to window, and refuse a bad one`() = runTest {
        val cli = CliHarness().apply { signIn() }
        cli.on(HttpMethod.Get, "/api/public/v1/conversations", body = """{"data":[],"nextCursor":null}""")
        cli.on(HttpMethod.Get, "/api/public/v1/conversations/conv_1/messages", body = """{"data":[],"nextCursor":null}""")
        assertEquals(0, cli.run("conversations", "list", "--from", "2026-10-01T00:00:00Z", "--to", "2026-10-02T00:00:00Z").code)
        assertEquals(0, cli.run("conversations", "messages", "conv_1", "--from", "2026-10-01T00:00:00Z").code)
        cli.requests[0].url.parameters.let { assertTrue(it["from"]!!.startsWith("2026-10-01")); assertTrue(it["to"]!!.startsWith("2026-10-02")) }
        cli.requests[1].url.parameters.let { assertTrue(it["from"]!!.startsWith("2026-10-01")); assertNull(it["to"]) }
        val count = cli.requests.size
        assertEquals(ExitCode.USAGE, cli.run("conversations", "list", "--from", "2026-10-02T00:00:00Z", "--to", "2026-10-01T00:00:00Z").code)
        assertEquals(ExitCode.USAGE, cli.run("conversations", "messages", "conv_1", "--to", "yesterday").code)
        assertEquals(count, cli.requests.size)
    }

    @Test fun `conversations list sends the buyer, item and offer status filters, and the sheet shows the buyer id`() = runTest {
        val cli = CliHarness().apply { signIn() }
        cli.on(HttpMethod.Get, "/api/public/v1/conversations", body = """{"data":[],"nextCursor":null}""")
        assertEquals(0, cli.run("conversations", "list", "--buyer", "b-1", "--item", "42", "--offer-status", "PENDING").code)
        cli.requests.single().url.parameters.let {
            assertEquals("b-1", it["buyerId"]); assertEquals("42", it["itemId"]); assertEquals("PENDING", it["offerStatus"])
        }
        assertEquals(ExitCode.USAGE, cli.run("conversations", "list", "--item", "x").code)
    }
}
