package com.controlresell.cli.output

import com.controlresell.cli.CliFailure
import com.controlresell.cli.ExitCode
import com.controlresell.sdk.generated.infrastructure.HttpResponse
import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.*

/** The most elements `--all` reads, across pages. */
internal const val ALL_LIMIT = 10_000

internal class Paging : OptionGroup() {
    val limit by option("--limit", help = "Page size, 1 to 100 (50 by default).").int().restrictTo(1..100)
    val cursor by option("--cursor", help = "The cursor of the page to read, printed after the previous one.")
    val all by option("--all", help = "Read every page, up to $ALL_LIMIT elements.").flag()
}

/** One page as the API answered it, and its elements decoded. */
internal class FetchedPage<T>(val raw: String, val data: List<T>, val nextCursor: String?)

internal suspend fun <P : Any, T> fetchPage(response: HttpResponse<P>, data: (P) -> List<T>, next: (P) -> String?, json: Boolean): FetchedPage<T> =
    if (json) {
        val raw = response.response.bodyAsText()
        val cursor = (Json.parseToJsonElement(raw).jsonObject["nextCursor"] as? JsonPrimitive)?.contentOrNull
        FetchedPage(raw, emptyList(), cursor)
    } else response.body().let { FetchedPage("", data(it), next(it)) }

/**
 * Prints a list: one page, or every page with `--all`. With `--json`, stdout gets the API's JSON as is (a page), or one
 * `{"data": [...], "nextCursor": null}` merging every page; anything meant for a human goes to stderr.
 */
internal suspend fun <T> BaseCliktCommand<*>.printList(
    paging: Paging, json: Boolean, command: String,
    fetch: suspend (cursor: String?) -> FetchedPage<T>, render: (List<T>) -> String,
) {
    if (paging.all && paging.cursor != null) throw UsageError("--all reads every page: it cannot be combined with --cursor.")
    if (!paging.all) {
        val page = fetch(paging.cursor)
        if (json) echo(page.raw.trim()) else echo(render(page.data))
        page.nextCursor?.let { echo("Next page: $command --cursor ${printable(it)}", err = true) }
        return
    }
    val elements = mutableListOf<T>()
    val raw = mutableListOf<JsonElement>()
    var cursor: String? = null
    do {
        val page = fetch(cursor)
        if (json) raw.addAll(Json.parseToJsonElement(page.raw).jsonObject["data"] as? JsonArray
            ?: throw CliFailure(ExitCode.FAILURE, "The API answered a page without data.")) else elements.addAll(page.data)
        cursor = page.nextCursor
        if (cursor != null && (if (json) raw.size else elements.size) >= ALL_LIMIT) {
            echo("Stopped after $ALL_LIMIT elements. Continue with: $command --cursor ${printable(cursor)}", err = true)
            break
        }
    } while (cursor != null)
    if (json) echo(JsonObject(mapOf("data" to JsonArray(raw.take(ALL_LIMIT)), "nextCursor" to JsonNull)).toString())
    else echo(render(elements.take(ALL_LIMIT)))
}

private fun printable(value: String) = com.controlresell.cli.printable(value)
