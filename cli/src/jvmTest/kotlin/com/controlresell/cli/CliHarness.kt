package com.controlresell.cli

import com.controlresell.sdk.RetryPolicy
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import java.nio.file.Files
import java.nio.file.Path

/** A fake ControlResell API and an isolated configuration directory for one test. */
class CliHarness(private val variables: Map<String, String> = emptyMap()) {
    val directory: Path = Files.createTempDirectory("controlresell-cli-test-")
    val configFile: Path = directory.resolve("controlresell/config.json")
    val requests = mutableListOf<HttpRequestData>()
    private val routes = mutableListOf<Pair<(HttpRequestData) -> Boolean, Answer>>()

    class Answer(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())
    data class Result(val code: Int, val stdout: String, val stderr: String)

    fun on(method: HttpMethod, path: String, status: Int = 200, body: String, headers: Map<String, String> = emptyMap(),
        query: Map<String, String?> = emptyMap()) {
        routes += { request: HttpRequestData ->
            request.method == method && request.url.encodedPath == path &&
                query.all { (name, value) -> request.url.parameters[name] == value }
        } to Answer(status, body, headers)
    }

    private val engine = MockEngine { request ->
        requests += request
        val answer = routes.lastOrNull { it.first(request) }?.second ?: Answer(404, error("route_not_found", "No route"))
        respond(answer.body, HttpStatusCode.fromValue(answer.status), headersOf(
            *(answer.headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()))
    }

    val environment = CliEnvironment(
        configPath = { configFile.toString() },
        variable = { variables[it] },
        engine = engine,
        retry = RetryPolicy(maxRetries = 0),
    )

    suspend fun run(vararg arguments: String, stdin: String? = null): Result {
        val recorder = TerminalRecorder(inputInteractive = false, outputInteractive = false)
        stdin?.let { recorder.inputLines = it.lines().toMutableList() }
        val cli = Cli(environment).context { terminal = Terminal(terminalInterface = recorder) }
        val outcome = execute(cli, arguments.toList())
        return Result(outcome.code, recorder.stdout(), recorder.stderr() + (outcome.message?.let { it + "\n" } ?: ""))
    }

    fun signIn(key: String = KEY, apiUrl: String = "https://api.controlresell.com") {
        Files.createDirectories(configFile.parent)
        Files.writeString(configFile, """{"apiUrl":"$apiUrl","apiKey":"$key"}""")
    }

    companion object {
        const val KEY = "cr_test_0123456789abcdef"
        const val ME = """{"id":42,"username":"vintage-shop","currency":"EUR"}"""
        fun error(code: String, message: String, requestId: String = "req_test") =
            """{"error":{"code":"$code","message":"$message","params":{},"requestId":"$requestId"}}"""
    }
}
