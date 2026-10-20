package com.controlresell.cli.items

import com.controlresell.cli.CliEnvironment
import com.controlresell.cli.output.*
import com.controlresell.sdk.generated.models.Item
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.long
import io.ktor.client.statement.bodyAsText

internal class Items(environment: CliEnvironment) : SuspendingNoOpCliktCommand(name = "items") {
    override fun help(context: Context) = "The items of the seller's stock."
    init { subcommands(ListItems(environment), GetItem(environment), ItemListings(environment)) }
}

private class ListItems(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "list") {
    private val sold by option("--sold", help = "true: only sold items; false: only unsold ones.").boolean()
    private val account by option("--account", help = "Only the items listed on this marketplace account.")
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the items, most recently created first."
    override suspend fun run() = environment.sdk().use { client ->
        printList(paging, json, fetch = { cursor, limit ->
            fetchPage(client.items.listItems(limit = limit, cursor = cursor, sold = sold, accountId = account),
                { it.`data` }, { it.nextCursor }, json)
        }, render = { items ->
            terminal.table(listOf("ID", "Name", "Status", "Price", "Created"), items.map {
                listOf(it.id.toString(), short(it.name), if (it.sold) "sold" else "unsold", money(it.sellingPrice ?: it.estimatedPrice), date(it.createdAt))
            }, empty = "No items.")
        })
    }
}

private class GetItem(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "get") {
    private val id by argument(help = "The item's identifier, a number.").long()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Show one item."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.items.getItem(id)
        if (json) echo(response.response.bodyAsText().trim()) else echo(item(response.body()))
    }
}

private class ItemListings(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "listings") {
    private val id by argument(help = "The item's identifier, a number.").long()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Show where an item is listed, on each marketplace account."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.items.listItemListings(id)
        if (json) { echo(response.response.bodyAsText().trim()); return@use }
        echo(terminal.table(listOf("ID", "Account", "Status", "Price", "Published", "URL"), response.body().`data`.map {
            listOf(it.id, it.accountId, it.status, money(it.price), date(it.publishedAt), it.url)
        }, empty = "This item is not listed."))
    }
}

internal fun item(item: Item) = sheet(
    "ID" to item.id.toString(),
    "Name" to item.name,
    "SKU" to item.sku,
    "Status" to if (item.sold) "sold" else "unsold",
    "Description" to short(item.description, 200),
    "Labels" to item.labels.joinToString(", "),
    "Photos" to item.photos.size.toString(),
    "Estimated price" to money(item.estimatedPrice),
    "Purchase price" to money(item.purchasePrice),
    "Purchase date" to date(item.purchaseDate),
    "Selling price" to money(item.sellingPrice),
    "Selling date" to date(item.sellingDate),
    "Created" to date(item.createdAt),
    "Updated" to date(item.updatedAt),
)
