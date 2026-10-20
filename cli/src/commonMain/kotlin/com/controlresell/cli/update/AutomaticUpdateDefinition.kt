package com.controlresell.cli.update

import com.controlresell.cli.platform.quoteWindowsArgument
import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath

/** Hourly user jobs, separate from any cr process tree. */
internal fun automaticUpdateDefinitions(settings: AutomaticUpdateSettings): Map<Path, ByteArray> {
    val arguments = listOf(settings.executable, "update", "--automatic-run", "--root", settings.executable.toPath().parent!!.parent.toString())
    fun xml(value: String) = definitionXml(value)
    val primary = settings.registration.toPath()
    if (settings.os == "linux") return mapOf(
        primary to """
            [Unit]
            Description=ControlResell CLI automatic update schedule
            [Timer]
            OnCalendar=hourly
            Persistent=true
            RandomizedDelaySec=60
            Unit=${settings.label}.service
            [Install]
            WantedBy=timers.target
        """.trimIndent().plus("\n").encodeToByteArray(),
        primary.parent!! / "${settings.label}.service" to """
            [Unit]
            Description=ControlResell CLI signed automatic update
            [Service]
            Type=oneshot
            ExecStart=${systemdCommand(arguments)}
            UMask=0077
            NoNewPrivileges=yes
            StandardOutput=null
            StandardError=null
        """.trimIndent().plus("\n").encodeToByteArray(),
    )
    val text = if (settings.os == "macos") """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0"><dict>
        <key>Label</key><string>${settings.label}</string>
        <key>ProgramArguments</key><array>${arguments.joinToString("") { "<string>${xml(it)}</string>" }}</array>
        <key>RunAtLoad</key><true/>
        <key>StartInterval</key><integer>3600</integer>
        <key>ProcessType</key><string>Background</string>
        <key>StandardOutPath</key><string>/dev/null</string>
        <key>StandardErrorPath</key><string>/dev/null</string>
        </dict></plist>
    """.trimIndent() + "\n" else """
        <?xml version="1.0" encoding="UTF-16"?>
        <Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
        <RegistrationInfo><Description>ControlResell CLI signed automatic update</Description></RegistrationInfo>
        <Triggers><TimeTrigger><Repetition><Interval>PT1H</Interval><StopAtDurationEnd>false</StopAtDurationEnd></Repetition><StartBoundary>2020-01-01T00:00:00</StartBoundary></TimeTrigger></Triggers>
        <Principals><Principal id="ControlResellUser"><UserId>${xml(settings.userId)}</UserId><LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal></Principals>
        <Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy><DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries><StopIfGoingOnBatteries>false</StopIfGoingOnBatteries><AllowHardTerminate>false</AllowHardTerminate><StartWhenAvailable>true</StartWhenAvailable><RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable><AllowStartOnDemand>true</AllowStartOnDemand><Enabled>true</Enabled><ExecutionTimeLimit>PT0S</ExecutionTimeLimit></Settings>
        <Actions Context="ControlResellUser"><Exec><Command>${xml(settings.executable)}</Command><Arguments>${xml(arguments.drop(1).joinToString(" ", transform = ::quoteWindowsArgument))}</Arguments></Exec></Actions>
        </Task>
    """.trimIndent() + "\n"
    return mapOf(primary to if (settings.os == "windows") Buffer().apply {
        writeShortLe(0xfeff); text.forEach { writeShortLe(it.code) }
    }.readByteArray() else text.encodeToByteArray())
}
