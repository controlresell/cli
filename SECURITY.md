# Security

The ControlResell CLI is pre-release software.

Report a vulnerability privately with GitHub's
[private vulnerability reporting](https://github.com/controlresell/cli/security/advisories/new), never in a public
issue. Never include an API key, a request log with personal data or a buyer conversation in a report or
an issue. Use synthetic keys in tests and examples.

Releases are signed. Verify a downloaded release with `cr verify-release` or let the installer do it; do not run an
executable that failed verification.
