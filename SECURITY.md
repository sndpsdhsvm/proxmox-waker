# Security policy

## Supported versions

Security fixes are made against the latest release only. If you are running an
older build, upgrade before reporting an issue.

## Reporting a vulnerability

Please **do not open a public issue** for a security problem.

Use GitHub's private vulnerability reporting:

1. Open the repository's **Security** tab.
2. Choose **Report a vulnerability**.
3. Include the app version, Android version, steps to reproduce, and the impact.

If private reporting is unavailable, open a minimal public issue asking for a
private contact channel and do not include exploit details.

You can expect an initial response within a few days. This is a volunteer project,
so there is no bug bounty.

## Threat model

Proxmox Waker is a local-network remote control for a Proxmox VE node. It is not
designed to be exposed to the internet, and it has no cloud component, account
system, analytics, or telemetry.

### Credentials at rest

The Proxmox username and password are stored in app-private Android DataStore.
They are excluded from Android cloud backup and device transfer, but they are
**not** encrypted with the Android Keystore. Anyone who can read the app's private
storage — for example on a rooted device or through a full device backup — can
read the password.

Use a dedicated Proxmox user with only the permissions the app needs. Do not use
the built-in root account unless you accept that tradeoff. API-token support and Keystore-backed
encryption are tracked as future work.

### Fingerprint lock

The optional fingerprint gate uses `androidx.biometric` with Class 3
(`BIOMETRIC_STRONG`) biometrics. It is a local UI gate that hides the controls on a
fresh start. It does **not** encrypt the stored credentials and does not protect
against someone with root access to the device.

### TLS

Proxmox installs a per-host self-signed CA by default, so the app falls back to
accepting a certificate only when it:

- is rejected by the system trust store,
- identifies itself as a Proxmox certificate,
- covers the configured hostname, and
- is inside its validity window.

The accepted certificate's SHA-256 fingerprint is recorded and shown in Settings,
but it is **not pinned or enforced**. If the certificate changes, the app accepts
and displays the new fingerprint instead of refusing the connection. An attacker
who can intercept LAN traffic and present a matching Proxmox-looking certificate
for the same host could therefore impersonate the server. Installing your Proxmox
CA (or a publicly trusted certificate) on the phone makes the connection use the
system trust store and avoids the fallback.

### Known limitations

- TFA / 2FA is not supported. Accounts with TFA enabled cannot log in.
- One node per configuration; clusters are not modelled.
- No certificate pinning.
- No hardware-backed credential storage.

## Secure usage recommendations

- Use a dedicated Proxmox user with the smallest permission set that works.
- Prefer a strong, unique password; do not reuse your Proxmox admin password.
- Keep the phone and the app updated.
- Install your Proxmox CA on the phone, or use a publicly trusted certificate on
  the server, if you want real TLS verification.
- Keep the Proxmox API off the public internet.
