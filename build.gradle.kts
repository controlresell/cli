plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.serialization) apply false
}
val controlresellCliVersion = providers.gradleProperty("controlresellCliVersion").orElse("1.0.0-SNAPSHOT").get()
val versionNumber = "(?:0|[1-9][0-9]*)"
val prereleasePart = "(?:$versionNumber|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"
require(controlresellCliVersion.length <= 80 && Regex("""$versionNumber\.$versionNumber\.$versionNumber(?:-$prereleasePart(?:\.$prereleasePart)*)?""").matches(controlresellCliVersion)) {
    "Use a semantic ControlResell CLI version such as 1.0.0 or 1.0.0-alpha.1."
}
allprojects {
    group = "com.controlresell"
    version = controlresellCliVersion
}
