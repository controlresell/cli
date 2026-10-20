package com.controlresell.cli.output

import com.controlresell.cli.CliFailure
import com.controlresell.cli.ExitCode
import com.controlresell.sdk.generated.infrastructure.HttpResponse
import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** The most elements `--all` reads, across pages. */
internal const val ALL_LIMIT = 10_000

internal class Paging : OptionGroup() {
    val limit by option("--limit", help = "Page size, 1 to 100 (50 by default).").int().restrictTo(1..100)
    val cursor by option("--cursor", help = "The cursor of the page to read, printed after the previous one.")
    val all by option("--all", help = "Read every page, up to $ALL_LIMIT elements, 100 per request unless --limit is given.").flag()
}

/** One page as the API answered it, and its elements decoded. */
internal class FetchedPage<T>(val raw: String, val data: List<T>, val nextCursor: String?)

/** The page size `--all` asks for when `--limit` is not given: the largest the API accepts, for the fewest requests. */
private const val ALL_PAGE_SIZE = 100

internal suspend fun <P : Any, T> fetchPage(response: HttpResponse<P>, data: (P) -> List<T>, next: (P) -> String?, json: Boolean): FetchedPage<T> =
    if (json) {
        val raw = response.response.bodyAsText()
        FetchedPage(raw, emptyList(), (jsonPage(raw)["nextCursor"] as? JsonPrimitive)?.contentOrNull)
    } else response.body().let { FetchedPage("", data(it), next(it)) }

private fun jsonPage(raw: String): JsonObject = try { Json.parseToJsonElement(raw) as? JsonObject }
    catch (_: SerializationException) { null } ?: throw CliFailure(ExitCode.FAILURE, "The API answered with an unexpected body.")

/**
 * Prints a list: one page, or every page with `--all`. With `--json`, stdout gets the API's JSON as is (a page), or one
 * `{"data": [...], "nextCursor": ...}` merging every page, whose cursor is null unless the ceiling stopped the reading.
 * Anything meant for a human goes to stderr. `fetch` reads one page from a cursor, with a page size.
 */
internal suspend fun <T> BaseCliktCommand<*>.printList(
    paging: Paging, json: Boolean,
    fetch: suspend (cursor: String?, limit: Int?) -> FetchedPage<T>, render: (List<T>) -> String,
) {
    if (!paging.all) {
        val page = fetch(paging.cursor, paging.limit)
        if (json) echo(page.raw.trim()) else echo(render(page.data))
        page.nextCursor?.let { echo("Next page: run the same command with --cursor ${printable(it)}", err = true) }
        return
    }
    val pageSize = paging.limit ?: ALL_PAGE_SIZE
    val elements = mutableListOf<T>()
    val raw = mutableListOf<JsonElement>()
    var cursor: String? = paging.cursor
    do {
        val count = if (json) raw.size else elements.size
        // Never read past the ceiling, so the cursor given to continue skips nothing.
        val page = fetch(cursor, minOf(pageSize, ALL_LIMIT - count))
        if (json) raw.addAll(jsonPage(page.raw)["data"] as? JsonArray
            ?: throw CliFailure(ExitCode.FAILURE, "The API answered a page without data.")) else elements.addAll(page.data)
        if (page.nextCursor != null && page.nextCursor == cursor) throw CliFailure(ExitCode.FAILURE, "The API returned the same page cursor twice.")
        cursor = page.nextCursor
        if (cursor != null && (if (json) raw.size else elements.size) >= ALL_LIMIT) {
            echo("Stopped after $ALL_LIMIT elements. Continue: run the same command with --cursor ${printable(cursor)}", err = true)
            break
        }
    } while (cursor != null)
    if (json) echo(JsonObject(mapOf("data" to JsonArray(raw), "nextCursor" to (cursor?.let(::JsonPrimitive) ?: JsonNull))).toString())
    else echo(render(elements))
}

private fun printable(value: String) = com.controlresell.cli.printable(value)
