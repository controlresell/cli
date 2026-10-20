@file:OptIn(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi::class)

import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
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
        commonMain { kotlin.srcDir(generateBuildInfo) }
        commonMain.dependencies {
            implementation(libs.clikt)
            implementation(libs.mordant)
            implementation(libs.okio)
            implementation(libs.coroutines)
            implementation(libs.ktor.client.engine.defaults)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }
        jvmTest.dependencies { runtimeOnly(libs.junit) }
    }
}
