# ControlResell CLI

`cr` is the command-line client of the [ControlResell](https://controlresell.com) public API: read your marketplace
accounts, your stock and your buyer conversations from a terminal, a script or a CI job. It is a native executable for
macOS, Linux and Windows, built with Kotlin Multiplatform on the ControlResell Kotlin SDK. No JVM is required.

**Pre-release:** no version is published yet. The commands below describe the first release, 0.1.0.

## Install

Each release ships signed, version-pinned installers. Replace `VERSION` with the release you want:

```sh
curl -fsSL https://github.com/controlresell/cli/releases/download/VERSION/install.sh | sh
```

```powershell
& ([scriptblock]::Create((Invoke-WebRequest -UseBasicParsing 'https://github.com/controlresell/cli/releases/download/VERSION/install.ps1').Content))
```

The installer verifies the release signature against the public key embedded in the executable, installs it in your
user's application data and prints the launcher directory to add to `PATH`. Set `CONTROLRESELL_INSTALL_ROOT` to choose
another directory, or `CONTROLRESELL_INSTALL_BUNDLE` to install from a downloaded release directory without network
access. Running the installer again keeps your saved login.

## Log in

Create an API key in the ControlResell app, then pass it on standard input. It is checked with the API before it is
saved, and never appears in your shell history or in the process list:

```sh
cr login            # paste the key; input is hidden on a terminal
printf '%s\n' "$KEY" | cr login
cr whoami
cr logout           # removes the saved key; revoke the key itself in the app
```

The key is saved in `$XDG_CONFIG_HOME/controlresell/config.json`, falling back to `~/.config/controlresell/config.json`
(directory 0700, file 0600). In CI or scripts, set `CONTROLRESELL_API_KEY` instead: it takes precedence over the saved
key, and no file is written. `cr login --api-url URL` targets another API origin; remote origins must use HTTPS.

## Commands

```sh
cr accounts list
cr accounts get ACCOUNT_ID
cr items list [--sold true|false] [--account ACCOUNT_ID]
cr items get ITEM_ID
cr items listings ITEM_ID
cr conversations list [--unread true|false] [--account ACCOUNT_ID]
cr conversations get CONVERSATION_ID
cr conversations messages CONVERSATION_ID
cr version
```

Every command accepts `--help`. Read commands print a table or a summary; `--json` prints the API's JSON response
unchanged, for `jq` and scripts.

Lists are paginated. `--limit` sets the page size (1 to 100, 50 by default). When more results exist, the cursor of the
next page is printed on standard error; run the same command again with `--cursor`. `--all` reads every page, 100 per
request unless `--limit` is given, and stops at 10,000 elements. With `--json`, it prints a single
`{"data": [...], "nextCursor": ...}` document; its cursor is null unless the 10,000 ceiling stopped the reading, and
`--all --cursor` continues from there.

Rate-limited requests are retried after the delay the API asks for. Standard output carries only the command's result;
errors and notices go to standard error, as `error: CODE: MESSAGE (request REQUEST_ID)` for an API error. Quote the
request identifier when you contact support.

### Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Success |
| 1 | Other failure: network, file permissions, unexpected response |
| 2 | Invalid command line |
| 3 | No API key, or the key is invalid or not allowed (401, 403) |
| 4 | Not found (404) |
| 5 | Payment required: the subscription does not include this (402) |
| 6 | Rate limited after the retries (429) |
| 7 | Invalid request (400, 409, 422) |

## Updates

An installed `cr` keeps its previous version, so updates are reversible offline:

```sh
cr update --check          # compare with the latest signed release
cr update                  # install it
cr update --version 0.2.0  # choose a version, including a downgrade
cr update --rollback       # return to the previous version, without network access
```

On a terminal, `cr` mentions on standard error when a newer release exists; `cr update --notifications off` disables
this. Automatic updates are off by default: `cr update --automatic on` registers an hourly job for your user (launchd on
macOS, a systemd user timer on Linux, Task Scheduler on Windows); `--automatic status`, `off` and `uninstall` manage
it. An update never interrupts a running command: while one runs, activation is deferred to the next attempt. Set
`CONTROLRESELL_DISABLE_AUTOUPDATE=1` to turn off release checks and automatic updates for a process.

`cr verify-release --manifest manifest.json --signature manifest.sig [--binary FILE --target TARGET]` verifies a
downloaded release by hand, without login or network access.

## Build from source

Use JDK 21 and the checked-in Gradle wrapper. Until the Kotlin SDK is on Maven Central, publish it to your local Maven
repository first:

```sh
scripts/publish-sdk-locally.sh
./gradlew :cli:jvmTest
./gradlew :cli:linkReleaseExecutableMacosArm64   # or MacosX64, LinuxX64, LinuxArm64, MingwX64
python3 scripts/smoke-native.py --binary cli/build/bin/macosArm64/releaseExecutable/cr.kexe
```

`smoke-native.py` runs the native executable against a local fake of the public API (`scripts/fake-api.py`), with an
isolated configuration directory and synthetic keys. Tests never call the real API.

Pull requests run the JVM tests. Native tests, signed bundles and installation acceptance on all five targets run on
version tags and on a manual full validation; publishing a release is a separate, manual step.

## Security and contributing

See [SECURITY.md](SECURITY.md) to report a vulnerability, and [CONTRIBUTING.md](CONTRIBUTING.md) before opening a
pull request.

## License

Apache License 2.0. See [LICENSE](LICENSE).
