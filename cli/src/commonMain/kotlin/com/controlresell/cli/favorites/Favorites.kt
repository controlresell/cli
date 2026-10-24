package com.controlresell.cli.favorites

import com.controlresell.cli.CliEnvironment
import com.controlresell.cli.output.*
import com.controlresell.sdk.generated.models.Favorite
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.long

internal class Favorites(environment: CliEnvironment) : SuspendingNoOpCliktCommand(name = "favorites") {
    override fun help(context: Context) = "The buyers who put the seller's listings in their favorites."
    init {
        subcommands(ListFavorites(environment))
    }
}

private class ListFavorites(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "list") {
    private val account by option("--account", help = "Only the favorites of the listings of this marketplace account.")
    private val item by option("--item", help = "Only the favorites of the listings of this item (a number).").long()
    private val from by option("--from", help = "Only the favorites created at or after this instant (inclusive), ISO-8601 with its offset, such as 2026-10-01T00:00:00Z.")
    private val to by option("--to", help = "Only the favorites created before this instant (exclusive), ISO-8601 with its offset.")
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the favorites of the listings, most recent first. Needs the items:read scope."
    override suspend fun run() {
        val (start, end) = window(from, to)
        environment.sdk().use { client ->
            printList(paging, json, fetch = { cursor, limit ->
                fetchPage(client.favorites.listFavorites(limit = limit, cursor = cursor, from = start, to = end,
                    accountId = account, itemId = item), { it.`data` }, { it.nextCursor }, json)
            }, render = { favorites -> terminal.table(FAVORITE_COLUMNS, favorites.map(::favoriteRow), empty = "No favorites.") })
        }
    }
}

/** `cr items favorites ITEM_ID`: the favorites of one item, on every account it is listed on. */
internal class ItemFavorites(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "favorites") {
    private val id by argument(help = "The item's identifier, a number.").long()
    private val from by option("--from", help = "Only the favorites created at or after this instant (inclusive), ISO-8601 with its offset, such as 2026-10-01T00:00:00Z.")
    private val to by option("--to", help = "Only the favorites created before this instant (exclusive), ISO-8601 with its offset.")
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the favorites of an item's listings, most recent first. Needs the items:read scope."
    override suspend fun run() {
        val (start, end) = window(from, to)
        environment.sdk().use { client ->
            printList(paging, json, fetch = { cursor, limit ->
                fetchPage(client.favorites.listItemFavorites(id = id, limit = limit, cursor = cursor, from = start, to = end),
                    { it.`data` }, { it.nextCursor }, json)
            }, render = { favorites -> terminal.table(FAVORITE_COLUMNS, favorites.map(::favoriteRow), empty = "No favorites.") })
        }
    }
}

private val FAVORITE_COLUMNS = listOf("ID", "Account", "Platform", "Buyer", "Item", "Price", "Sold", "Created", "Message sent")

private fun favoriteRow(favorite: Favorite) = listOf(
    favorite.id, favorite.accountId, favorite.platform, favorite.buyerId,
    favorite.item?.let { "${it.id} ${short(it.name, 40)}" }, favorite.item?.let { money(it.price) },
    favorite.item?.let { if (it.sold) "yes" else "no" }, date(favorite.createdAt), date(favorite.messageSentAt),
)
