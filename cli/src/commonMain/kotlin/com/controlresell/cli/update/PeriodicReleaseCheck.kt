package com.controlresell.cli.update

import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import com.controlresell.cli.Serialization
import com.controlresell.cli.CliOperationException
import com.controlresell.cli.VERSION
import com.controlresell.cli.platform.environment
import com.controlresell.cli.platform.servicePlatform
import com.controlresell.cli.platform.writePrivateFile
import com.controlresell.cli.release.DownloadedReleaseMetadata
import com.controlresell.cli.release.ReleaseClient
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import kotlin.time.Clock

@Serializable
private data class ReleaseNotifications(val schemaVersion: Int = 1, val enabled: Boolean = true) {
    init { require(schemaVersion == 1) }
}

@Serializable
private data class CachedReleaseCheck(val schemaVersion: Int = 1, val checkedAtMillis: Long,
    val manifest: String? = null, val signature: String? = null, val successfulObservation: Boolean = false) {
    init {
        require(schemaVersion == 1 && checkedAtMillis >= 0)
        require((manifest == null) == (signature == null))
        require((manifest?.length ?: 0) <= 21_848 && (signature?.length ?: 0) <= 2_732)
    }
}

/** Daily, credential-free checks; cached metadata is reauthenticated against this build's roots. */
internal class PeriodicReleaseCheck(
    private val paths: InstallationPaths,
    private val currentVersion: String,
    private val verifier: VerifyReleaseManifest = VerifyReleaseManifest(),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val fs = FileSystem.SYSTEM
    private val policy = paths.root / "update-settings.json"
    private val cache = paths.root / "update-check.json"
    private val lock = paths.root / "update-check.lock"

    fun notificationsEnabled(): Boolean = if (fs.metadataOrNull(policy) == null) true else try {
        Serialization.json.decodeFromString<ReleaseNotifications>(readPrivate(policy, 1024)).enabled
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { false }

    fun setNotifications(enabled: Boolean) {
        validatePrivateInstallationPath(paths.root.toString(), directory = true)
        writePrivateFile(policy.toString(), Serialization.json.encodeToString(ReleaseNotifications.serializer(), ReleaseNotifications(enabled = enabled)))
    }

    suspend fun check(report: (String) -> Unit = {}, forInstallation: Boolean = false, fetch: suspend () -> ReleaseCheck?): ReleaseCheck? {
        if (!forInstallation && !notificationsEnabled()) return null
        validatePrivateInstallationPath(paths.root.toString(), directory = true)
        val acquired = acquireInstallationLock(lock.toString(), exclusive = true)
        if (acquired == null) {
            val previous = readCache() ?: return null
            val instant = now()
            return previous.takeIf { !forInstallation || (it.successfulObservation && instant >= it.checkedAtMillis &&
                instant - it.checkedAtMillis < DAY_MILLIS) }?.let(::verified)
        }
        acquired.use {
            val previous = readCache()
            val instant = now()
            require(instant >= 0)
            // A backwards wall clock is stale, rather than suppressing checks indefinitely.
            if (previous != null && instant >= previous.checkedAtMillis && instant - previous.checkedAtMillis < DAY_MILLIS)
                return previous.takeIf { !forInstallation || it.successfulObservation }?.let(::verified)
            val updated = try {
                // Only our own deadline becomes an unavailable check; caller cancellation propagates.
                val fetched = withTimeoutOrNull(5_000) { Fetched(fetch()) }
                    ?: throw CliOperationException("release_check_timeout")
                val metadata = fetched.value?.metadata
                if (metadata == null) CachedReleaseCheck(checkedAtMillis = instant, successfulObservation = true) else {
                    val snapshot = DownloadedReleaseMetadata(metadata.manifest.copyOf(), metadata.signature.copyOf())
                    val release = verifier.verify(snapshot.manifest, snapshot.signature)
                    if (release.manifest.version != fetched.value.release.manifest.version) throw CliOperationException("release_version_mismatch")
                    CachedReleaseCheck(checkedAtMillis = instant,
                        manifest = snapshot.manifest.toByteString().base64(), signature = snapshot.signature.toByteString().base64(), successfulObservation = true)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                report("Release check unavailable; continuing with the current installation.")
                // A failure is retried after an hour, not a day, so the hourly job does not stall for a day offline.
                val retryAt = instant - DAY_MILLIS + FAILURE_RETRY_MILLIS
                previous?.copy(checkedAtMillis = retryAt, successfulObservation = false) ?: CachedReleaseCheck(checkedAtMillis = retryAt)
            }
            writePrivateFile(cache.toString(), Serialization.json.encodeToString(CachedReleaseCheck.serializer(), updated))
            return updated.takeIf { !forInstallation || it.successfulObservation }?.let(::verified)
        }
    }

    private fun readCache(): CachedReleaseCheck? = try {
        if (fs.metadataOrNull(cache) == null) null else
            Serialization.json.decodeFromString<CachedReleaseCheck>(readPrivate(cache, 32 * 1024)).also { verified(it) }
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { null }

    private fun verified(value: CachedReleaseCheck): ReleaseCheck? {
        val manifest = value.manifest ?: return null
        val metadata = DownloadedReleaseMetadata(
            manifest.decodeBase64()?.toByteArray() ?: throw CliOperationException("release_cache_invalid"),
            value.signature?.decodeBase64()?.toByteArray() ?: throw CliOperationException("release_cache_invalid"))
        return ReleaseCheck(currentVersion, verifier.verify(metadata.manifest, metadata.signature), metadata)
    }

    private fun readPrivate(path: Path, maximum: Int): String {
        validatePrivateInstallationPath(path.toString(), directory = false)
        return readReleaseFile(path, maximum).decodeToString(throwOnInvalidSequence = true)
    }
    private data class Fetched(val value: ReleaseCheck?)
    companion object { const val DAY_MILLIS = 86_400_000L; const val FAILURE_RETRY_MILLIS = 3_600_000L }
}

/**
 * One check at the start of a command of a managed installation, run beside it: it never delays the command, downloads
 * executables or reads login. The cache keeps it to one network check a day, bounded to 5 seconds.
 */
internal suspend fun checkReleaseUpdate(report: (String) -> Unit) {
    if (!environment("CONTROLRESELL_DISABLE_AUTOUPDATE").isNullOrEmpty() || servicePlatform().userId == "0") return
    val store = try { managedInstallation() }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { return }
    if (store == null) return // Source/manual copies never create update state or make release requests.
    try {
        val result = PeriodicReleaseCheck(store.paths, VERSION).check(report) {
            ReleaseClient { configure -> HttpClient { configure(this) } }.use { CheckRelease(it, currentVersion = VERSION)() }
        }
        if (result?.updateAvailable == true)
            report("ControlResell CLI ${result.release.manifest.version} is available. Run cr update to install it.")
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { report("Release notifications unavailable; check installation permissions.") }
}
