package com.controlresell.cli

actual object SystemExit {
    actual fun exit(code: Int): Nothing = kotlin.system.exitProcess(code)
    actual fun fail(message: String, code: Int): Nothing {
        System.err.println(message)
        kotlin.system.exitProcess(code)
    }
    actual fun notice(message: String) = System.err.println(message)
}
