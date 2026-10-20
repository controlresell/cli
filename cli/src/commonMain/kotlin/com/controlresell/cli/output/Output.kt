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
