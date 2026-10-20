package com.controlresell.cli.update

import com.controlresell.cli.platform.secureRandomBytes

internal fun randomHex(bytes: Int) = secureRandomBytes(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

internal fun definitionXml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

/** systemd's executable token has stricter escaping rules than subsequent arguments. */
internal fun systemdCommand(arguments: List<String>): String {
    fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%").replace("$", "$$") + "\""
    val values = if (arguments.first().any { it in setOf('\'', '"', '\\', '$') }) listOf("/usr/bin/env", "--") + arguments else arguments
    return values.joinToString(" ", transform = ::quote)
}
