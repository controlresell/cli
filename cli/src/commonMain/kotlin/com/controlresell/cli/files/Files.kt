package com.controlresell.cli.files

import com.controlresell.cli.CliEnvironment
import com.controlresell.cli.output.*
import com.controlresell.sdk.uploadFile
import com.controlresell.sdk.generated.models.ModelFile
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath

/** The largest file the API accepts, in bytes (20 MiB). */
private const val MAX_BYTES = 20L * 1024 * 1024

/** The photo types the API accepts, by file extension. */
private val CONTENT_TYPES = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
    "heic" to "image/heic", "heif" to "image/heif",
)

internal class Files(environment: CliEnvironment) : SuspendingNoOpCliktCommand(name = "files") {
    override fun help(context: Context) = "The photos of the seller's library: send one, read one."
    init {
        subcommands(UploadFile(environment), GetFile(environment))
    }
}

private class UploadFile(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "upload") {
    private val path by argument(help = "The photo to send: a jpeg, png, webp, heic or heif file of at most 20 MiB.")
    private val filename by option("--filename", help = "The name to register (the file's own name by default).")
    private val contentType by option("--content-type", help = "image/jpeg, image/png, image/webp, image/heic or image/heif (guessed from the extension by default).")
    private val write by WriteOptions()
    private val json by option("--json", help = "Print the id as JSON.").flag()
    override fun help(context: Context) =
        "Register a photo and send its bytes, then print its id: pass it to cr items create --photo-id ID. Needs the items:write scope."
    override suspend fun run() {
        val file = path.toPath()
        val fs = FileSystem.SYSTEM
        val metadata = fs.metadataOrNull(file)
        if (metadata == null || !metadata.isRegularFile) throw UsageError("$path is not a file.")
        val size = metadata.size ?: throw UsageError("Cannot read the size of $path.")
        if (size == 0L) throw UsageError("$path is empty.")
        if (size > MAX_BYTES) throw UsageError("$path is larger than 20 MiB.")
        val type = contentType?.trim()?.lowercase() ?: CONTENT_TYPES[file.name.substringAfterLast('.', "").lowercase()]
            ?: throw UsageError("Cannot guess the type of $path: pass --content-type.")
        if (type !in CONTENT_TYPES.values) throw UsageError("--content-type must be one of ${CONTENT_TYPES.values.distinct().joinToString(", ")}.")
        val name = filename ?: file.name
        if (name.isBlank()) throw UsageError("--filename cannot be empty.")
        val bytes = fs.read(file) { readByteArray() }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw UsageError("$path changed while it was read: it must be 1 byte to 20 MiB.")
        environment.sdk().use { client ->
            // The signed address of the registration is a short-lived credential: only the id is printed.
            val upload = client.uploadFile(bytes, name, type, write.key())
            if (json) echo(buildJsonObject { put("id", upload.id) }.toString()) else echo(sheet("ID" to upload.id, "Next" to "cr items create --name NAME --photo-id ${upload.id}"))
        }
    }
}

private class GetFile(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "get") {
    private val id by argument(help = "The file's identifier, from cr files upload.")
    private val json by option("--json", help = "Print the API's JSON response.").flag()
    override fun help(context: Context) =
        "Show one file of the library; its status is PENDING until its bytes are stored. Needs the items:read scope."
    override suspend fun run() = environment.sdk().use { client ->
        val response = client.files.getFile(id)
        if (json) echo(response.response.bodyAsText().trim()) else echo(file(response.body()))
    }
}

internal fun file(file: ModelFile) = sheet(
    "ID" to file.id,
    "Status" to file.status,
    "URL" to file.url,
    "Created" to date(file.createdAt),
)
