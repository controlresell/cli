package com.controlresell.cli.accounts

import com.controlresell.cli.CliEnvironment
import com.controlresell.cli.output.*
import com.controlresell.sdk.generated.models.Account
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import io.ktor.client.statement.bodyAsText

internal class Accounts(environment: CliEnvironment) : SuspendingNoOpCliktCommand(name = "accounts") {
    override fun help(context: Context) = "The seller's marketplace accounts."
    init { subcommands(ListAccounts(environment), GetAccount(environment)) }
}

private class ListAccounts(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "list") {
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the marketplace accounts."
    override suspend fun run() = environment.sdk().use { client ->
        printList(paging, json, fetch = { cursor, limit ->
            fetchPage(client.accounts.listAccounts(limit = limit, cursor = cursor), { it.`data` }, { it.nextCursor }, json)
        }, render = { accounts ->
            terminal.table(listOf("ID", "Platform", "Name", "Status"), accounts.map { listOf(it.id, it.platform, it.name, it.status) },
                empty = "No accounts.")
        })
    }
}

private class GetAccount(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "get") {
    private val id by argument(help = "The account's identifier.")
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Show one marketplace account."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.accounts.getAccount(id)
        if (json) echo(response.response.bodyAsText().trim()) else echo(account(response.body()))
    }
}

internal fun account(account: Account) = sheet(
    "ID" to account.id,
    "Platform" to account.platform,
    "Name" to account.name,
    "Status" to account.status,
    "Status changed" to date(account.statusChangedAt),
    "Lock reason" to account.lockReason,
    "Cooldown ends" to date(account.cooldownEndsAt),
    "Restricted until" to date(account.restrictedUntil),
    "Locale" to account.locale,
    "Available balance" to money(account.availableBalance),
    "Pending balance" to money(account.pendingBalance),
)
