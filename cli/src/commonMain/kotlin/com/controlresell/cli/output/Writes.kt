package com.controlresell.cli.output

import com.controlresell.cli.CliFailure
import com.controlresell.cli.ExitCode
import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.option
import kotlin.uuid.Uuid

/** What every write command accepts: the `Idempotency-Key` of the call. */
internal class WriteOptions : OptionGroup() {
    private val idempotencyKey by option(
        "--idempotency-key",
        help = "1 to 255 characters, unique per write. Run the same command again with the same key to retry safely: the API " +
            "answers the first response again instead of writing twice. A random key is used by default.",
    ).check("must be 1 to 255 characters") { it.length in 1..255 }

    /** The key of this invocation: the given one, else a random one (the SDK keeps it across its own retries). */
    fun key(): String = idempotencyKey ?: Uuid.random().toString()
}

/**
 * Asks before an irreversible write, on stderr. `--yes` skips the question. Without it, anything but `y` or `yes` aborts;
 * with no input to read (a script, a closed stdin) it aborts with a usage error that names `--yes`.
 */
internal fun BaseCliktCommand<*>.confirm(question: String, yes: Boolean) {
    if (yes) return
    echo("$question [y/N] ", trailingNewline = false, err = true)
    val answer = terminal.readLineOrNull(hideInput = false)?.trim()?.lowercase()
        ?: throw CliFailure(ExitCode.USAGE, "Nothing was confirmed, nothing was done. Answer the question, or pass --yes to skip it.")
    if (answer != "y" && answer != "yes") throw CliFailure(ExitCode.FAILURE, "Aborted: nothing was done.")
}
