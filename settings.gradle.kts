rootProject.name = "cli"
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Temporary: com.controlresell:kotlin-sdk is not on Maven Central yet. Publish it locally first
        // (publishToMavenLocal from controlresell/kotlin-sdk); remove this line once the SDK is released on Central.
        mavenLocal { content { includeGroup("com.controlresell") } }
    }
}
include(":cli")
