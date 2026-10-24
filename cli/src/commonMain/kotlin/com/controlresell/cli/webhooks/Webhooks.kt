package com.controlresell.cli.webhooks

import com.controlresell.cli.CliEnvironment
import com.controlresell.cli.output.*
import com.controlresell.sdk.generated.models.WebhookEndpoint
import com.controlresell.sdk.generated.models.WebhookEndpointCreateRequest
import com.controlresell.sdk.generated.models.WebhookEndpointUpdateRequest
import com.controlresell.sdk.generated.models.WebhookRecoverRequest
import com.controlresell.sdk.generated.models.WebhookSecret
import com.controlresell.sdk.generated.models.WebhookSecretRotateRequest
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class Webhooks(environment: CliEnvironment) : SuspendingNoOpCliktCommand(name = "webhooks") {
    override fun help(context: Context) =
        "The webhook endpoints that receive your events, their secrets and deliveries, and the events of the last 7 days."
    init {
        subcommands(ListEndpoints(environment), GetEndpoint(environment), CreateEndpoint(environment), UpdateEndpoint(environment),
            DeleteEndpoint(environment), ReadSecret(environment), RotateSecret(environment), TestEndpoint(environment),
            ListDeliveries(environment), RetryDelivery(environment), RecoverDeliveries(environment), ListEvents(environment),
            ListEventTypes(environment))
    }
}

private class ListEndpoints(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "list") {
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List your webhook endpoints (5 at most), without their secrets. Needs the webhooks:read scope."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.webhooks.listWebhookEndpoints()
        if (json) echo(response.response.bodyAsText().trim())
        else echo(terminal.table(listOf("ID", "URL", "Status", "Events", "Payload"), response.body().`data`.map {
            listOf(it.id, short(it.url, 60), it.status, it.eventTypes.joinToString(","), it.payloadMode)
        }, empty = "No webhook endpoints."))
    }
}

private class GetEndpoint(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "get") {
    private val id by argument(help = "The endpoint's identifier.")
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Show one webhook endpoint, its status and why it was disabled. Needs the webhooks:read scope."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.webhooks.getWebhookEndpoint(id)
        if (json) echo(response.response.bodyAsText().trim()) else echo(endpoint(response.body()))
    }
}

private class CreateEndpoint(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "create") {
    private val url by option("--url", help = "The https address that receives the events.").required()
    private val events by option("--event", help = "An event type to receive, such as item.sold (cr webhooks event-types lists them). Repeat it for several.").multiple(required = true)
    private val description by option("--description", help = "A note for you.")
    private val payload by option("--payload", help = "light (the identifiers and facts, the default) or full (the whole object too).").choice("light", "full")
    private val write by WriteOptions()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) =
        "Create a webhook endpoint and print its signing secret. Needs the webhooks:write scope and the read scope of each event type."
    override suspend fun run() {
        checkEvents(events)
        environment.sdk().use { client ->
            val response = client.webhooks.createWebhookEndpoint(write.key(),
                WebhookEndpointCreateRequest(url = url, eventTypes = events, description = description, payloadMode = payload))
            if (json) echo(response.response.bodyAsText().trim())
            else response.body().let { echo(endpoint(it.endpoint) + "\n" + sheet("Secret" to it.secret)) }
        }
        echo("Keep the secret to verify the signatures; cr webhooks secret reads it again.", err = true)
    }
}

private class UpdateEndpoint(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "update") {
    private val id by argument(help = "The endpoint's identifier.")
    private val url by option("--url", help = "A new https address.")
    private val events by option("--event", help = "The event types to receive; the list you give replaces the whole list. Repeat it for several.").multiple()
    private val description by option("--description", help = "A new note.")
    private val payload by option("--payload", help = "light or full.").choice("light", "full")
    private val status by option(help = "Enable the endpoint again (its health is reset) or disable it.").switch("--enable" to "enabled", "--disable" to "disabled")
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Change fields of a webhook endpoint; the fields you leave out keep their value. Needs the webhooks:write scope."
    override suspend fun run() {
        if (url == null && events.isEmpty() && description == null && payload == null && status == null)
            throw UsageError("Give at least one field to change, such as --url, --event, --enable or --disable.")
        checkEvents(events)
        environment.sdk().use { client ->
            val response = client.webhooks.updateWebhookEndpoint(id, WebhookEndpointUpdateRequest(url = url,
                description = description, eventTypes = events.takeIf { it.isNotEmpty() }, payloadMode = payload, status = status))
            if (json) echo(response.response.bodyAsText().trim()) else echo(endpoint(response.body()))
        }
    }
}

