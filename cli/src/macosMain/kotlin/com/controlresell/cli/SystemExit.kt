@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.controlresell.cli

import platform.posix.fflush
import platform.posix.fputs
import platform.posix.stderr

actual object SystemExit {
    actual fun exit(code: Int): Nothing = kotlin.system.exitProcess(code)
    actual fun fail(message: String, code: Int): Nothing {
        fputs("$message\n", stderr)
        fflush(stderr)
        kotlin.system.exitProcess(code)
    }
}
