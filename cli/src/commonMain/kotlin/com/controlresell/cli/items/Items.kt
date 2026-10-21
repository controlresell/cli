package com.controlresell.cli.items

import com.controlresell.cli.CliEnvironment
import com.controlresell.cli.output.*
import com.controlresell.sdk.generated.models.Item
import com.controlresell.sdk.generated.models.PriceDropRequest
import com.controlresell.sdk.generated.models.PublishRequest
import com.controlresell.sdk.generated.models.RepublishRequest
import com.controlresell.sdk.generated.models.ScheduledAction
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
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
    init {
        subcommands(ListItems(environment), GetItem(environment), ItemListings(environment), PublishItem(environment),
            PriceDropItem(environment), RepublishItem(environment), DeleteItem(environment))
    }
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

private class PublishItem(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "publish") {
    private val id by argument(help = "The item's identifier, a number.").long()
    private val accounts by option("--account", help = "A marketplace account to publish on. Repeat it for several accounts.").multiple(required = true)
    private val cancelBeforeProceeding by option("--cancel-before-proceeding", help = "Cancel the item's pending actions before publishing.").flag()
    private val write by WriteOptions()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Schedule the publication of an item on marketplace accounts. Needs the items:write scope."
    override suspend fun run() {
        checkAccounts(accounts)
        environment.sdk().use { client ->
        val response = client.items.publishItem(id, write.key(),
            PublishRequest(accountIds = accounts, cancelBeforeProceeding = cancelBeforeProceeding.takeIf { it }))
        if (json) echo(response.response.bodyAsText().trim()) else echo(scheduled(response.body()))
        }
    }
}

private class PriceDropItem(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "price-drop") {
    private val id by argument(help = "The item's identifier, a number.").long()
    private val type by option("--type", help = "ABSOLUTE (an amount) or PERCENTAGE.").required()
    private val value by option("--value", help = "What is removed from each listing's current price, never the new price: 3 or 10.5.").required()
    private val roundType by option("--round-type", help = "How the new price is rounded: UP, DOWN or NEAREST.")
    private val roundStep by option("--round-step", help = "The rounding step, such as 0.5.")
    private val accounts by option("--account", help = "Only the listings on this marketplace account. Repeat it for several. Default: every account the item is online on.").multiple()
    private val write by WriteOptions()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Lower the price of an item's listings. Needs the items:write scope."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.items.dropItemPrice(id, write.key(), priceDrop(type, value, roundType, roundStep, accounts))
        if (json) echo(response.response.bodyAsText().trim()) else echo(scheduled(response.body()))
    }
}

private class RepublishItem(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "republish") {
    private val id by argument(help = "The item's identifier, a number.").long()
    private val accounts by option("--account", help = "Only this marketplace account. Repeat it for several. Default: every account the item is listed on.").multiple()
    private val priceVariation by option("--price-variation", help = "A change of the price at republication, such as -5 or +2.")
    private val allowIfDeleted by option("--allow-if-deleted", help = "Republish even if the listing was deleted.").flag()
    private val dropType by option("--drop-type", help = "Lower the price while republishing: ABSOLUTE or PERCENTAGE. Needs --drop-value.")
    private val dropValue by option("--drop-value", help = "What is removed from the price while republishing. Needs --drop-type.")
    private val write by WriteOptions()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Delete an item's listings and publish them again. Needs the items:write scope."
    override suspend fun run() {
        if ((dropType == null) != (dropValue == null)) throw UsageError("--drop-type and --drop-value go together.")
        if (priceVariation != null && !VARIATION.matches(priceVariation!!)) throw UsageError("--price-variation must be a number such as -5 or +2.")
        checkAccounts(accounts)
        val drop = dropType?.let { priceDrop(it, dropValue!!, null, null, emptyList()) }
        environment.sdk().use { client ->
        val response = client.items.republishItem(id, write.key(), RepublishRequest(
            accountIds = accounts.takeIf { it.isNotEmpty() }, priceDrop = drop, priceVariation = priceVariation,
            allowIfDeleted = allowIfDeleted.takeIf { it }))
        if (json) echo(response.response.bodyAsText().trim()) else echo(scheduled(response.body()))
        }
    }
}

private class DeleteItem(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "delete") {
    private val id by argument(help = "The item's identifier, a number.").long()
    private val yes by option("--yes", "-y", help = "Delete without asking for confirmation.").flag()
    override fun help(context: Context) =
        "Delete an item from the stock, with its photos links, labels and history. This cannot be undone. An item still online is refused. Needs the items:write scope."
    override suspend fun run() {
        confirm("Delete item $id from your stock? This cannot be undone.", yes)
        environment.sdk().use { client -> client.items.deleteItem(id) }
        echo("Deleted item $id.")
    }
}

private val DECIMAL = Regex("""\d+(\.\d+)?""")
private val VARIATION = Regex("""[+-]?\d+(\.\d+)?""")

private fun checkAccounts(accounts: List<String>) {
    if (accounts.any { it.isBlank() }) throw UsageError("--account cannot be empty.")
    if (accounts.toSet().size != accounts.size) throw UsageError("--account is given twice for the same account.")
}

/** Local checks first: a typo must not reach the marketplace accounts. */
private fun priceDrop(type: String, value: String, roundType: String?, roundStep: String?, accounts: List<String>): PriceDropRequest {
    if (type.uppercase() !in setOf("ABSOLUTE", "PERCENTAGE")) throw UsageError("--type must be ABSOLUTE or PERCENTAGE.")
    if (!DECIMAL.matches(value) || value.toDouble() <= 0.0) throw UsageError("--value must be a positive decimal number such as 3 or 10.5.")
    if (type.uppercase() == "PERCENTAGE" && value.toDouble() >= 100.0) throw UsageError("--value must be under 100 for a PERCENTAGE.")
    if (roundType != null && roundType.uppercase() !in setOf("UP", "DOWN", "NEAREST")) throw UsageError("--round-type must be UP, DOWN or NEAREST.")
    if (roundStep != null && (!DECIMAL.matches(roundStep) || roundStep.toDouble() <= 0.0)) throw UsageError("--round-step must be a positive decimal number such as 0.5.")
    checkAccounts(accounts)
    return PriceDropRequest(type = type.uppercase(), value = value, roundType = roundType?.uppercase(), roundStep = roundStep,
        accountIds = accounts.takeIf { it.isNotEmpty() })
}

internal fun scheduled(action: ScheduledAction) = sheet(
    "Item" to action.itemId.toString(),
    "Action" to action.action,
    "Accounts" to action.accountIds.joinToString(", "),
    "Scheduled at" to date(action.scheduledAt),
)

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
