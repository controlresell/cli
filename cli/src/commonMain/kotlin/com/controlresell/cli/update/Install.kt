package com.controlresell.cli.update

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.*
import com.controlresell.cli.platform.environment
import com.controlresell.cli.platform.servicePlatform
import com.controlresell.cli.CliOperationException

internal class Install : SuspendingCliktCommand(name = "install") {
    private val version by option("--version", help = "Published version; defaults to the latest stable release.")
    private val bundle by option("--bundle", help = "Offline signed release directory containing manifest.json, manifest.sig and the target asset.")
    private val root by option("--root", help = "Absolute private installation directory; defaults to the user's application data.")
    private val noPath by option("--no-path", help = "Do not make the cr command available by name (a ~/.local/bin link on macOS/Linux, the user PATH on Windows).").flag()
    override fun help(context: Context) = "Install a verified native release with an immutable launcher and versioned payloads. Saved login is preserved."
    override suspend fun run() = releaseOperation {
        if (servicePlatform().userId == "0") throw CliOperationException("release_user_installation_required")
        val store = InstallationStore(defaultInstallationPaths(root))
        val selected = InstallationOperations(store).install(version, bundle, allowDowngrade = version != null || bundle != null)
        echo(if (selected == null) "ControlResell CLI ${requireNotNull(store.state()).current.version} is already installed." else "Installed ControlResell CLI ${selected.version}.")
        echo("Launcher: ${store.paths.launcher}")
        if (noPath) echo("Not changing PATH. Invoke the launcher by its full path, or add ${store.paths.launcherDirectory} to PATH yourself.")
        else {
            val outcome = CommandPath(store.paths.target, store.paths.launcher, ::environment).ensure()
            outcome.lines.forEach { echo(it, err = outcome is CommandPathOutcome.Conflict) }
            if (outcome is CommandPathOutcome.Conflict) {
                echo("ControlResell CLI ${requireNotNull(store.state()).current.version} itself is installed; once the name is free, run the install command again. Launcher: ${store.paths.launcher}", err = true)
                throw CliOperationException("release_command_link_conflict")
            }
        }
        echo("Then use $COMMAND_NAME login.")
    }
}
