# Contributing

Thanks for taking a look. This is a small, focused utility, so the best
contributions are bug fixes, compatibility fixes, and carefully scoped features.

## Before you start

- For a bug, open an issue with the app version, Android version, Proxmox VE
  version, and whether TFA is enabled on the account.
- For a security problem, follow [SECURITY.md](SECURITY.md) instead of opening a
  public issue.
- For a larger feature, open an issue first so we can agree on the approach.

## Build and test

Requirements: JDK 17 and an Android SDK with platform 35. See the
[Building](README.md#building) section for details.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Please run the tests and lint before opening a pull request. If you change the
Proxmox API assumptions, add or update a test in
`app/src/test/java/dev/sndp/proxmoxwaker/`.

## Code style

- Kotlin, official style (`kotlin.code.style=official`).
- Jetpack Compose with Material 3; keep the existing theme and visual language.
- Small dependencies only. The app deliberately avoids heavy frameworks.
- Keep user-facing text clear and avoid adding telemetry, analytics, or cloud
  services.
- Update the README and `SECURITY.md` when behavior or security properties change.

## Pull requests

- One topic per PR, with a short description of the problem and the fix.
- Include screenshots or a short recording for visible UI changes.
- Do not commit keystores, passwords, API tokens, or real server hostnames.
- By contributing, you agree that your changes are licensed under the project's
  [MIT License](LICENSE).
