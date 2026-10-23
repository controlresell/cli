package com.controlresell.cli.items

import com.controlresell.cli.CliEnvironment
import com.controlresell.cli.output.*
import com.controlresell.sdk.generated.models.Item
import com.controlresell.sdk.generated.models.ItemAttribute
import com.controlresell.sdk.generated.models.ItemCreateRequest
import com.controlresell.sdk.generated.models.ItemCustomField
import com.controlresell.sdk.generated.models.ItemEnumValue
import com.controlresell.sdk.generated.models.ItemUpdateRequest
import com.controlresell.sdk.generated.models.Money
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
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.long
import io.ktor.client.statement.bodyAsText
import kotlin.time.Instant

internal class Items(environment: CliEnvironment) : SuspendingNoOpCliktCommand(name = "items") {
    override fun help(context: Context) = "The items of the seller's stock."
    init {
        subcommands(ListItems(environment), GetItem(environment), ItemListings(environment), CreateItem(environment), UpdateItem(environment), PublishItem(environment),
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

/** The fields of an item, shared by `create` and `update`. */
private class ItemFields : OptionGroup() {
    val sku by option("--sku", help = "The item's SKU.")
    val description by option("--description", help = "The item's description.")
    val photoIds by option("--photo-id", help = "A file already in your library to use as a photo, in order. Repeat it for several.").multiple()
    val categoryId by option("--category-id", help = "The category's identifier.").long()
    val brandId by option("--brand-id", help = "The brand's identifier.").long()
    val sizeId by option("--size-id", help = "The size's identifier.").long()
    val colorIds by option("--color-id", help = "A color's identifier. Repeat it for several.").long().multiple()
    val state by option("--state", help = "The condition's identifier.").long()
    val sex by option("--sex", help = "The sex the item is for.")
    val labels by option("--label", help = "A label (created if you do not have it yet). Repeat it for several.").multiple()
    val sold by option("--sold", help = "true: the item is sold; false: it is not.").boolean()
    val estimatedPrice by option("--estimated-price", help = "The estimated price as a decimal number, such as 12.50.")
    val purchasePrice by option("--purchase-price", help = "The purchase price as a decimal number, such as 12.50.")
    val sellingPrice by option("--selling-price", help = "The selling price as a decimal number, such as 12.50.")
    val purchaseDate by option("--purchase-date", help = "The purchase date, ISO-8601 with its offset, such as 2026-10-01T00:00:00Z.")
    val sellingDate by option("--selling-date", help = "The selling date, ISO-8601 with its offset.")
    val packageSizeId by option("--package-size-id", help = "The package size's identifier.").long()
    val materialIds by option("--material-id", help = "A material's identifier. Repeat it for several.").long().multiple()
    val measurementLength by option("--measurement-length", help = "The item's length in centimeters, such as 42.5.").double()
    val measurementWidth by option("--measurement-width", help = "The item's width in centimeters, such as 30.").double()
    val enumValues by option("--enum", help = "A value of one of the category's enumerated fields, as KEY=VALUE. Repeat it for several.").multiple()
    val attributes by option("--attribute", help = "A value of one of the category's free-text fields, as KEY=VALUE. Repeat it for several.").multiple()
    val customFields by option("--custom-field", help = "A value of one of your own fields, as FIELD_ID=VALUE. Repeat it for several.").multiple()
    val purchasePlaceId by option("--purchase-place-id", help = "The identifier of the place the item was bought.").long()
    val sellingPlaceId by option("--selling-place-id", help = "The identifier of the place the item was sold.").long()
    val currency by option("--currency", help = "The currency of the prices (default EUR, the only one accepted for now).").default("EUR")

    val any get() = listOf(sku, description, categoryId, brandId, sizeId, state, sex, sold, estimatedPrice, purchasePrice,
        sellingPrice, purchaseDate, sellingDate, packageSizeId, measurementLength, measurementWidth, purchasePlaceId,
        sellingPlaceId).any { it != null } ||
        listOf(photoIds, colorIds, labels, materialIds, enumValues, attributes, customFields).any { it.isNotEmpty() }

    /** Local checks first, so that a typo does not reach the API. */
    fun amount(option: String, value: String?): Money? {
        if (value == null) return null
        if (!AMOUNT.matches(value)) throw UsageError("$option must be a decimal number with at most 2 decimals, such as 12.50.")
        return Money(amount = value, currency = currency.uppercase())
    }

    fun instant(option: String, value: String?): Instant? = value?.let { parseInstant(option, it) }

    fun toCreate(name: String) = ItemCreateRequest(
        name = name, sku = sku, description = description, photoIds = photoIds.takeIf { it.isNotEmpty() },
        categoryId = categoryId, brandId = brandId, sizeId = sizeId, colorIds = colorIds.takeIf { it.isNotEmpty() },
        state = state, sex = sex, labels = labels.takeIf { it.isNotEmpty() }, sold = sold,
        estimatedPrice = amount("--estimated-price", estimatedPrice), purchasePrice = amount("--purchase-price", purchasePrice),
        purchaseDate = instant("--purchase-date", purchaseDate), sellingPrice = amount("--selling-price", sellingPrice),
        sellingDate = instant("--selling-date", sellingDate),
        packageSizeId = packageSizeId, materialIds = materialIds.takeIf { it.isNotEmpty() }, measurementLength = measurementLength,
        measurementWidth = measurementWidth, purchasePlaceId = purchasePlaceId, sellingPlaceId = sellingPlaceId,
        enumValues = enumValues.takeIf { it.isNotEmpty() }?.map { pair("--enum", it).let { (k, v) -> ItemEnumValue(enumKey = k, enumValue = v) } },
        attributes = attributes.takeIf { it.isNotEmpty() }?.map { pair("--attribute", it).let { (k, v) -> ItemAttribute(fieldKey = k, value = v) } },
        customFields = customFields.takeIf { it.isNotEmpty() }?.map { customField(it) },
    )

    fun toUpdate(name: String?) = ItemUpdateRequest(
        name = name, sku = sku, description = description, photoIds = photoIds.takeIf { it.isNotEmpty() },
        categoryId = categoryId, brandId = brandId, sizeId = sizeId, colorIds = colorIds.takeIf { it.isNotEmpty() },
        state = state, sex = sex, labels = labels.takeIf { it.isNotEmpty() }, sold = sold,
        estimatedPrice = amount("--estimated-price", estimatedPrice), purchasePrice = amount("--purchase-price", purchasePrice),
        purchaseDate = instant("--purchase-date", purchaseDate), sellingPrice = amount("--selling-price", sellingPrice),
        sellingDate = instant("--selling-date", sellingDate),
        packageSizeId = packageSizeId, materialIds = materialIds.takeIf { it.isNotEmpty() }, measurementLength = measurementLength,
        measurementWidth = measurementWidth, purchasePlaceId = purchasePlaceId, sellingPlaceId = sellingPlaceId,
        enumValues = enumValues.takeIf { it.isNotEmpty() }?.map { pair("--enum", it).let { (k, v) -> ItemEnumValue(enumKey = k, enumValue = v) } },
        attributes = attributes.takeIf { it.isNotEmpty() }?.map { pair("--attribute", it).let { (k, v) -> ItemAttribute(fieldKey = k, value = v) } },
        customFields = customFields.takeIf { it.isNotEmpty() }?.map { customField(it) },
    )

    private fun pair(option: String, value: String): Pair<String, String> {
        val at = value.indexOf('=')
        if (at <= 0 || at == value.length - 1) throw UsageError("$option must be KEY=VALUE, such as ${if (option == "--enum") "color=red" else "model=501"}.")
        return value.substring(0, at) to value.substring(at + 1)
    }

    private fun customField(value: String): ItemCustomField {
        val (id, text) = pair("--custom-field", value)
        return ItemCustomField(fieldId = id.toLongOrNull()?.takeIf { it >= 1 } ?: throw UsageError("--custom-field must be FIELD_ID=VALUE, with the identifier of the field, such as 12=blue."), value = text)
    }

    /** Checks the values that need no price to be wrong, before any request. */
    fun check() {
        if (!Regex("[A-Za-z]{3}").matches(currency)) throw UsageError("--currency must be a three-letter code such as EUR.")
        if (sku?.isBlank() == true) throw UsageError("--sku cannot be empty.")
        if (labels.any { it.isBlank() }) throw UsageError("--label cannot be empty.")
        if (photoIds.any { it.isBlank() }) throw UsageError("--photo-id cannot be empty.")
        enumValues.forEach { pair("--enum", it) }
        attributes.forEach { pair("--attribute", it) }
        customFields.forEach { customField(it) }
        if (listOfNotNull(measurementLength, measurementWidth).any { it <= 0.0 }) throw UsageError("--measurement-length and --measurement-width must be positive.")
    }
}

private val AMOUNT = Regex("""\d+(\.\d{1,2})?""")

private class CreateItem(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "create") {
    private val name by option("--name", help = "The item's name.").required()
    private val fields by ItemFields()
    private val write by WriteOptions()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) =
        "Add an item to your stock, unsold and not published (publish it with cr items publish). Needs the items:write scope."
    override suspend fun run() {
        if (name.isBlank()) throw UsageError("--name cannot be empty.")
        fields.check()
        val request = fields.toCreate(name)
        environment.sdk().use { client ->
            val response = client.items.createItem(write.key(), request)
            if (json) echo(response.response.bodyAsText().trim()) else echo(item(response.body()))
        }
    }
}

private class UpdateItem(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "update") {
    private val id by argument(help = "The item's identifier, a number.").long()
    private val name by option("--name", help = "The item's new name.")
    private val fields by ItemFields()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) =
        "Change fields of an item in your stock; the fields you leave out keep their value, and a list you give replaces the whole list. " +
            "It changes the item in ControlResell only, not its listings on the marketplaces. Needs the items:write scope."
    override suspend fun run() {
        if (name != null && name!!.isBlank()) throw UsageError("--name cannot be empty.")
        if (name == null && !fields.any) throw UsageError("Give at least one field to change, such as --name or --estimated-price.")
        fields.check()
        val request = fields.toUpdate(name)
        environment.sdk().use { client ->
            val response = client.items.updateItem(id, request)
            if (json) echo(response.response.bodyAsText().trim()) else echo(item(response.body()))
        }
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
    "Package size" to item.packageSizeId?.toString(),
    "Materials" to item.materialIds.joinToString(", "),
    "Length (cm)" to item.measurementLength?.toString(),
    "Width (cm)" to item.measurementWidth?.toString(),
    "Enumerated fields" to item.enumValues.joinToString(", ") { "${it.enumKey}=${it.enumValue}" },
    "Free-text fields" to item.attributes.joinToString(", ") { "${it.fieldKey}=${it.value}" },
    "Custom fields" to item.customFields.joinToString(", ") { "${it.fieldId}=${it.value}" },
    "Purchase place" to item.purchasePlaceId?.toString(),
    "Selling place" to item.sellingPlaceId?.toString(),
    "Estimated price" to money(item.estimatedPrice),
    "Purchase price" to money(item.purchasePrice),
    "Purchase date" to date(item.purchaseDate),
    "Selling price" to money(item.sellingPrice),
    "Selling date" to date(item.sellingDate),
    "Created" to date(item.createdAt),
    "Updated" to date(item.updatedAt),
)
