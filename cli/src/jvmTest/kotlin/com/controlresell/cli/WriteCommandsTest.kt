package com.controlresell.cli

import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WriteCommandsTest {
    private val action = """{"itemId":7,"action":"PUBLISH","accountIds":["acc_1"],"scheduledAt":"2026-10-21T10:00:00Z"}"""
    private val message = """{"id":"m_1","conversationId":"c_1","author":"SELLER","type":"TEXT","status":"SENT","body":"Hello!","createdAt":"2026-10-21T10:00:00Z"}"""

    private fun signedIn() = CliHarness().apply { signIn() }
    private fun HttpRequestData.json() = Json.parseToJsonElement((body as TextContent).text).jsonObject

    @Test fun `publish sends the accounts with an idempotency key and prints what was scheduled`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Post, "/api/public/v1/items/7/publish", status = 202, body = action)
        val result = cli.run("items", "publish", "7", "--account", "acc_1", "--account", "acc_2")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "PUBLISH")
        assertContains(result.stdout, "acc_1")
        val request = cli.requests.single()
        assertEquals(listOf("acc_1", "acc_2"), request.json()["accountIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(request.headers["Idempotency-Key"]!!.isNotBlank())
    }

    @Test fun `publish needs an account`() = runTest {
        val cli = signedIn()
        val result = cli.run("items", "publish", "7")
        assertEquals(ExitCode.USAGE, result.code)
        assertTrue(cli.requests.isEmpty())
    }

    @Test fun `an idempotency key given is the one sent, and json prints the response as is`() = runTest {
        val cli = signedIn()
        val raw = """$action"""
        cli.on(HttpMethod.Post, "/api/public/v1/items/7/republish", status = 202, body = raw)
        val result = cli.run("items", "republish", "7", "--idempotency-key", "my-key", "--json")
        assertEquals(0, result.code, result.stderr)
        assertEquals("$raw\n", result.stdout)
        assertEquals("my-key", cli.requests.single().headers["Idempotency-Key"])
    }

    @Test fun `republish sends only what was asked`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Post, "/api/public/v1/items/7/republish", status = 202, body = action)
        cli.run("items", "republish", "7", "--account", "acc_1", "--price-variation", "-5", "--drop-type", "percentage", "--drop-value", "10")
        val sent = cli.requests.single().json()
        assertEquals("-5", sent["priceVariation"]!!.jsonPrimitive.content)
        assertEquals("PERCENTAGE", sent["priceDrop"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("allowIfDeleted" in sent)
        assertEquals(listOf("acc_1"), sent["accountIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        val lone = cli.run("items", "republish", "7", "--drop-type", "ABSOLUTE")
        assertEquals(ExitCode.USAGE, lone.code)
        assertEquals(1, cli.requests.size, "the lone --drop-type sent nothing")
    }

    @Test fun `price-drop sends the drop to the price-drop route`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Post, "/api/public/v1/items/7/price-drop", status = 202, body = action)
        val result = cli.run("items", "price-drop", "7", "--type", "absolute", "--value", "3", "--round-type", "up", "--round-step", "0.5")
        assertEquals(0, result.code, result.stderr)
        val sent = cli.requests.single().json()
        assertEquals("ABSOLUTE", sent["type"]!!.jsonPrimitive.content)
        assertEquals("3", sent["value"]!!.jsonPrimitive.content)
        assertEquals("UP", sent["roundType"]!!.jsonPrimitive.content)
    }

    @Test fun `delete deletes only after a yes`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Delete, "/api/public/v1/items/7", status = 204, body = "")
        val result = cli.run("items", "delete", "7", stdin = "y")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "Deleted item 7.")
        assertContains(result.stderr, "Delete item 7")
        assertEquals(HttpMethod.Delete, cli.requests.single().method)
    }

    @Test fun `delete does nothing on a no, or with nothing to answer`() = runTest {
        val no = signedIn()
        no.on(HttpMethod.Delete, "/api/public/v1/items/7", status = 204, body = "")
        assertEquals(ExitCode.FAILURE, no.run("items", "delete", "7", stdin = "n").code)
        assertTrue(no.requests.isEmpty())
        val silent = signedIn()
        val result = silent.run("items", "delete", "7")
        assertEquals(ExitCode.USAGE, result.code)
        assertContains(result.stderr, "--yes")
        assertTrue(silent.requests.isEmpty())
    }

    @Test fun `delete --yes skips the question, and a refusal exits with 7`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Delete, "/api/public/v1/items/7", status = 409, body = CliHarness.error("item_still_listed", "Item still listed"))
        val result = cli.run("items", "delete", "7", "--yes")
        assertEquals(ExitCode.REFUSED, result.code)
        assertContains(result.stderr, "item_still_listed")
        assertEquals(1, cli.requests.size)
    }

    @Test fun `send posts the text and prints the message`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Post, "/api/public/v1/conversations/c_1/messages", status = 201, body = message)
        val result = cli.run("conversations", "send", "c_1", "--text", "Hello!")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "Hello!")
        assertEquals("Hello!", cli.requests.single().json()["text"]!!.jsonPrimitive.content)
    }

    @Test fun `offer posts the price in its currency`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Post, "/api/public/v1/conversations/c_1/offers", status = 201, body = message)
        val result = cli.run("conversations", "offer", "c_1", "--price", "12.50", "--currency", "eur", "--transaction", "t_1")
        assertEquals(0, result.code, result.stderr)
        val sent = cli.requests.single().json()
        assertEquals("12.50", sent["price"]!!.jsonObject["amount"]!!.jsonPrimitive.content)
        assertEquals("EUR", sent["price"]!!.jsonObject["currency"]!!.jsonPrimitive.content)
        assertEquals("t_1", sent["transactionId"]!!.jsonPrimitive.content)
    }

    @Test fun `typos are refused before anything is sent`() = runTest {
        val cli = signedIn()
        assertEquals(ExitCode.USAGE, cli.run("items", "price-drop", "7", "--type", "pct", "--value", "3").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "price-drop", "7", "--type", "ABSOLUTE", "--value", "abc").code)
        assertEquals(ExitCode.USAGE, cli.run("conversations", "offer", "c_1", "--price", "12,50", "--currency", "EUR").code)
        assertEquals(ExitCode.USAGE, cli.run("conversations", "offer", "c_1", "--price", "0", "--currency", "EUR").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "price-drop", "7", "--type", "ABSOLUTE", "--value", "0").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "price-drop", "7", "--type", "PERCENTAGE", "--value", "150").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "republish", "7", "--price-variation", "abc").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "publish", "7", "--account", "").code)
        assertEquals(ExitCode.USAGE, cli.run("conversations", "send", "c_1", "--text", " ").code)
        assertTrue(cli.requests.isEmpty())
    }

    @Test fun `a missing write scope exits with 3`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Post, "/api/public/v1/conversations/c_1/messages", status = 403, body = CliHarness.error("insufficient_scope", "Missing scope"))
        assertEquals(ExitCode.AUTHENTICATION, cli.run("conversations", "send", "c_1", "--text", "x").code)
    }

    private val created = """{"id":7,"name":"Jacket","photos":[],"colorIds":[],"labels":["denim"],"sold":false,"createdAt":"2026-10-21T10:00:00Z","updatedAt":"2026-10-21T10:00:00Z","estimatedPrice":{"amount":"12.50","currency":"EUR"}}"""

    @Test fun `items create posts the fields with an idempotency key and prints the item`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Post, "/api/public/v1/items", status = 201, body = created)
        val result = cli.run("items", "create", "--name", "Jacket", "--estimated-price", "12.50", "--label", "denim", "--label", "vintage",
            "--color-id", "3", "--sold", "false", "--purchase-date", "2026-10-01T00:00:00Z", "--idempotency-key", "my-key")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "Jacket")
        val request = cli.requests.single()
        assertEquals("my-key", request.headers["Idempotency-Key"])
        val sent = request.json()
        assertEquals("Jacket", sent["name"]!!.jsonPrimitive.content)
        assertEquals("12.50", sent["estimatedPrice"]!!.jsonObject["amount"]!!.jsonPrimitive.content)
        assertEquals("EUR", sent["estimatedPrice"]!!.jsonObject["currency"]!!.jsonPrimitive.content)
        assertEquals(listOf("denim", "vintage"), sent["labels"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(3L), sent["colorIds"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertFalse(sent["sold"]!!.jsonPrimitive.boolean)
        assertFalse("sku" in sent)
    }

    @Test fun `items create json prints the response as is, with a random key by default`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Post, "/api/public/v1/items", status = 201, body = created)
        val result = cli.run("items", "create", "--name", "Jacket", "--json")
        assertEquals("$created\n", result.stdout)
        assertTrue(cli.requests.single().headers["Idempotency-Key"]!!.isNotBlank())
    }

    @Test fun `items update patches only the given fields`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Patch, "/api/public/v1/items/7", body = created)
        val result = cli.run("items", "update", "7", "--selling-price", "20", "--sold", "true", "--currency", "eur")
        assertEquals(0, result.code, result.stderr)
        val request = cli.requests.single()
        assertEquals(null, request.headers["Idempotency-Key"])
        val sent = request.json()
        assertEquals(setOf("sellingPrice", "sold"), sent.keys)
        assertEquals("EUR", sent["sellingPrice"]!!.jsonObject["currency"]!!.jsonPrimitive.content)
    }

    @Test fun `items create and update refuse typos before anything is sent`() = runTest {
        val cli = signedIn()
        assertEquals(ExitCode.USAGE, cli.run("items", "create").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "create", "--name", " ").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "create", "--name", "x", "--estimated-price", "12,50").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "create", "--name", "x", "--estimated-price", "1", "--currency", "euro").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "create", "--name", "x", "--selling-date", "yesterday").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "create", "--name", "x", "--currency", "euro").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "create", "--name", "x", "--label", "").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "update", "7").code)
        assertEquals(ExitCode.USAGE, cli.run("items", "update", "7", "--name", "").code)
        assertTrue(cli.requests.isEmpty())
    }

    @Test fun `items update of a missing item exits with 4 and a missing scope with 3`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Patch, "/api/public/v1/items/8", status = 404, body = CliHarness.error("item_not_found", "No such item."))
        cli.on(HttpMethod.Post, "/api/public/v1/items", status = 403, body = CliHarness.error("insufficient_scope", "Missing scope"))
        assertEquals(ExitCode.NOT_FOUND, cli.run("items", "update", "8", "--name", "x").code)
        assertEquals(ExitCode.AUTHENTICATION, cli.run("items", "create", "--name", "x").code)
    }
}
