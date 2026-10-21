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
user's application data and makes `cr` available by name (a `~/.local/bin` link on macOS/Linux, your user PATH on Windows). Set `CONTROLRESELL_INSTALL_ROOT` to choose
another directory, or `CONTROLRESELL_INSTALL_BUNDLE` to install from a downloaded release directory without network
access. Running the installer again keeps your saved login.

The installer runs `cr install`, which you can also run yourself: `--version` picks a release, `--bundle DIR` installs a
downloaded release directory, `--root DIR` chooses the directory. The default directory is
`~/Library/Application Support/ControlResell` on macOS, `~/.local/share/controlresell` on Linux and
`%LOCALAPPDATA%\ControlResell` on Windows. To uninstall, run `cr update --automatic uninstall` if you enabled automatic
updates, then delete that directory, and `~/.config/controlresell` to remove the saved login.

The command needs no `sudo` or administrator rights. On macOS and Linux it is a symbolic link `~/.local/bin/cr` to the
stable launcher (`<root>/bin/cr`), never to a versioned payload, so updates and rollback need no relinking; when
`~/.local/bin` is not in your `PATH`, the installer prints the exact line to add (`export PATH="$HOME/.local/bin:$PATH"`)
and never edits your shell profile. On Windows the launcher directory is appended to your user PATH
(`HKCU\Environment`); open a new terminal to see it. Running the installer again changes nothing, and it never overwrites
a file or link it did not create: if `~/.local/bin/cr` is something else, the installation finishes, nothing is replaced
and the installer exits with an error. `cr install --no-path` (not available through the install scripts) skips this
step. To undo it, delete `~/.local/bin/cr` (macOS/Linux) or remove the launcher directory from your user PATH (Windows).

## Log in

Create an API key in the ControlResell app, with the scopes of the commands you use (`accounts:read`, `items:read`,
`conversations:read`, and `items:write`, `conversations:write` for the commands that change something; a missing scope makes the command exit with 3). Keys start with `cr_`. Pass it on standard input. It is checked with the API before it is
saved, and never appears in your shell history or in the process list:

```sh
cr login            # paste the key; input is hidden on a terminal
printf '%s\n' "$KEY" | cr login
cr whoami
cr logout           # removes the saved key; revoke the key itself in the app
```

The key is saved in `$XDG_CONFIG_HOME/controlresell/config.json`, falling back to `~/.config/controlresell/config.json`
(`%USERPROFILE%\.config\controlresell\config.json` on Windows), readable only by you: directory 0700 and file 0600 on
macOS and Linux, a private access list on Windows. In CI or scripts, set `CONTROLRESELL_API_KEY` instead: it takes
precedence over the saved key, and no file is written. `cr login --api-url URL` targets another API origin; remote
origins must use HTTPS. The variable uses the origin saved by an earlier `cr login --api-url`, if any, and the default
API otherwise; `cr logout` removes the file, not the variable.

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
cr items publish ITEM_ID --account ACCOUNT_ID [--account ...] [--cancel-before-proceeding]
cr items price-drop ITEM_ID --type ABSOLUTE|PERCENTAGE --value 3 [--round-type UP|DOWN|NEAREST] [--round-step 0.5] [--account ACCOUNT_ID]
cr items republish ITEM_ID [--account ACCOUNT_ID] [--price-variation -5] [--allow-if-deleted] [--drop-type T --drop-value V]
cr items delete ITEM_ID [--yes]
cr conversations send CONVERSATION_ID --text "Hello!"
cr conversations offer CONVERSATION_ID --price 12.50 --currency EUR [--transaction TRANSACTION_ID]
cr version
```

### Writing

The write commands (`publish`, `price-drop`, `republish`, `delete`, `send`, `offer`) need a key with the `items:write` or
`conversations:write` scope, and act on your real marketplace accounts: the publication, price drop and republication run
in your accounts' browsers, a message or an offer reaches the buyer. Read what you are about to send.

- `cr items delete` asks `[y/N]` on standard error before deleting, and deleting cannot be undone. Pass `--yes` to skip
  the question in a script; with no input to answer it (a closed standard input) and no `--yes`, it exits with 2 and does
  nothing. An item still online is refused by the API (`item_still_listed`, exit 7).
- Every write carries an `Idempotency-Key`, random by default. Pass `--idempotency-key KEY` (1 to 255 characters) to
  retry a command safely: the API answers the first response again instead of writing twice.

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
cr update --bundle DIR     # install a downloaded release directory
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
