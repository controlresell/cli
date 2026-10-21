package com.controlresell.cli.customers

import com.controlresell.cli.CliEnvironment
import com.controlresell.cli.output.*
import com.controlresell.sdk.generated.models.Customer
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import io.ktor.client.statement.bodyAsText

internal class Customers(environment: CliEnvironment) : SuspendingNoOpCliktCommand(name = "customers") {
    override fun help(context: Context) = "The other parties of the seller's sales and purchases."
    init {
        subcommands(ListCustomers(environment), GetCustomer(environment))
    }
}

private class ListCustomers(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "list") {
    private val account by option("--account", help = "Only the customers of an order on this marketplace account.")
    private val from by option("--from", help = "Only the customers created at or after this instant (inclusive), ISO-8601 with its offset, such as 2026-10-01T00:00:00Z.")
    private val to by option("--to", help = "Only the customers created before this instant (exclusive), ISO-8601 with its offset.")
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the customers, most recently created first. Needs the customers:read scope."
    override suspend fun run() {
        val start = from?.let { parseInstant("--from", it) }
        val end = to?.let { parseInstant("--to", it) }
        if (start != null && end != null && end <= start) throw UsageError("--to must be after --from.")
        environment.sdk().use { client ->
            printList(paging, json, fetch = { cursor, limit ->
                fetchPage(client.customers.listCustomers(limit = limit, cursor = cursor, from = start, to = end,
                    accountId = account), { it.`data` }, { it.nextCursor }, json)
            }, render = { customers ->
                terminal.table(listOf("ID", "Name", "Email", "Country", "Created"), customers.map {
                    listOf(it.id, "${it.firstName} ${it.lastName}", it.email, it.address.country, date(it.createdAt))
                }, empty = "No customers.")
            })
        }
    }
}

private class GetCustomer(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "get") {
    private val id by argument(help = "The customer's identifier.")
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Show one customer. Needs the customers:read scope."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.customers.getCustomer(id)
        if (json) echo(response.response.bodyAsText().trim()) else echo(customer(response.body()))
    }
}

internal fun customer(customer: Customer) = sheet(
    "ID" to customer.id,
    "First name" to customer.firstName,
    "Last name" to customer.lastName,
    "Email" to customer.email,
    "Address" to listOfNotNull(customer.address.line1, customer.address.line2, customer.address.postalCode,
        customer.address.city, customer.address.country).joinToString(", ").ifEmpty { null },
    "Created" to date(customer.createdAt),
)
