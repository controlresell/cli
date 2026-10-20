package com.controlresell.cli.update

import io.ktor.client.HttpClient
import kotlinx.coroutines.withTimeout
import com.controlresell.cli.release.ReleaseFormat
import com.controlresell.cli.release.ReleaseVersion
import com.controlresell.cli.CliOperationException
import com.controlresell.cli.platform.runInteractiveProcess
import com.controlresell.cli.release.DownloadedReleaseMetadata
import com.controlresell.cli.release.ReleaseClient
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

internal class InstallationOperations(private val store: InstallationStore) {
    private val fs = FileSystem.SYSTEM
    private val verifier = VerifyReleaseManifest()

    suspend fun install(version: String?, bundle: String?, allowDowngrade: Boolean, approvedMetadata: DownloadedReleaseMetadata? = null): InstalledVersion? {
        require(approvedMetadata == null || (version != null && bundle == null && !allowDowngrade))
        store.initialize()
        store.writerLock().use {
            recoverBeforeWriting()
            ReleaseClient { configure -> HttpClient { configure(this) } }.use { transport ->
                val candidate = if (bundle == null) version ?: transport.latestVersion()
                    ?: throw CliOperationException("release_no_stable_version") else version
                val directory = bundle?.toPath()
                val metadata = approvedMetadata?.let { DownloadedReleaseMetadata(it.manifest.copyOf(), it.signature.copyOf()) }
                    ?: if (directory == null) transport.metadata(requireNotNull(candidate)) else DownloadedReleaseMetadata(
                    readReleaseFile(directory / "manifest.json", ReleaseFormat.MAX_MANIFEST_BYTES),
                    readReleaseFile(directory / "manifest.sig", ReleaseFormat.MAX_SIGNATURE_BYTES))
                val verified = verifier.verify(metadata.manifest, metadata.signature)
                if (candidate != null && verified.manifest.version != candidate) throw CliOperationException("release_version_mismatch")
                val before = store.state()
                if (!allowDowngrade && before != null && ReleaseVersion.parse(verified.manifest.version) <= ReleaseVersion.parse(before.current.version)) return null
                val apiVersion = serverApiVersion()
                val asset = verified.manifest.assets[store.paths.target] ?: throw CliOperationException("release_target_missing")
                val selected = store.stage(metadata.manifest, metadata.signature) { destination ->
                    fs.sink(destination).buffer().use { sink ->
                        if (directory == null) transport.binary(verified.manifest.version, store.paths.target, asset.size) { bytes, count -> sink.write(bytes, 0, count) }
                        else {
                            val source = directory / asset.fileName
                            val info = fs.metadataOrNull(source) ?: throw CliOperationException("release_file_unreadable")
                            if (!info.isRegularFile || info.symlinkTarget != null || info.size != asset.size) throw CliOperationException("release_binary_invalid")
                            fs.source(source).use { input ->
                                val buffer = okio.Buffer()
                                var count = 0L
                                while (true) {
                                    val read = input.read(buffer, minOf(8192L, asset.size - count + 1))
                                    if (read < 0) break
                                    count += read
                                    if (count > asset.size) throw CliOperationException("release_binary_invalid")
                                    sink.write(buffer, read)
                                }
                            }
                        }
                    }
                }
                probe(selected, apiVersion)
                store.ensureLauncher(selected)
                activate(selected, rollback = false)
                return selected
            }
        }
    }

    suspend fun rollback(): InstalledVersion {
        store.writerLock().use {
            recoverBeforeWriting()
            val selected = store.state()?.previous ?: throw CliOperationException("release_rollback_unavailable")
            store.validate(selected)
            // Previous payloads were already accepted for API v1; rollback remains available offline.
            probe(selected, 1)
            activate(selected, rollback = true)
            return selected
        }
    }

    private suspend fun recoverBeforeWriting() {
        if (store.journal() == null) return
        store.lock(exclusive = true).use { store.recoverInterrupted() }
    }

    private suspend fun activate(version: InstalledVersion, rollback: Boolean) {
        // Every running cr holds the activity lock shared: activation waits for none of them and fails as busy instead.
        store.lock(exclusive = true).use { store.activate(version, rollback = rollback) }
    }

    /** The public API is versioned in its path and v1 only grows by addition: candidates are checked against v1, offline. */
    private fun serverApiVersion(): Int = PUBLIC_API_VERSION

    private suspend fun probe(version: InstalledVersion, apiVersion: Int) {
        store.validate(version)
        val code = withTimeout(10_000) {
            runInteractiveProcess(store.paths.binary(version).toString(),
                listOf("--controlresell-installation-probe", version.version, apiVersion.toString()), emptyMap(), quiet = true)
        }
        if (code != 0) throw CliOperationException("release_candidate_incompatible")
    }
}

/** The version of the public API this CLI calls (`/api/public/v1`). */
internal const val PUBLIC_API_VERSION = 1
