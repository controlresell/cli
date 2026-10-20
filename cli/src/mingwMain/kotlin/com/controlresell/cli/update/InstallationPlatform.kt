@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.controlresell.cli.update

import kotlinx.cinterop.*
import com.controlresell.cli.CliOperationException
import com.controlresell.cli.interop.windows.*
import com.controlresell.cli.platform.runInteractiveProcess

internal actual fun acquireInstallationLock(path: String, exclusive: Boolean): InstallationLock? = memScoped {
    val handle = alloc<COpaquePointerVar>()
    when (controlresell_windows_install_lock(path.wcstr.ptr, if (exclusive) 1 else 0, handle.ptr)) {
        0 -> null
        1 -> object : InstallationLock {
            private var value = handle.value
            override fun close() { value?.let { controlresell_windows_install_unlock(it); value = null } }
        }
        else -> throw CliOperationException("release_lock_failed")
    }
}
internal actual fun sealInstallationExecutable(path: String) = memScoped {
    if (controlresell_windows_install_seal(path.wcstr.ptr) != 0) throw CliOperationException("release_executable_permissions")
}
// Metadata writes use FlushFileBuffers + MoveFileExW(MOVEFILE_WRITE_THROUGH) in the private-file adapter.
internal actual fun flushInstallationDirectory(path: String) = Unit
internal actual fun validatePrivateInstallationPath(path: String, directory: Boolean) = memScoped {
    if (controlresell_windows_install_private_path(path.wcstr.ptr, if (directory) 1 else 0) != 1)
        throw CliOperationException("release_private_path_invalid")
}
internal actual fun installationTarget(): String = "windows-x64"
internal actual suspend fun executeInstalledPayload(path: String, arguments: List<String>, root: String): Int =
    runInteractiveProcess(path, arguments, mapOf("CONTROLRESELL_INSTALL_ROOT" to root))
