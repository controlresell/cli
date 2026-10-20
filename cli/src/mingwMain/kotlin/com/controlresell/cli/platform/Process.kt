@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.controlresell.cli.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import com.controlresell.cli.interop.windows.*
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.windows.*

private fun executable(command: String): String? {
    val fs = FileSystem.SYSTEM
    val names = if (command.endsWith(".exe", true)) listOf(command) else listOf("$command.exe")
    val roots = if (command.toPath().isAbsolute || '/' in command || '\\' in command) listOf("")
        else environment("PATH").orEmpty().split(';').filter { it.isNotBlank() }.map { it.trim('"') + "\\" }
    return roots.flatMap { root -> names.map { root + it } }.firstOrNull { fs.metadataOrNull(it.toPath())?.isRegularFile == true }
}

actual suspend fun runInteractiveProcess(command: String, arguments: List<String>, overrides: Map<String, String?>, quiet: Boolean): Int {
    val application = executable(command) ?: error("Install the requested executable and make it available on PATH.")
    val actualArguments = arguments
    val line = (listOf(application) + actualArguments).joinToString(" ", transform = ::quoteWindowsArgument)
    require(line.length < 32_767)
    val child = memScoped {
        val values = mutableMapOf<String, Pair<String, String>>()
        val inherited = checkNotNull(GetEnvironmentStringsW())
        try {
            var offset = 0
            while (inherited[offset] != 0.toUShort()) {
                val entry = (inherited + offset)!!.toKStringFromUtf16()
                offset += entry.length + 1
                val separator = entry.indexOf('=', startIndex = if (entry.startsWith('=')) 1 else 0)
                if (separator > 0) {
                    val name = entry.substring(0, separator)
                    values[name.uppercase()] = name to entry.substring(separator + 1)
                }
            }
        } finally { FreeEnvironmentStringsW(inherited) }
        overrides.forEach { (name, value) ->
            require(name.isNotEmpty() && name.none { it == '=' || it == '\u0000' } && value?.contains('\u0000') != true)
            if (value == null) values.remove(name.uppercase()) else values[name.uppercase()] = name to value
        }
        val block = values.entries.sortedBy { it.key }.joinToString("\u0000", postfix = "\u0000\u0000") { "${it.value.first}=${it.value.second}" }
        checkNotNull(controlresell_windows_spawn(application.wcstr.ptr, line.wcstr.ptr, block.wcstr.ptr, if (quiet) 1 else 0)) {
            "Cannot launch the requested executable."
        }
    }
    var completed = false
    try {
        while (true) {
            val code = memScoped {
                val result = alloc<UIntVar>()
                when (controlresell_windows_poll(child, result.ptr)) {
                    0 -> null
                    1 -> { completed = true; result.value.toInt() }
                    else -> error("Cannot wait for the requested process.")
                }
            }
            if (code != null) return code
            delay(50)
        }
    } finally {
        if (!completed) controlresell_windows_terminate(child)
        controlresell_windows_close(child)
    }
}
