@file:OptIn(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi::class)

import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}
val releasePublicKeys = rootProject.fileTree("release/trust") { include("*.pem") }
val generatedReleaseTrust = layout.buildDirectory.dir("generated/release-trust")
val generateReleaseTrust by tasks.registering {
    inputs.files(releasePublicKeys)
    outputs.dir(generatedReleaseTrust)
    doLast {
        fun der(tag: Int, content: ByteArray): ByteArray {
            val length = when {
                content.size < 128 -> byteArrayOf(content.size.toByte())
                content.size < 256 -> byteArrayOf(0x81.toByte(), content.size.toByte())
                else -> byteArrayOf(0x82.toByte(), (content.size ushr 8).toByte(), content.size.toByte())
            }
            return byteArrayOf(tag.toByte()) + length + content
        }
        val identities = mutableSetOf<String>()
        val keys = releasePublicKeys.files.sortedBy { it.name }.map { file ->
            val text = file.readText()
            require(text.startsWith("-----BEGIN PUBLIC KEY-----") && text.trimEnd().endsWith("-----END PUBLIC KEY-----"))
            val spki = Base64.getMimeDecoder().decode(text.removePrefix("-----BEGIN PUBLIC KEY-----").substringBefore("-----END PUBLIC KEY-----"))
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki)) as RSAPublicKey
            require(key.encoded.contentEquals(spki)) { "Release roots must use canonical SubjectPublicKeyInfo encoding." }
            require(key.modulus.bitLength() == 3072 && key.publicExponent.toString() == "65537") { "Release roots must be RSA-3072 with exponent 65537." }
            val pkcs1 = der(0x30, der(0x02, key.modulus.toByteArray()) + der(0x02, key.publicExponent.toByteArray()))
            val id = MessageDigest.getInstance("SHA-256").digest(spki).joinToString("") { "%02x".format(it) }
            require(identities.add(id)) { "Release trust roots must be distinct." }
            "        ReleasePublicKey(\"${Base64.getEncoder().encodeToString(spki)}\", \"${Base64.getEncoder().encodeToString(pkcs1)}\"), // $id"
        }
        require(keys.size in 1..8) { "Ship at least one pinned release root." }
        val output = generatedReleaseTrust.get().file("com/controlresell/cli/update/ShippedReleaseKeys.kt").asFile
        output.parentFile.mkdirs()
        output.writeText("package com.controlresell.cli.update\n\ninternal object ShippedReleaseKeys {\n    val keys = listOf(\n${keys.joinToString("\n")}\n    )\n}\n")
    }
}
val generatedVersionDirectory = layout.buildDirectory.dir("generated/controlresell-version")
val generateBuildInfo by tasks.registering {
    inputs.property("version", project.version.toString())
    outputs.dir(generatedVersionDirectory)
    doLast {
        val output = generatedVersionDirectory.get().file("com/controlresell/cli/BuildInfo.kt").asFile
        output.parentFile.mkdirs()
        output.writeText("package com.controlresell.cli\n\nobject BuildInfo { const val VERSION: String = \"${project.version}\" }\n")
    }
}
kotlin {
    jvmToolchain(21)
    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        if (!target.name.startsWith("mingw")) {
            target.compilations.getByName("main").cinterops.create("process") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/process.def"))
                includeDirs(project.file("src/nativeInterop/cinterop"))
            }
        } else {
            target.compilations.getByName("main").cinterops.create("windows") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/windows.def"))
                includeDirs(project.file("src/nativeInterop/cinterop"))
            }
        }
        target.binaries.executable {
            entryPoint = "com.controlresell.cli.main"
            baseName = "cr"
        }
        target.binaries.configureEach {
            if (target.name.startsWith("linux") && buildType.name == "DEBUG") {
                // Clikt 5.1.0 caches export the same internal symbol twice on Linux.
                // Re-evaluate this workaround on the next Kotlin/Clikt update.
                disableNativeCache(
                    version = DisableCacheInKotlinVersion.`2_4_20`,
                    reason = "Clikt 5.1.0 duplicate selfAndAncestors symbol in Linux static caches",
                )
            }
        }
    }
    sourceSets {
        // Shared POSIX implementation compiled with each platform's own interop.
        macosMain.get().kotlin.srcDir("src/posixMain/kotlin")
        linuxMain.get().kotlin.srcDir("src/posixMain/kotlin")
        commonMain { kotlin.srcDir(generateBuildInfo); kotlin.srcDir(generateReleaseTrust) }
        commonMain.dependencies {
            implementation(libs.controlresell.sdk)
            implementation(libs.serialization)
            implementation(libs.clikt)
            implementation(libs.mordant)
            implementation(libs.okio)
            implementation(libs.coroutines)
            implementation(libs.ktor.client.engine.defaults)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }
        jvmTest.dependencies {
            runtimeOnly(libs.junit)
            implementation(libs.ktor.client.mock)
        }
    }
}
