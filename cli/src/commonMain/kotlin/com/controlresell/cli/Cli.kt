package com.controlresell.cli

import com.controlresell.cli.accounts.Accounts
import com.controlresell.cli.config.*
import com.controlresell.cli.conversations.Conversations
import com.controlresell.cli.identity.*
import com.controlresell.cli.items.Items
import com.controlresell.cli.platform.*
import com.controlresell.sdk.ControlResell
import com.controlresell.sdk.ControlResellConnectionException
import com.controlresell.sdk.ControlResellError
import com.controlresell.sdk.RetryPolicy
import com.github.ajalt.clikt.command.*
import com.github.ajalt.clikt.core.*
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

const val VERSION = BuildInfo.VERSION

/** The variable that gives the API key without a configuration file; it takes precedence over the file. */
const val API_KEY_VARIABLE = "CONTROLRESELL_API_KEY"

/** What the commands depend on, wired by hand. Tests replace the engine, the files and the environment. */
class CliEnvironment(
    val configPath: () -> String = { com.controlresell.cli.config.configPath() },
    val variable: (String) -> String? = ::environment,
    val engine: HttpClientEngine? = null,
    val retry: RetryPolicy = RetryPolicy(),
) {
    fun store() = ConfigStore(configPath())

    /** The saved configuration, with [API_KEY_VARIABLE] taking precedence over the saved key. */
    fun credentials(): CliConfig {
        val key = variable(API_KEY_VARIABLE)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return store().load() ?: throw CliFailure(ExitCode.AUTHENTICATION, "Not signed in. Run cr login, or set $API_KEY_VARIABLE.")
        // With the variable, the file only gives the API origin: an unreadable file must not block the key.
        return CliConfig(store().loadOrNull()?.apiUrl ?: ControlResell.DEFAULT_BASE_URL, key)
    }

    fun sdk(config: CliConfig = credentials()) =
        ControlResell(apiKey = config.apiKey, baseUrl = validateApiUrl(config.apiUrl), engine = engine, retry = retry)
}

class Cli(environment: CliEnvironment = CliEnvironment()) : SuspendingNoOpCliktCommand(name = "cr") {
    override fun help(context: Context) = "The ControlResell command line. First use: cr login."
    init {
        subcommands(Login(environment), Logout(environment), WhoAmI(environment), Accounts(environment), Items(environment),
            Conversations(environment), Version())
    }
}

private class Version : SuspendingCliktCommand(name = "version") {
    override suspend fun run() { echo("ControlResell CLI $VERSION") }
}

/** How an invocation ended: its exit code, and the message to print on stderr. */
internal data class Outcome(val code: Int, val message: String? = null)

internal suspend fun execute(cli: Cli, arguments: List<String>): Outcome = try {
    cli.parse(arguments)
    Outcome(ExitCode.SUCCESS)
}
catch (error: CancellationException) { throw error }
catch (error: CliktError) {
    cli.echoFormattedHelp(error)
    Outcome(if (error is UsageError) ExitCode.USAGE else error.statusCode)
}
catch (error: ControlResellError) { Outcome(exitCodeFor(error), describe(error)) }
catch (_: ControlResellConnectionException) {
    // The cause may name hosts, TLS details or proxies: never print it.
    Outcome(ExitCode.FAILURE, "Could not reach the ControlResell API. Check your connection and the API URL.")
}
catch (error: CliFailure) { Outcome(error.exitCode, error.safeMessage) }
catch (error: CliOperationException) {
    Outcome(ExitCode.FAILURE, "ControlResell command failed (${error.code}). Check connectivity, authentication and private file permissions.")
}
catch (_: Exception) {
    // Never print exception chains from TLS, JSON, file paths or remote servers: they may carry credentials.
    Outcome(ExitCode.FAILURE, "ControlResell command failed. Check connectivity, authentication and file permissions.")
}

fun main(args: Array<String>): Unit = runBlocking {
    val outcome = try {
        execute(Cli(), platformArguments(args).toList())
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { Outcome(ExitCode.FAILURE, "ControlResell command failed. Check connectivity, authentication and file permissions.") }
    outcome.message?.let { SystemExit.fail(it, outcome.code) }
    SystemExit.exit(outcome.code)
}

expect object SystemExit { fun fail(message: String, code: Int = 1): Nothing; fun exit(code: Int): Nothing }

/** Safe operation identifiers only; never print raw platform/HTTP exception messages. */
class CliOperationException(val code: String) : Exception() {
    init { require(Regex("[a-zA-Z0-9_]{1,120}").matches(code)) }
}
