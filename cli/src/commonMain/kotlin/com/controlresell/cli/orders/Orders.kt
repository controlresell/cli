package com.controlresell.cli.orders

import com.controlresell.cli.CliEnvironment
import com.controlresell.cli.output.*
import com.controlresell.sdk.generated.models.Order
import com.controlresell.sdk.generated.models.OrderLabel
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import io.ktor.client.statement.bodyAsText

internal class Orders(environment: CliEnvironment) : SuspendingNoOpCliktCommand(name = "orders") {
    override fun help(context: Context) = "The seller's sales and purchases."
    init {
        subcommands(ListOrders(environment), GetOrder(environment), OrderLabels(environment), LabelsToShip(environment))
    }
}

private val BUCKETS = setOf("to_send", "to_pickup")

private class ListOrders(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "list") {
    private val bucket by option("--bucket", help = "to_send: the sales to ship; to_pickup: the purchases waiting at a pickup point. Default: every order.")
    private val platform by option("--platform", help = "Only the orders of your accounts on this marketplace, such as VINTED, SHOPIFY or EBAY.")
    private val account by option("--account", help = "Only the orders of this marketplace account.")
    private val from by option("--from", help = "Only the orders dated at or after this instant (inclusive), ISO-8601 with its offset, such as 2026-10-01T00:00:00Z.")
    private val to by option("--to", help = "Only the orders dated before this instant (exclusive), ISO-8601 with its offset.")
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the orders, most recent first. Needs the orders:read scope."
    override suspend fun run() {
        if (bucket != null && bucket !in BUCKETS) throw UsageError("--bucket must be to_send or to_pickup.")
        val start = from?.let { parseInstant("--from", it) }
        val end = to?.let { parseInstant("--to", it) }
        if (start != null && end != null && end <= start) throw UsageError("--to must be after --from.")
        environment.sdk().use { client ->
            printList(paging, json, fetch = { cursor, limit ->
                fetchPage(client.orders.listOrders(limit = limit, cursor = cursor, bucket = bucket, platform = platform,
                    accountId = account, from = start, to = end), { it.`data` }, { it.nextCursor }, json)
            }, render = { orders ->
                terminal.table(listOf("ID", "Account", "Type", "Status", "Price", "Prepared", "Date"), orders.map {
                    listOf(it.id, it.accountId, it.type, it.status, money(it.price), if (it.prepared) "yes" else "no", date(it.date))
                }, empty = "No orders.")
            })
        }
    }
}

private class GetOrder(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "get") {
    private val id by argument(help = "The order's identifier.")
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Show one order. Needs the orders:read scope."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.orders.getOrder(id)
        if (json) echo(response.response.bodyAsText().trim()) else echo(order(response.body()))
    }
}

private class OrderLabels(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "labels") {
    private val id by argument(help = "The order's identifier.")
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) =
        "Show the shipping labels of an order. The links are signed and expire: run the command again rather than keeping them. Needs the orders:read scope."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.orders.listOrderLabels(id)
        if (json) { echo(response.response.bodyAsText().trim()); return@use }
        echo(terminal.table(listOf("ID", "Carrier", "Created", "URL"), response.body().`data`.map { label: OrderLabel ->
            listOf(label.id, label.carrier, date(label.createdAt), label.url)
        }, empty = "This order has no label."))
    }
}

private class LabelsToShip(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "labels-to-ship") {
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) =
        "List the shipping labels still to ship over all your orders, the oldest order first. The links are signed and expire: run the command again rather than keeping them. Needs the orders:read scope."
    override suspend fun run() = environment.sdk().use { client ->
        printList(paging, json, fetch = { cursor, limit ->
            fetchPage(client.orders.listLabelsToShip(limit = limit, cursor = cursor), { it.`data` }, { it.nextCursor }, json)
        }, render = { labels ->
            terminal.table(listOf("ID", "Order", "Carrier", "Created", "URL"), labels.map { label: OrderLabel ->
                listOf(label.id, label.orderId, label.carrier, date(label.createdAt), label.url)
            }, empty = "No label to ship.")
        })
    }
}

internal fun order(order: Order) = sheet(
    "ID" to order.id,
    "Account" to order.accountId,
    "Type" to order.type,
    "Status" to order.status,
    "Platform order" to order.platformOrderId,
    "Price" to money(order.price),
    "Platform fee" to money(order.platformFee),
    "Shipping fee" to money(order.shippingFee),
    "Shipping VAT" to money(order.shippingVat),
    "VAT" to money(order.vatAmount),
    "Total" to money(order.totalAmount),
    "Refund" to money(order.refundAmount),
    "Prepared" to if (order.prepared) "yes" else "no",
    "Items" to order.items.joinToString(", ") { it.itemId?.toString() ?: "listing ${it.listingId}" },
    "Conversation" to order.conversationId,
    "Transaction" to order.transactionId,
    "Date" to date(order.date),
)
