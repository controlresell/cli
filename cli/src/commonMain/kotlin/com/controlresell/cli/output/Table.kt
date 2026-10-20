package com.controlresell.cli.output

import com.controlresell.cli.printable
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal

/**
 * A Mordant table of [rows] under [headers], as plain text. It is laid out wider than any terminal so that no value is
 * cut or wrapped; remote values are made printable.
 */
internal fun Terminal.table(headers: List<String>, rows: List<List<String?>>, empty: String): String {
    if (rows.isEmpty()) return empty
    val widget = table {
        whitespace = Whitespace.NOWRAP
        header { row(*headers.toTypedArray()) }
        body { rows.forEach { values -> row(*values.map { printable(it.orEmpty()) }.toTypedArray()) } }
    }
    return widget.render(this, 100_000).lines.joinToString("\n") { line -> line.joinToString("") { it.text }.trimEnd() }
}

/** At most [length] characters of [value], on one line. */
internal fun short(value: String?, length: Int = 40): String? =
    value?.replace(Regex("\\s+"), " ")?.let { if (it.length <= length) it else it.take(length - 1) + "…" }
