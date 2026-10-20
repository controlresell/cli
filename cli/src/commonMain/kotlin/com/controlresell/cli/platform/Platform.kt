package com.controlresell.cli.platform


expect fun environment(name: String): String?
expect fun platformArguments(arguments: Array<String>): Array<String>
expect fun writePrivateFile(path: String, content: String)
/** Atomically publish a complete private file only if the destination does not exist. */
expect fun createPrivateFile(path: String, content: String): Boolean

expect fun secureRandomBytes(count: Int): ByteArray
