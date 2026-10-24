package com.controlresell.cli

import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WebhooksCommandsTest {
    private fun signedIn() = CliHarness().apply { signIn() }

    private val endpoint = """{"id":"e-1","url":"https://example.com/hook","description":null,"eventTypes":["item.sold","order.created"],
        "payloadMode":"light","status":"enabled","apiVersion":"v1","createdAt":"2026-10-24T00:00:00Z","updatedAt":"2026-10-24T00:00:00Z"}"""

    private fun body(cli: CliHarness, index: Int = 0) =
        (cli.requests[index].body as OutgoingContent.ByteArrayContent).bytes().decodeToString().filterNot { it.isWhitespace() }

    @Test fun `webhooks create posts the url and the events with a key, and prints the secret`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Post, "/api/public/v1/webhook-endpoints", status = 201, body = """{"endpoint":$endpoint,"secret":"whsec_abc"}""")
        val result = cli.run("webhooks", "create", "--url", "https://example.com/hook", "--event", "item.sold", "--event", "order.created",
            "--idempotency-key", "k1")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "whsec_abc")
        assertContains(result.stdout, "item.sold, order.created")
        assertEquals("k1", cli.requests.single().headers["Idempotency-Key"])
        assertEquals("""{"url":"https://example.com/hook","eventTypes":["item.sold","order.created"]}""", body(cli))
    }

    @Test fun `webhooks create refuses a comma in an event type before any request`() = runTest {
        val cli = signedIn()
        val result = cli.run("webhooks", "create", "--url", "https://example.com/hook", "--event", "item.sold,order.created")
        assertEquals(ExitCode.USAGE, result.code)
        assertTrue(cli.requests.isEmpty())
    }

    @Test fun `webhooks list prints a table, and update needs a field and sends only it`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/webhook-endpoints", body = """{"data":[$endpoint],"nextCursor":null}""")
        cli.on(HttpMethod.Patch, "/api/public/v1/webhook-endpoints/e-1", body = endpoint)
        assertContains(cli.run("webhooks", "list").stdout, "https://example.com/hook")
        assertEquals(ExitCode.USAGE, cli.run("webhooks", "update", "e-1").code)
        val result = cli.run("webhooks", "update", "e-1", "--disable")
        assertEquals(0, result.code, result.stderr)
        assertEquals("""{"status":"disabled"}""", body(cli, 1))
    }

    @Test fun `webhooks delete deletes only after a yes`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Delete, "/api/public/v1/webhook-endpoints/e-1", status = 204, body = "")
        assertEquals(ExitCode.FAILURE, cli.run("webhooks", "delete", "e-1", stdin = "n").code)
        assertTrue(cli.requests.isEmpty())
        val result = cli.run("webhooks", "delete", "e-1", "--yes")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "Deleted webhook endpoint e-1.")
    }

    @Test fun `webhooks rotate-secret sends the overlap and asks before stopping the secret at once`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Post, "/api/public/v1/webhook-endpoints/e-1/rotate-secret",
            body = """{"secret":"whsec_new","previousSecretExpiresAt":"2026-10-25T00:00:00Z"}""")
        val result = cli.run("webhooks", "rotate-secret", "e-1", "--expires-in-hours", "12")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "whsec_new")
        assertEquals("""{"expiresInHours":12}""", body(cli))
        assertEquals(ExitCode.USAGE, cli.run("webhooks", "rotate-secret", "e-1", "--expires-in-hours", "0").code)
        assertEquals(1, cli.requests.size)
    }

    @Test fun `webhooks events sends the types joined by commas and prints the data`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/events", body = """{"data":[{"id":"ev1","type":"item.sold","createdAt":"2026-10-24T00:00:00Z",
            "apiVersion":"v1","sellerId":7,"data":{"itemId":42,"source":"order","object":null}}],"nextCursor":"c2"}""")
        val result = cli.run("webhooks", "events", "--type", "item.sold", "--type", "order.created", "--since", "2026-10-23T00:00:00Z")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "itemId=42")
        assertContains(result.stderr, "--cursor c2")
        cli.requests.single().url.parameters.let {
            assertEquals("item.sold,order.created", it["type"])
            assertEquals("2026-10-23T00:00:00Z", it["since"])
        }
    }

    @Test fun `webhooks deliveries, retry, recover and test call their routes`() = runTest {
        val cli = signedIn()
        val delivery = """{"id":"d1","eventId":"ev1","eventType":"item.sold","status":"failed","attempt":3,"httpStatus":500,
            "createdAt":"2026-10-24T00:00:00Z","updatedAt":"2026-10-24T00:00:00Z"}"""
        cli.on(HttpMethod.Get, "/api/public/v1/webhook-endpoints/e-1/deliveries", body = """{"data":[$delivery],"nextCursor":null}""")
        cli.on(HttpMethod.Post, "/api/public/v1/webhook-endpoints/e-1/deliveries/d1/retry", status = 202, body = delivery)
        cli.on(HttpMethod.Post, "/api/public/v1/webhook-endpoints/e-1/recover", status = 202, body = """{"count":2,"hasMore":false}""")
        cli.on(HttpMethod.Post, "/api/public/v1/webhook-endpoints/e-1/test", body = """{"success":false,"statusCode":500,"durationMs":40,"error":"http_500"}""")
        assertContains(cli.run("webhooks", "deliveries", "e-1", "--status", "failed").stdout, "item.sold")
        assertEquals("failed", cli.requests[0].url.parameters["status"])
        assertEquals(0, cli.run("webhooks", "retry", "e-1", "d1").code)
        assertContains(cli.run("webhooks", "recover", "e-1", "--since", "2026-10-23T00:00:00Z").stdout, "Queued 2 deliveries again.")
        assertContains(cli.run("webhooks", "test", "e-1").stdout, "http_500")
        assertEquals(ExitCode.USAGE, cli.run("webhooks", "recover", "e-1", "--since", "yesterday").code)
        assertEquals(4, cli.requests.size)
    }

    @Test fun `webhooks event-types prints each type with its scope, and a missing endpoint exits with 4`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/webhook-event-types",
            body = """{"data":[{"type":"item.sold","scope":"items:read"},{"type":"webhook.test","scope":null}]}""")
        cli.on(HttpMethod.Get, "/api/public/v1/webhook-endpoints/e-9", status = 404,
            body = CliHarness.error("webhook_endpoint_not_found", "No such endpoint."))
        val types = cli.run("webhooks", "event-types")
        assertEquals(0, types.code, types.stderr)
        assertContains(types.stdout, "items:read")
        assertEquals(ExitCode.NOT_FOUND, cli.run("webhooks", "get", "e-9").code)
    }
}
