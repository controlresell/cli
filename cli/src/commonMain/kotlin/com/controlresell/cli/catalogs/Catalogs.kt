package com.controlresell.cli.catalogs

import com.controlresell.cli.CliEnvironment
import com.controlresell.cli.printable
import com.controlresell.cli.output.*
import com.controlresell.sdk.generated.models.CatalogCategory
import com.controlresell.sdk.generated.models.CatalogCategoryFields
import com.controlresell.sdk.generated.models.CatalogValue
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.long
import io.ktor.client.statement.bodyAsText

internal class Catalogs(environment: CliEnvironment) : SuspendingNoOpCliktCommand(name = "catalogs") {
    override fun help(context: Context) = "The identifiers an item is written with: categories, brands, sizes, colors..."
    init {
        subcommands(
            Categories(environment), GetCategory(environment), CategoryFields(environment), Brands(environment), Sizes(environment),
            Values(environment, "colors", "colors") { client, limit, cursor, category -> client.catalogs.listCatalogColors(limit = limit, cursor = cursor, categoryId = category) },
            Values(environment, "conditions", "conditions") { client, limit, cursor, category -> client.catalogs.listCatalogConditions(limit = limit, cursor = cursor, categoryId = category) },
            Values(environment, "package-sizes", "package sizes") { client, limit, cursor, category -> client.catalogs.listCatalogPackageSizes(limit = limit, cursor = cursor, categoryId = category) },
            Values(environment, "materials", "materials") { client, limit, cursor, category -> client.catalogs.listCatalogMaterials(limit = limit, cursor = cursor, categoryId = category) },
        )
    }
}

private const val SCOPE = "Needs the items:read scope."

private class Categories(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "categories") {
    private val parent by option("--parent", help = "Only the children of this category.").long()
    private val platform by option("--platform", help = "Only the categories of this marketplace, such as VINTED.")
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the categories, flat, by identifier. $SCOPE"
    override suspend fun run() = environment.sdk().use { client ->
        printList(paging, json, fetch = { cursor, limit ->
            fetchPage(client.catalogs.listCatalogCategories(limit = limit, cursor = cursor, parentId = parent, platform = platform),
                { it.`data` }, { it.nextCursor }, json)
        }, render = { categories ->
            terminal.table(listOf("ID", "Parent", "Title", "Children", "Platforms"), categories.map {
                listOf(it.id.toString(), it.parentId?.toString(), it.title, if (it.hasChildren) "yes" else "no", it.platforms.joinToString(", "))
            }, empty = "No categories.")
        })
    }
}

private class GetCategory(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "category") {
    private val id by argument(help = "The category's identifier.").long()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Show one category. $SCOPE"
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.catalogs.getCatalogCategory(id)
        if (json) echo(response.response.bodyAsText().trim()) else echo(category(response.body()))
    }
}

private class CategoryFields(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "fields") {
    private val id by argument(help = "The category's identifier.").long()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Show what an item of a category is filled with: size groups, enumerated and text fields, measurements. $SCOPE"
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.catalogs.getCatalogCategoryFields(id)
        if (json) echo(response.response.bodyAsText().trim()) else echo(categoryFields(response.body()))
    }
}

private class Brands(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "brands") {
    private val query by option("--q", help = "Only the brands whose name starts with this text, by name (case and accents ignored).")
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the brands: the global ones and yours. $SCOPE"
    override suspend fun run() = environment.sdk().use { client ->
        printList(paging, json, fetch = { cursor, limit ->
            fetchPage(client.catalogs.listCatalogBrands(limit = limit, cursor = cursor, q = query), { it.`data` }, { it.nextCursor }, json)
        }, render = { brands ->
            terminal.table(listOf("ID", "Name", "Custom"), brands.map { listOf(it.id.toString(), it.name, if (it.custom) "yes" else "no") },
                empty = "No brands.")
        })
    }
}

private class Sizes(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "sizes") {
    private val category by option("--category", help = "Only the sizes of this category's size groups.").long()
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the sizes, each with its size group. $SCOPE"
    override suspend fun run() = environment.sdk().use { client ->
        printList(paging, json, fetch = { cursor, limit ->
            fetchPage(client.catalogs.listCatalogSizes(limit = limit, cursor = cursor, categoryId = category), { it.`data` }, { it.nextCursor }, json)
        }, render = { sizes ->
            terminal.table(listOf("ID", "Title", "Group", "Group title"), sizes.map {
                listOf(it.id.toString(), it.title, it.sizeGroupId.toString(), it.sizeGroupTitle)
            }, empty = "No sizes.")
        })
    }
}

/** A list of `id`, `key`, `title` values, such as the colors; only the route differs. */
private class Values(
    private val environment: CliEnvironment, name: String, private val what: String,
    private val read: suspend (com.controlresell.sdk.ControlResell, Int?, String?, Long?) -> com.controlresell.sdk.generated.infrastructure.HttpResponse<com.controlresell.sdk.generated.models.CatalogValuePage>,
) : SuspendingCliktCommand(name = name) {
    private val category by option("--category", help = "Only the $what that apply to this category.").long()
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the $what, titles in your language. $SCOPE"
    override suspend fun run() = environment.sdk().use { client ->
        printList(paging, json, fetch = { cursor, limit ->
            fetchPage(read(client, limit, cursor, category), { it.`data` }, { it.nextCursor }, json)
        }, render = { values ->
            terminal.table(listOf("ID", "Key", "Title"), values.map { listOf(it.id.toString(), it.key, it.title) }, empty = "No $what.")
        })
    }
}

internal fun category(category: CatalogCategory) = sheet(
    "ID" to category.id.toString(),
    "Parent" to category.parentId?.toString(),
    "Title" to category.title,
    "Has children" to if (category.hasChildren) "yes" else "no",
    "Size groups" to category.sizeGroupIds.joinToString(", "),
    "Platforms" to category.platforms.joinToString(", "),
)

internal fun categoryFields(fields: CatalogCategoryFields): String {
    val lines = mutableListOf(
        sheet("Category" to fields.categoryId.toString(), "Size groups" to fields.sizeGroupIds.joinToString(", ")),
    )
    fun values(values: List<CatalogValue>) = values.joinToString(", ") { "${it.id}=${it.key}" }
    if (fields.enumFields.isNotEmpty()) {
        lines += "Enumerated fields (written in the item's \"target\" field):"
        fields.enumFields.forEach {
            lines += "  ${printable(it.key)} -> ${printable(it.target)}${if (it.multiple == true) " (several)" else ""}" +
                (it.platform?.let { platform -> " [${printable(platform)}]" } ?: "") + ": " + printable(values(it.propertyValues))
        }
    }
    if (fields.textFields.isNotEmpty()) {
        lines += "Text fields:"
        fields.textFields.forEach {
            lines += "  ${printable(it.key)} (${printable(it.type)}${if (it.required) ", required" else ""})" +
                (it.platform?.let { platform -> " [${printable(platform)}]" } ?: "")
        }
    }
    if (fields.measurements.isNotEmpty()) {
        lines += "Measurements:"
        fields.measurements.forEach { lines += "  ${printable(it.target)} (${printable(it.unit)}${if (it.required) ", required" else ""})" }
    }
    return lines.joinToString("\n")
}
