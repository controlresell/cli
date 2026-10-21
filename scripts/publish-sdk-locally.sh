#!/usr/bin/env bash
# Temporary, until com.controlresell:kotlin-sdk is released on Maven Central: build the SDK at a pinned commit and
# publish it to the local Maven repository as 0.1.0-SNAPSHOT, the version the CLI depends on.
# Usage: scripts/publish-sdk-locally.sh [Gradle publication tasks...]   (default: publishToMavenLocal)
set -euo pipefail
SDK_COMMIT=4bb867d77e207bbf5b62cdb1a8e2c9c2e29fbf8c
directory="$(mktemp -d)"
trap 'rm -rf "$directory"' EXIT
git clone --quiet https://github.com/controlresell/kotlin-sdk.git "$directory"
git -C "$directory" checkout --quiet "$SDK_COMMIT"
sed -i.original 's/^version = ".*"$/version = "0.1.0-SNAPSHOT"/' "$directory/build.gradle.kts"
grep -q '^version = "0.1.0-SNAPSHOT"$' "$directory/build.gradle.kts"
if [ "$#" -eq 0 ]; then set -- publishToMavenLocal; fi
(cd "$directory" && ./gradlew --no-daemon -q "$@")
