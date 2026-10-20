package com.controlresell.cli.platform


expect fun environment(name: String): String?
expect fun platformArguments(arguments: Array<String>): Array<String>
expect fun writePrivateFile(path: String, content: String)
/** Atomically publish a complete private file only if the destination does not exist. */
expect fun createPrivateFile(path: String, content: String): Boolean

expect fun secureRandomBytes(count: Int): ByteArray

expect suspend fun runInteractiveProcess(command: String, arguments: List<String>, overrides: Map<String, String?>, quiet: Boolean = false): Int

/** The running executable and its user, for managed installation and user update jobs. */
data class ServicePlatform(val os: String, val executable: String, val userId: String, val userName: String? = null, val userHome: String? = null)
expect fun servicePlatform(): ServicePlatform

/** Whether stderr is a terminal: notices meant for a person are printed only there. */
expect fun stderrIsTerminal(): Boolean
