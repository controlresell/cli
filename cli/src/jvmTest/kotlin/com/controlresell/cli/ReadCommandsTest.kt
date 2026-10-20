package com.controlresell.cli

import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ReadCommandsTest {
    private fun account(n: Int) = """{"id":"acc_$n","platform":"vinted","name":"Shop $n","status":"CONNECTED"}"""
    private fun item(n: Int, sold: Boolean = false) = """{"id":$n,"name":"Jacket $n","photos":[],"colorIds":[],"labels":["denim"],"sold":$sold,""" +
        """"createdAt":"2026-10-01T10:00:00Z","updatedAt":"2026-10-02T10:00:00Z","sellingPrice":{"amount":"12.50","currency":"EUR"}}"""

    private fun signedIn() = CliHarness().apply { signIn() }

    @Test fun `a list prints a table and gives the next cursor on stderr`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/accounts", body = """{"data":[${account(1)},${account(2)}],"nextCursor":"c2"}""")
        val result = cli.run("accounts", "list", "--limit", "2")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "Shop 1")
        assertContains(result.stdout, "acc_2")
        assertEquals("Next page: cr accounts list --cursor c2\n", result.stderr)
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

    @Test fun `all stops at its ceiling and says how to continue`() = runTest {
        val cli = signedIn()
        val full = (1..100).joinToString(",", prefix = """{"data":[""", postfix = """],"nextCursor":"more"}""") { account(it) }
        cli.on(HttpMethod.Get, "/api/public/v1/accounts", body = full)
        val result = cli.run("accounts", "list", "--all", "--json")
        assertEquals(0, result.code)
        assertEquals(10_000, Json.parseToJsonElement(result.stdout).jsonObject["data"]!!.jsonArray.size)
        assertContains(result.stderr, "Stopped after 10000 elements. Continue with: cr accounts list --cursor more")
        assertEquals(100, cli.requests.size)
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
        assertEquals(ExitCode.USAGE, cli.run("items", "list", "--all", "--cursor", "x").code)
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
}