private class DeleteEndpoint(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "delete") {
    private val id by argument(help = "The endpoint's identifier.")
    private val yes by option("--yes", "-y", help = "Delete without asking for confirmation.").flag()
    override fun help(context: Context) = "Delete a webhook endpoint; its pending deliveries are dropped. Needs the webhooks:write scope."
    override suspend fun run() {
        confirm("Delete webhook endpoint $id? Its pending deliveries are dropped.", yes)
        environment.sdk().use { client -> client.webhooks.deleteWebhookEndpoint(id) }
        echo("Deleted webhook endpoint $id.")
    }
}

private class ReadSecret(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "secret") {
    private val id by argument(help = "The endpoint's identifier.")
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) =
        "Print the current signing secret of a webhook endpoint. Needs the webhooks:write scope and every scope of the endpoint."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.webhooks.getWebhookSecret(id)
        if (json) echo(response.response.bodyAsText().trim()) else echo(secret(response.body()))
    }
}

private class RotateSecret(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "rotate-secret") {
    private val id by argument(help = "The endpoint's identifier.")
    private val expiresInHours by option("--expires-in-hours", help = "How long the previous secret still signs, 0 to 72 hours (24 by default; 0 stops it at once).").long().restrictTo(0L..72L)
    private val yes by option("--yes", "-y", help = "With --expires-in-hours 0, rotate without asking for confirmation.").flag()
    private val write by WriteOptions()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) =
        "Give a webhook endpoint a new signing secret and print it. Needs the webhooks:write scope and every scope of the endpoint."
    override suspend fun run() {
        if (expiresInHours == 0L) confirm("Stop the current secret of $id at once? Deliveries signed with it fail your checks.", yes)
        environment.sdk().use { client ->
            val response = client.webhooks.rotateWebhookSecret(id, write.key(), WebhookSecretRotateRequest(expiresInHours = expiresInHours))
            if (json) echo(response.response.bodyAsText().trim()) else echo(secret(response.body()))
        }
    }
}

private class TestEndpoint(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "test") {
    private val id by argument(help = "The endpoint's identifier.")
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Send a webhook.test event to an endpoint now and show how it answered. Needs the webhooks:write scope."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.webhooks.testWebhookEndpoint(id)
        if (json) echo(response.response.bodyAsText().trim())
        else response.body().let {
            echo(sheet("Success" to it.success.toString(), "HTTP status" to it.statusCode?.toString(),
                "Duration" to "${it.durationMs} ms", "Error" to it.error))
        }
    }
}

private class ListDeliveries(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "deliveries") {
    private val id by argument(help = "The endpoint's identifier.")
    private val status by option("--status", help = "Only the deliveries in this status: pending, delivered or failed.")
    private val type by option("--type", help = "Only the deliveries of this event type.")
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the deliveries of an endpoint over the last 7 days, most recent first. Needs the webhooks:read scope."
    override suspend fun run() = environment.sdk().use { client ->
        printList(paging, json, fetch = { cursor, limit ->
            fetchPage(client.webhooks.listWebhookDeliveries(id = id, status = status, type = type, limit = limit, cursor = cursor),
                { it.`data` }, { it.nextCursor }, json)
        }, render = { deliveries ->
            terminal.table(listOf("ID", "Event", "Status", "Attempt", "HTTP", "Error", "Created"), deliveries.map {
                listOf(it.id, it.eventType, it.status, it.attempt.toString(), it.httpStatus?.toString(), short(it.error), date(it.createdAt))
            }, empty = "No deliveries.")
        })
    }
}

