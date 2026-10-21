package com.controlresell.cli.output

import com.controlresell.cli.printable
import com.controlresell.sdk.generated.models.Money
import kotlin.time.Instant

/** A key-value sheet, keys aligned; empty values are skipped. */
internal fun sheet(vararg rows: Pair<String, String?>): String {
    val shown = rows.filter { !it.second.isNullOrEmpty() }
    val width = shown.maxOfOrNull { it.first.length } ?: 0
    return shown.joinToString("\n") { (key, value) -> key.padEnd(width) + "  " + printable(value.orEmpty()) }
}

internal fun money(value: Money?): String? = value?.let { "${printable(it.amount)} ${printable(it.currency)}" }

internal fun date(value: Instant?): String? = value?.toString()

/** An ISO-8601 instant with its offset, or a usage error naming [option]. */
internal fun parseInstant(option: String, value: String): Instant =
    try { Instant.parse(value) }
    catch (_: IllegalArgumentException) { throw com.github.ajalt.clikt.core.UsageError("$option must be an ISO-8601 instant with its offset, such as 2026-10-01T00:00:00Z.") }

/** The `--from` / `--to` window of a list: both parsed, `--to` after `--from`, or a usage error. */
internal fun window(from: String?, to: String?): Pair<Instant?, Instant?> {
    val start = from?.let { parseInstant("--from", it) }
    val end = to?.let { parseInstant("--to", it) }
    if (start != null && end != null && end <= start) throw com.github.ajalt.clikt.core.UsageError("--to must be after --from.")
    return start to end
}
