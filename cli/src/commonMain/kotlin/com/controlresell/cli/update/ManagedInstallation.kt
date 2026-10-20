package com.controlresell.cli.update

import com.controlresell.cli.CliOperationException
import com.controlresell.cli.SystemExit
import com.controlresell.cli.platform.environment
import com.controlresell.cli.platform.servicePlatform
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

internal fun defaultInstallationPaths(root: String? = null, target: String = installationTarget(),
    env: (String) -> String? = ::environment): InstallationPaths {
    if (root != null) return InstallationPaths(root.toPath(), target)
    fun value(name: String) = env(name)?.takeIf { it.isNotBlank() }
    fun home() = value("HOME") ?: value("USERPROFILE") ?: throw CliOperationException("release_home_unavailable")
    val selected = when {
        target == "windows-x64" -> (value("LOCALAPPDATA") ?: "${home()}/AppData/Local") + "/ControlResell"
        target.startsWith("macos-") -> (value("XDG_DATA_HOME") ?: "${home()}/Library/Application Support") + "/ControlResell"
        else -> (value("XDG_DATA_HOME") ?: "${home()}/.local/share") + "/controlresell"
    }
    return InstallationPaths(selected.toPath(), target)
}

/** Discover only our immutable bin/versions layout, never a runtime server setting or arbitrary PATH entry. */
internal fun managedInstallation(executable: String = servicePlatform().executable): InstallationStore? {
    val fs = FileSystem.SYSTEM
    val metadata = fs.metadataOrNull(executable.toPath()) ?: return null
    if (!metadata.isRegularFile && metadata.symlinkTarget == null) return null
    val actual = fs.canonicalize(executable.toPath())
    val parent = actual.parent ?: return null
    val target = installationTarget()
    val root = when {
        parent.name.equals("bin", ignoreCase = target == "windows-x64") -> parent.parent
        parent.parent?.name == "versions" && Regex("[0-9a-fA-F]{64}").matches(parent.name) -> parent.parent?.parent
        else -> null
    } ?: return null
    val paths = InstallationPaths(root, target)
    if (fs.metadataOrNull(paths.launcherRecord) == null) return null
    val store = InstallationStore(paths)
    store.launcherVersion() ?: return null
    if (!sameInstallationPath(actual, paths.launcher, target) && !actual.name.equals(paths.executableName, ignoreCase = target == "windows-x64")) return null
    return store
}

internal fun sameInstallationPath(left: Path, right: Path, target: String): Boolean =
    FileSystem.SYSTEM.canonicalize(left).toString().equals(FileSystem.SYSTEM.canonicalize(right).toString(), ignoreCase = target == "windows-x64")

internal suspend fun recoverInstallation(store: InstallationStore) {
    if (store.journal() == null) return
    // An active writer owns the lock; a command must still read its atomically selected payload.
    val lock = acquireInstallationLock(store.paths.lock.toString(), exclusive = true) ?: return
    lock.use { store.recoverInterrupted() }
}

internal suspend fun dispatchInstalled(arguments: Array<String>): Boolean {
    val executable = servicePlatform().executable
    val store = managedInstallation(executable) ?: return false
    recoverInstallation(store)
    if (!sameInstallationPath(executable.toPath(), store.paths.launcher, store.paths.target)) return false
    val selected = store.state()?.current
    if (selected == null) {
        if (arguments.firstOrNull() in setOf("install", "update")) return false
        throw CliOperationException("release_installation_missing")
    }
    store.validate(selected)
    SystemExit.exit(executeInstalledPayload(store.paths.binary(selected).toString(), arguments.toList(), store.paths.root.toString()))
}

/**
 * Held shared for the whole command by every cr of a managed installation, so that an activation never swaps its payload.
 * Best effort: a damaged record or an activation in progress must not block an API command, nor `cr update` that repairs it.
 */
internal fun commandInstallationLease(): InstallationLock? =
    try { managedInstallation()?.lock(exclusive = false) } catch (_: CliOperationException) { null }
