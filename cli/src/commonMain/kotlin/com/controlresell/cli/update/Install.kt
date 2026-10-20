package com.controlresell.cli.update

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.*
import com.controlresell.cli.platform.servicePlatform
import com.controlresell.cli.CliOperationException

internal class Install : SuspendingCliktCommand(name = "install") {
    private val version by option("--version", help = "Published version; defaults to the latest stable release.")
    private val bundle by option("--bundle", help = "Offline signed release directory containing manifest.json, manifest.sig and the target asset.")
    private val root by option("--root", help = "Absolute private installation directory; defaults to the user's application data.")
    override fun help(context: Context) = "Install a verified native release with an immutable launcher and versioned payloads. Saved login is preserved."
    override suspend fun run() = releaseOperation {
        if (servicePlatform().userId == "0") throw CliOperationException("release_user_installation_required")
        val store = InstallationStore(defaultInstallationPaths(root))
        val selected = InstallationOperations(store).install(version, bundle, allowDowngrade = version != null || bundle != null)
        echo(if (selected == null) "ControlResell CLI ${requireNotNull(store.state()).current.version} is already installed." else "Installed ControlResell CLI ${selected.version}.")
        echo("Launcher: ${store.paths.launcher}")
        echo("Add ${store.paths.launcherDirectory} to PATH, then use cr login.")
    }
}
