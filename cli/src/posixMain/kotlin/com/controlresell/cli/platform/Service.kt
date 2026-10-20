@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
package com.controlresell.cli.platform

import kotlinx.cinterop.*
import com.controlresell.cli.interop.process.*
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.STDERR_FILENO
import platform.posix.getuid
import platform.posix.getpwuid
import platform.posix.isatty
import kotlin.native.Platform
import kotlin.native.OsFamily

actual fun servicePlatform(): ServicePlatform = memScoped {
    val buffer = allocArray<ByteVar>(16_384)
    check(controlresell_executable(buffer, 16_384u) == 0) { "Cannot locate the native executable." }
    val executable = FileSystem.SYSTEM.canonicalize(buffer.toKString().toPath()).toString()
    val identity = checkNotNull(getpwuid(getuid())).pointed
    ServicePlatform(if (Platform.osFamily == OsFamily.MACOSX) "macos" else "linux", executable, getuid().toString(), identity.pw_name?.toKString(), identity.pw_dir?.toKString())
}
actual fun stderrIsTerminal(): Boolean = isatty(STDERR_FILENO) == 1
