package com.controlresell.cli.update

import com.controlresell.cli.CliOperationException
import kotlinx.coroutines.test.runTest
import com.controlresell.cli.platform.ServicePlatform
import com.controlresell.cli.platform.secureRandomBytes
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.*

class AutomaticUpdateDefinitionTest {
    @Test fun definitionsUseStableLauncherAndIndependentHourlyUserJobsWithoutCredentials() {
        val id = "a".repeat(32)
        for (os in listOf("macos", "linux", "windows")) {
            val label = "com.controlresell.cli.updates.$id"
            val settings = AutomaticUpdateSettings(id = id, configuration = "/private/config/controlresell/config.json",
                executable = "/private/application's $ directory/bin/cr", os = os,
                userId = if (os == "windows") "S-1-5-21-123" else "1000",
                registration = "/private/registration/$label" + when (os) { "macos" -> ".plist"; "linux" -> ".timer"; else -> ".xml" })
            val files = automaticUpdateDefinitions(settings)
            assertEquals(if (os == "linux") 2 else 1, files.size)
            val text = files.values.joinToString("\n") { bytes -> if (os == "windows")
                (2 until bytes.size step 2).map { index -> (bytes[index].toInt().and(255) + bytes[index + 1].toInt().and(255) * 256).toChar() }.joinToString("")
                else bytes.decodeToString() }
            assertTrue("--automatic-run" in text && "--root" in text)
            assertFalse("apiKey" in text || "cr_" in text)
            when (os) {
                "linux" -> { assertTrue("OnCalendar=hourly" in text && "Persistent=true" in text); assertTrue("ExecStart=\"/usr/bin/env\" \"--\"" in text) }
                "macos" -> { assertTrue("3600" in text && "&apos;" in text); assertFalse("KeepAlive" in text) }
                else -> { assertTrue("PT1H" in text && "InteractiveToken" in text && "IgnoreNew" in text); assertEquals(0xff.toByte(), files.values.single()[0]) }
            }
        }
    }

    @Test fun modifiedRegistrationIsRejectedBeforeMissingFilesAreWrittenOrAnyManagerIsCalled() = runTest {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("controlresell-automatic-definition " + secureRandomBytes(12).toByteString().hex())
        try {
            val paths = InstallationPaths(root, "linux-x64")
            InstallationStore(paths).initialize()
            val settings = automaticUpdateSettings(paths, (root / "config/controlresell/config.json").toString(), ServicePlatform("linux", paths.launcher.toString(), "1000"), root.toString())
            val files = automaticUpdateDefinitions(settings)
            files.forEach { (path, bytes) -> fs.createDirectories(path.parent!!); fs.write(path) { write(bytes) } }
            val schedule = AutomaticUpdateScheduleImpl()
            assertTrue(schedule.owns(settings))
            val service = files.keys.single { it.name.endsWith(".service") }
            fs.write(service) { writeUtf8("external registration") }
            assertEquals("automatic_registration_modified", assertFailsWith<CliOperationException> { schedule.owns(settings) }.code)
            fs.delete(settings.registration.toPath())
            assertEquals("automatic_registration_modified", assertFailsWith<CliOperationException> { schedule.install(settings) }.code)
            assertFalse(fs.exists(settings.registration.toPath()))
        } finally { if (fs.exists(root)) fs.deleteRecursively(root) }
    }
}
