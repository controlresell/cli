# Contributing

Repository text, code, CLI messages and commits are in English. Keep changes focused and include evidence for behavior
changes. Never put a real API key or real seller or buyer data in a fixture.

Use the checked-in Gradle wrapper and JDK 21. Fast verification: `scripts/publish-sdk-locally.sh` once, then
`./gradlew :cli:jvmTest` and `python3 -m unittest discover -s scripts -p 'test_release_*.py'`. Native verification is
required for platform behavior: link the executable for your platform and run `scripts/smoke-native.py`. Tests run
against a Ktor `MockEngine` or the local fake API, never against the real API.

A command calls the API only through the ControlResell Kotlin SDK. A command whose route is not in the SDK yet waits
for it.
