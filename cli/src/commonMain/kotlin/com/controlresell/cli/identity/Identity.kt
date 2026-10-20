package com.controlresell.cli.identity

import com.controlresell.cli.CliEnvironment
import com.controlresell.cli.CliFailure
import com.controlresell.cli.CliOperationException
import com.controlresell.cli.ExitCode
import com.controlresell.cli.config.*
import com.controlresell.cli.output.sheet
import com.controlresell.sdk.ControlResell
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException

internal class Login(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "login") {
    private val apiUrl by option("--api-url", help = "The API origin; saved after login. Defaults to ${ControlResell.DEFAULT_BASE_URL}.")
    override fun help(context: Context) = "Save an API key read from stdin, after checking it with the API."
    override suspend fun run() {
        val store = environment.store()
        val origin = validateApiUrl(apiUrl ?: store.load()?.apiUrl ?: ControlResell.DEFAULT_BASE_URL)
        echo("Create an API key in the ControlResell app, with the scopes of the commands you use " +
            "(accounts:read, items:read, conversations:read).", err = true)
        echo("API key (read from stdin; never pass secrets as command arguments): ", trailingNewline = false, err = true)
        val key = terminal.readLineOrNull(hideInput = terminal.terminalInfo.inputInteractive)?.trim().orEmpty()
        if (!key.startsWith("cr_") || key.length > 512 || key.any { it.code < 33 || it.code > 126 })
            throw CliFailure(ExitCode.AUTHENTICATION, "This is not a ControlResell API key: it starts with cr_.")
        val config = CliConfig(origin, key)
        val me = environment.sdk(config).use { it.me.getMe().body() } // Verify before saving a credential.
        try { store.save(config) }
        catch (error: CancellationException) { throw error }
        catch (error: CliOperationException) { throw error }
        catch (error: Exception) { throw CliOperationException("configuration_write_${error::class.simpleName.orEmpty().filter { it.isLetterOrDigit() }}") }
        echo("Signed in as ${me.username}. Configuration saved to ${environment.configPath()}.")
    }
}

internal class Logout(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "logout") {
    override fun help(context: Context) = "Remove the local configuration. The API key stays valid: revoke it in the ControlResell app."
    override suspend fun run() {
        environment.store().delete()
        echo("Local configuration removed. The API key stays valid until you revoke it in the ControlResell app.")
    }
}

internal class WhoAmI(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "whoami") {
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Show the seller the API key acts for."
    override suspend fun run() {
        environment.sdk().use { client ->
            val response = client.me.getMe()
            if (json) { echo(response.response.bodyAsText().trim()); return }
            val me = response.body()
            echo(sheet("Username" to me.username, "ID" to me.id.toString(), "Currency" to me.currency))
        }
    }
}
