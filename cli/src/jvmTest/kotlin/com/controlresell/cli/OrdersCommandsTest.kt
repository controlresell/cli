package com.controlresell.cli

import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class OrdersCommandsTest {
    private fun order(n: Int) = """{"id":"o_$n","accountId":"acc_1","type":"SALE","status":"PAID","platformOrderId":"p$n",""" +
        """"price":{"amount":"12.50","currency":"EUR"},"prepared":false,"date":"2026-10-21T10:00:00Z","items":[{"listingId":"l_1","itemId":7}]}"""
    private val labels = """{"data":[{"id":"lb_1","orderId":"o_1","carrier":"Colissimo","url":"https://labels.example/lb_1.pdf","createdAt":"2026-10-21T11:00:00Z"}],"nextCursor":null}"""

    private fun signedIn() = CliHarness().apply { signIn() }

    @Test fun `orders list prints a table, sends the filters and gives the next cursor`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/orders", body = """{"data":[${order(1)},${order(2)}],"nextCursor":"c2"}""")
        val result = cli.run("orders", "list", "--bucket", "to_send", "--platform", "VINTED", "--account", "acc_1",
            "--from", "2026-10-01T00:00:00Z", "--to", "2026-11-01T00:00:00Z", "--limit", "2", "--cursor", "c1")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "o_1")
        assertContains(result.stdout, "12.50 EUR")
        assertEquals("Next page: run the same command with --cursor c2\n", result.stderr)
        cli.requests.single().url.parameters.let {
            assertEquals("to_send", it["bucket"]); assertEquals("VINTED", it["platform"]); assertEquals("acc_1", it["accountId"])
            assertEquals("2", it["limit"]); assertEquals("c1", it["cursor"])
            assertTrue(it["from"]!!.startsWith("2026-10-01")); assertTrue(it["to"]!!.startsWith("2026-11-01"))
        }
    }

    @Test fun `orders list --all follows the cursors with the same filters`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/orders", body = """{"data":[${order(1)}],"nextCursor":"p2"}""", query = mapOf("cursor" to null))
        cli.on(HttpMethod.Get, "/api/public/v1/orders", body = """{"data":[${order(2)}],"nextCursor":null}""", query = mapOf("cursor" to "p2"))
        val json = cli.run("orders", "list", "--all", "--bucket", "to_pickup", "--json")
        assertEquals(0, json.code, json.stderr)
        val merged = Json.parseToJsonElement(json.stdout).jsonObject
        assertEquals(listOf("o_1", "o_2"), merged["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(JsonNull, merged["nextCursor"])
        assertTrue(cli.requests.all { it.url.parameters["bucket"] == "to_pickup" })
        assertEquals("100", cli.requests.first().url.parameters["limit"])
    }

    @Test fun `orders list json prints the page as the API answered it, and an empty list says so`() = runTest {
        val cli = signedIn()
        val page = """{"data":[${order(1)}],"nextCursor":null,"futureField":true}"""
        cli.on(HttpMethod.Get, "/api/public/v1/orders", body = page)
        assertEquals("$page\n", cli.run("orders", "list", "--json").stdout)
        val empty = signedIn()
        empty.on(HttpMethod.Get, "/api/public/v1/orders", body = """{"data":[],"nextCursor":null}""")
        assertEquals("No orders.\n", empty.run("orders", "list").stdout)
    }

    @Test fun `orders list refuses a typo before anything is sent`() = runTest {
        val cli = signedIn()
        assertEquals(ExitCode.USAGE, cli.run("orders", "list", "--bucket", "shipped").code)
        assertEquals(ExitCode.USAGE, cli.run("orders", "list", "--from", "yesterday").code)
        assertEquals(ExitCode.USAGE, cli.run("orders", "list", "--from", "2026-10-02T00:00:00Z", "--to", "2026-10-01T00:00:00Z").code)
        assertEquals(ExitCode.USAGE, cli.run("orders", "list", "--limit", "101").code)
        assertTrue(cli.requests.isEmpty())
    }

    @Test fun `orders get prints a sheet, and a missing order exits with 4`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/orders/o_1", body = order(1))
        cli.on(HttpMethod.Get, "/api/public/v1/orders/o_2", status = 404, body = CliHarness.error("order_not_found", "No such order."))
        val result = cli.run("orders", "get", "o_1")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "Platform order")
        assertContains(result.stdout, "p1")
        assertEquals(ExitCode.NOT_FOUND, cli.run("orders", "get", "o_2").code)
    }

    @Test fun `orders labels prints the labels, or says there is none`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/orders/o_1/labels", body = labels)
        cli.on(HttpMethod.Get, "/api/public/v1/orders/o_2/labels", body = """{"data":[],"nextCursor":null}""")
        val result = cli.run("orders", "labels", "o_1")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "Colissimo")
        assertContains(result.stdout, "https://labels.example/lb_1.pdf")
        assertEquals("This order has no label.\n", cli.run("orders", "labels", "o_2").stdout)
        assertEquals("$labels\n", cli.run("orders", "labels", "o_1", "--json").stdout)
    }

    @Test fun `orders labels-to-ship lists the labels of every order and follows the cursor with --all`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/orders/labels", body = labels.replace("null}", "\"c2\"}"), query = mapOf("cursor" to null))
        cli.on(HttpMethod.Get, "/api/public/v1/orders/labels", body = labels.replace("lb_1", "lb_2"), query = mapOf("cursor" to "c2"))
        val table = cli.run("orders", "labels-to-ship", "--limit", "1")
        assertEquals(0, table.code, table.stderr)
        assertContains(table.stdout, "lb_1")
        assertContains(table.stdout, "o_1")
        assertEquals("Next page: run the same command with --cursor c2\n", table.stderr)
        val all = Json.parseToJsonElement(cli.run("orders", "labels-to-ship", "--all", "--json").stdout).jsonObject
        assertEquals(listOf("lb_1", "lb_2"), all["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test fun `a missing orders scope exits with 3`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/orders", status = 403, body = CliHarness.error("insufficient_scope", "Missing scope"))
        assertEquals(ExitCode.AUTHENTICATION, cli.run("orders", "list").code)
    }
}
