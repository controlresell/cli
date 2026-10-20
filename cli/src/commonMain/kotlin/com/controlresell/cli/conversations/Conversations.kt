package com.controlresell.cli.conversations

import com.controlresell.cli.CliEnvironment
import com.controlresell.cli.output.*
import com.controlresell.sdk.generated.models.Conversation
import com.controlresell.sdk.generated.models.Message
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.boolean
import io.ktor.client.statement.bodyAsText

internal class Conversations(environment: CliEnvironment) : SuspendingNoOpCliktCommand(name = "conversations") {
    override fun help(context: Context) = "The conversations with buyers."
    init { subcommands(ListConversations(environment), GetConversation(environment), ConversationMessages(environment)) }
}

private class ListConversations(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "list") {
    private val unread by option("--unread", help = "true: only unread conversations; false: only read ones.").boolean()
    private val account by option("--account", help = "Only the conversations of this marketplace account.")
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the conversations, most recently updated first."
    override suspend fun run() = environment.sdk().use { client ->
        printList(paging, json, "cr conversations list", fetch = { cursor ->
            fetchPage(client.conversations.listConversations(limit = paging.limit, cursor = cursor, unread = unread, accountId = account),
                { it.`data` }, { it.nextCursor }, json)
        }, render = { conversations ->
            terminal.table(listOf("ID", "Account", "Buyer", "Unread", "Last message", "Updated"), conversations.map {
                listOf(it.id, it.accountId, it.buyerLogin, if (it.unread) "yes" else "no", short(it.lastMessage?.let(::text)), date(it.updatedAt))
            }, empty = "No conversations.")
        })
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
    private val paging by Paging()
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) = "List the messages of a conversation."
    override suspend fun run() = environment.sdk().use { client ->
        printList(paging, json, "cr conversations messages $id", fetch = { cursor ->
            fetchPage(client.conversations.listConversationMessages(id = id, limit = paging.limit, cursor = cursor),
                { it.`data` }, { it.nextCursor }, json)
        }, render = { messages ->
            terminal.table(listOf("ID", "Author", "Type", "Status", "Sent", "Text"), messages.map {
                listOf(it.id, it.author, it.type, it.status, date(it.createdAt), short(text(it), 60))
            }, empty = "No messages.")
        })
    }
}

/** A message's text, or its offer. */
internal fun text(message: Message): String? =
    message.body ?: message.offer?.let { "Offer ${money(it.price)} (was ${money(it.originalPrice)}, ${it.status})" }

internal fun conversation(conversation: Conversation) = sheet(
    "ID" to conversation.id,
    "Account" to conversation.accountId,
    "Platform" to conversation.platform,
    "Buyer" to conversation.buyerLogin,
    "Unread" to if (conversation.unread) "yes" else "no",
    "Pinned" to if (conversation.pinned) "yes" else "no",
    "Last message" to short(conversation.lastMessage?.let(::text), 200),
    "Last message at" to date(conversation.lastMessageAt),
    "Updated" to date(conversation.updatedAt),
)
