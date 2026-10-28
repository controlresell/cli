#!/usr/bin/env bash
# Temporary, until com.controlresell:kotlin-sdk is released on Maven Central: build the SDK at a pinned commit and
# publish it to the local Maven repository as 1.0.0-SNAPSHOT, the version the CLI depends on.
# Usage: scripts/publish-sdk-locally.sh [Gradle publication tasks...]   (default: publishToMavenLocal)
set -euo pipefail
SDK_COMMIT=51d857969ba886494a38619bff87177062d916f5
directory="$(mktemp -d)"
trap 'rm -rf "$directory"' EXIT
git clone --quiet https://github.com/controlresell/kotlin-sdk.git "$directory"
git -C "$directory" checkout --quiet "$SDK_COMMIT"
sed -i.original 's/^version = ".*"$/version = "1.0.0-SNAPSHOT"/' "$directory/build.gradle.kts"
grep -q '^version = "1.0.0-SNAPSHOT"$' "$directory/build.gradle.kts"
if [ "$#" -eq 0 ]; then set -- publishToMavenLocal; fi
(cd "$directory" && ./gradlew --no-daemon -q "$@")
