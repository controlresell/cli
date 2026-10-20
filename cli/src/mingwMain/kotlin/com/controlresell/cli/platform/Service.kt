@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.controlresell.cli.platform

import kotlinx.cinterop.*
import com.controlresell.cli.interop.windows.controlresell_windows_user_sid
import platform.windows.*

actual fun servicePlatform(): ServicePlatform = memScoped {
    val buffer = allocArray<UShortVar>(32_768)
    val length = GetModuleFileNameW(null, buffer, 32_768u)
    check(length > 0u && length < 32_768u)
    val sid = checkNotNull(controlresell_windows_user_sid())
    try { ServicePlatform("windows", buffer.toKStringFromUtf16(), sid.toKStringFromUtf16()) }
    finally { LocalFree(sid) }
}
actual fun stderrIsTerminal(): Boolean = memScoped {
    val mode = alloc<UIntVar>()
    GetConsoleMode(GetStdHandle(STD_ERROR_HANDLE), mode.ptr) != 0
}
