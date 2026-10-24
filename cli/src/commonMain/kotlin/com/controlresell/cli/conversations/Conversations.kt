package com.controlresell.cli.conversations

import com.controlresell.cli.CliEnvironment
import com.controlresell.cli.output.*
import com.controlresell.sdk.generated.models.Conversation
import com.controlresell.sdk.generated.models.Message
import com.controlresell.sdk.generated.models.Money
import com.controlresell.sdk.generated.models.SendMessageRequest
import com.controlresell.sdk.generated.models.SendOfferRequest
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.long
import io.ktor.client.statement.bodyAsText

internal class Conversations(environment: CliEnvironment) : SuspendingNoOpCliktCommand(name = "conversations") {
    override fun help(context: Context) = "The conversations with buyers."
    init {
        subcommands(ListConversations(environment), GetConversation(environment), ConversationMessages(environment),
            SendMessage(environment), SendOffer(environment))
    }
}

private class ListConversations(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "list") {
    private val unread by option("--unread", help = "true: only unread conversations; false: only read ones.").boolean()
    private val account by option("--account", help = "Only the conversations of this marketplace account.")
    private val from by option("--from", help = "Only the conversations whose last message is at or after this instant (inclusive), ISO-8601 with its offset, such as 2026-10-01T00:00:00Z.")
    private val to by option("--to", help = "Only the conversations whose last message is before this instant (exclusive), ISO-8601 with its offset.")
    private val buyer by option("--buyer", help = "Only the conversations with this buyer, by their identifier on the marketplace (the buyerId of a conversation or a favorite).")
    private val item by option("--item", help = "Only the conversations about this item (a number), through its transactions.").long()
    private val offerStatus by option("--offer-status", help = "Only the conversations with at least one offer at this status: PENDING, ACCEPTED, REJECTED or CANCELED.")
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the conversations, most recently updated first."
    override suspend fun run() {
        val (start, end) = window(from, to)
        environment.sdk().use { client ->
        printList(paging, json, fetch = { cursor, limit ->
            fetchPage(client.conversations.listConversations(limit = limit, cursor = cursor, unread = unread, accountId = account,
                from = start, to = end, buyerId = buyer, itemId = item, offerStatus = offerStatus),
                { it.`data` }, { it.nextCursor }, json)
        }, render = { conversations ->
            terminal.table(listOf("ID", "Account", "Buyer", "Unread", "Last message", "Updated"), conversations.map {
                listOf(it.id, it.accountId, it.buyerLogin, if (it.unread) "yes" else "no", short(it.lastMessage?.let(::text)), date(it.updatedAt))
            }, empty = "No conversations.")
        })
        }
    }
}

private class GetConversation(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "get") {
    private val id by argument(help = "The conversation's identifier.")
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Show one conversation."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.conversations.getConversation(id)
        if (json) echo(response.response.bodyAsText().trim()) else echo(conversation(response.body()))
    }
}

private class ConversationMessages(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "messages") {
    private val id by argument(help = "The conversation's identifier.")
    private val from by option("--from", help = "Only the messages created at or after this instant (inclusive), ISO-8601 with its offset, such as 2026-10-01T00:00:00Z.")
    private val to by option("--to", help = "Only the messages created before this instant (exclusive), ISO-8601 with its offset.")
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the messages of a conversation."
    override suspend fun run() {
        val (start, end) = window(from, to)
        environment.sdk().use { client ->
        printList(paging, json, fetch = { cursor, limit ->
            fetchPage(client.conversations.listConversationMessages(id = id, limit = limit, cursor = cursor, from = start, to = end),
                { it.`data` }, { it.nextCursor }, json)
        }, render = { messages ->
            terminal.table(listOf("ID", "Author", "Type", "Status", "Sent", "Text"), messages.map {
                listOf(it.id, it.author, it.type, it.status, date(it.createdAt), short(text(it), 60))
            }, empty = "No messages.")
        })
        }
    }
}

private class SendMessage(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "send") {
    private val id by argument(help = "The conversation's identifier.")
    private val text by option("--text", help = "The message to send to the buyer.").required()
    private val write by WriteOptions()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Send a message to the buyer of a conversation. Needs the conversations:write scope."
    override suspend fun run() {
        if (text.isBlank()) throw UsageError("--text cannot be empty.")
        environment.sdk().use { client ->
        val response = client.conversations.sendMessage(id, write.key(), SendMessageRequest(text = text))
        if (json) echo(response.response.bodyAsText().trim()) else echo(sent(response.body()))
        }
    }
}

private class SendOffer(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "offer") {
    private val id by argument(help = "The conversation's identifier.")
    private val price by option("--price", help = "The offered price as a decimal number, such as 12.50.").required()
    private val currency by option("--currency", help = "The currency of the price, such as EUR (the currency of the account; EUR accounts only for now).").required()
    private val transaction by option("--transaction", help = "The identifier of the transaction the offer is about, when the conversation has several.")
    private val write by WriteOptions()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "Send the buyer a price offer. Needs the conversations:write scope."
    override suspend fun run() {
        if (!Regex("""\d+(\.\d{1,2})?""").matches(price) || price.toDouble() <= 0.0) throw UsageError("--price must be a positive decimal number such as 12.50.")
        if (!Regex("[A-Za-z]{3}").matches(currency)) throw UsageError("--currency must be a three-letter code such as EUR.")
        environment.sdk().use { client ->
        val response = client.conversations.sendOffer(id, write.key(),
            SendOfferRequest(price = Money(amount = price, currency = currency.uppercase()), transactionId = transaction))
        if (json) echo(response.response.bodyAsText().trim()) else echo(sent(response.body()))
        }
    }
}

internal fun sent(message: Message) = sheet(
    "ID" to message.id,
    "Conversation" to message.conversationId,
    "Author" to message.author,
    "Type" to message.type,
    "Status" to message.status,
    "Text" to short(text(message), 200),
    "Sent" to date(message.createdAt),
)

/** A message's text, or its offer. */
internal fun text(message: Message): String? =
    message.body ?: message.offer?.let { "Offer ${money(it.price)} (was ${money(it.originalPrice)}, ${it.status})" }

internal fun conversation(conversation: Conversation) = sheet(
    "ID" to conversation.id,
    "Account" to conversation.accountId,
    "Platform" to conversation.platform,
    "Buyer" to conversation.buyerLogin,
    "Buyer ID" to conversation.buyerId,
    "Unread" to if (conversation.unread) "yes" else "no",
    "Pinned" to if (conversation.pinned) "yes" else "no",
    "Last message" to short(conversation.lastMessage?.let(::text), 200),
    "Last message at" to date(conversation.lastMessageAt),
    "Updated" to date(conversation.updatedAt),
)
