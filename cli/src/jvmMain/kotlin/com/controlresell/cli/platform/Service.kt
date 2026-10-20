package com.controlresell.cli.platform

actual fun servicePlatform(): ServicePlatform = error("Managed installation requires the native executable.")
actual fun stderrIsTerminal(): Boolean = System.console() != null