private class RetryDelivery(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "retry") {
    private val id by argument(help = "The endpoint's identifier.")
    private val deliveryId by argument(help = "The delivery's identifier (cr webhooks deliveries lists them).")
    private val write by WriteOptions()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Send one event to the endpoint again, with the same webhook-id. Needs the webhooks:write scope."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.webhooks.retryWebhookDelivery(id, deliveryId, write.key())
        if (json) echo(response.response.bodyAsText().trim())
        else response.body().let { echo(sheet("ID" to it.id, "Event" to it.eventType, "Status" to it.status, "Attempt" to it.attempt.toString())) }
    }
}

private class RecoverDeliveries(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "recover") {
    private val id by argument(help = "The endpoint's identifier.")
    private val since by option("--since", help = "Send again the failed deliveries since this instant, at most 7 days ago, ISO-8601 with its offset.").required()
    private val write by WriteOptions()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) =
        "Send again the failed deliveries of an endpoint, 1,000 at most per call. Needs the webhooks:write scope."
    override suspend fun run() {
        val start = parseInstant("--since", since)
        environment.sdk().use { client ->
            val response = client.webhooks.recoverWebhookDeliveries(id, write.key(), WebhookRecoverRequest(since = start))
            if (json) echo(response.response.bodyAsText().trim())
            else response.body().let {
                echo("Queued ${it.count} deliveries again.")
                if (it.hasMore) echo("More remain: run the same command again.", err = true)
            }
        }
    }
}

private class ListEvents(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "events") {
    private val since by option("--since", help = "Only the events created at or after this instant, at most 7 days ago (the default), ISO-8601 with its offset.")
    private val types by option("--type", help = "Only this event type. Repeat it for several.").multiple()
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) =
        "List the events of the last 7 days, oldest first, to catch up what your endpoint missed. Needs the read scope of each event type."
    override suspend fun run() {
        val start = since?.let { parseInstant("--since", it) }
        checkEvents(types)
        environment.sdk().use { client ->
            printList(paging, json, fetch = { cursor, limit ->
                fetchPage(client.webhooks.listWebhookEvents(since = start, type = types.takeIf { it.isNotEmpty() }?.joinToString(","),
                    limit = limit, cursor = cursor), { it.`data` }, { it.nextCursor }, json)
            }, render = { events ->
                terminal.table(listOf("ID", "Type", "Created", "Data"), events.map { event ->
                    listOf(event.id, event.type, date(event.createdAt), short(event.`data`.entries.filter { it.key != "object" }
                        .joinToString(" ") { (key, value) -> "$key=${(value as? JsonPrimitive)?.contentOrNull ?: value}" }, 60))
                }, empty = "No events.")
            })
        }
    }
}

private class ListEventTypes(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "event-types") {
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the event types an endpoint can receive and the scope each one needs."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.webhooks.listWebhookEventTypes()
        if (json) echo(response.response.bodyAsText().trim())
        else echo(terminal.table(listOf("Type", "Scope"), response.body().`data`.map { listOf(it.type, it.scope) }, empty = "No event types."))
    }
}

private fun checkEvents(events: List<String>) {
    if (events.any { it.isBlank() || ',' in it }) throw UsageError("Give one event type per --event or --type, such as item.sold.")
}

internal fun endpoint(endpoint: WebhookEndpoint) = sheet(
    "ID" to endpoint.id,
    "URL" to endpoint.url,
    "Description" to endpoint.description,
    "Events" to endpoint.eventTypes.joinToString(", "),
    "Payload" to endpoint.payloadMode,
    "Status" to endpoint.status,
    "Disabled because" to endpoint.disabledReason,
    "API version" to endpoint.apiVersion,
    "Last success" to date(endpoint.lastSuccessAt),
    "Created" to date(endpoint.createdAt),
    "Updated" to date(endpoint.updatedAt),
)

private fun secret(secret: WebhookSecret) = sheet(
    "Secret" to secret.secret,
    "Previous secret valid until" to date(secret.previousSecretExpiresAt),
)
