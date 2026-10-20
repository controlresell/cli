package com.controlresell.cli

import com.github.ajalt.clikt.command.*
import com.github.ajalt.clikt.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

const val VERSION = BuildInfo.VERSION

class Cli : SuspendingNoOpCliktCommand(name = "cr") {
    override fun help(context: Context) = "The ControlResell command line."
    init { subcommands(Version()) }
}

private class Version : SuspendingCliktCommand(name = "version") {
    override suspend fun run() { echo("ControlResell CLI $VERSION") }
}

fun main(args: Array<String>): Unit = runBlocking {
    try {
        Cli().main(args)
    }
    catch (error: CancellationException) { throw error }
    catch (error: CliOperationException) {
        SystemExit.fail("ControlResell command failed (${error.code}). Check connectivity, authentication and private file permissions.")
    }
    catch (_: Exception) {
        // Never print exception chains from TLS, JSON, file paths or remote servers: they may carry credentials.
        SystemExit.fail("ControlResell command failed. Check connectivity, authentication and file permissions.")
    }
}

expect object SystemExit { fun fail(message: String, code: Int = 1): Nothing; fun exit(code: Int): Nothing }

/** Safe operation identifiers only; never print raw platform/HTTP exception messages. */
class CliOperationException(val code: String) : Exception() {
    init { require(Regex("[a-zA-Z0-9_]{1,120}").matches(code)) }
}
