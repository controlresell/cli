package com.controlresell.cli.config

import com.controlresell.cli.Serialization
import com.controlresell.cli.platform.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path.Companion.toPath

@Serializable
class CliConfig(val apiUrl: String, val apiKey: String) {
    init {
        validateApiUrl(apiUrl)
        require(apiKey.isNotBlank()) { "An API key is required." }
    }
    override fun toString(): String = "CliConfig(apiUrl=$apiUrl, apiKey=[REDACTED])"
}

fun validateApiUrl(value: String): String {
    require(value.isNotBlank()) { "A ControlResell API URL is required." }
    val url = Url(value)
    require(url.protocol == URLProtocol.HTTPS ||
        (url.protocol == URLProtocol.HTTP && url.host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]"))) {
        "Use HTTPS; unencrypted HTTP is allowed only on loopback."
    }
    require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.encodedPath in setOf("", "/") &&
        url.parameters.isEmpty() && url.fragment.isEmpty()) { "Use an API origin without credentials, path, query or fragment." }
    return value.trimEnd('/')
}

fun configPath(env: (String) -> String? = ::environment): String {
    val root = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: run {
        val home = env("HOME") ?: env("USERPROFILE") ?: error("Cannot locate the user home directory.")
        "$home/.config"
    }
    require(root.toPath().isAbsolute) { "The configuration directory must be absolute." }
    return "$root/controlresell/config.json"
}

class ConfigStore(private val path: String = configPath()) {
    fun load(): CliConfig? {
        val file = path.toPath()
        val metadata = FileSystem.SYSTEM.metadataOrNull(file) ?: return null
        require(metadata.symlinkTarget == null && metadata.isRegularFile && (metadata.size ?: 0) <= 65_536) {
            "The configuration must be a small regular file, not a symbolic link."
        }
        return try { Serialization.json.decodeFromString<CliConfig>(FileSystem.SYSTEM.read(file) { readUtf8() }) }
        catch (_: IllegalArgumentException) { error("Cannot read the ControlResell configuration. Run cr login to replace it.") }
    }
    /** [load], or null when the file is missing or unreadable: for callers that can do without it. */
    fun loadOrNull(): CliConfig? = try { load() } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null }
    fun delete() { FileSystem.SYSTEM.delete(path.toPath(), mustExist = false) }
    fun save(config: CliConfig) {
        writePrivateFile(path, Serialization.json.encodeToString(CliConfig.serializer(), config))
    }
}
