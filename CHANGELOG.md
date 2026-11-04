# Changelog

All notable changes to this project are documented here.

## Unreleased

- Animated power core on the node card: breathing while online, spinning while
  waking or shutting down, dim while asleep.
- Slow aurora backdrop behind the main screen, plus a confetti burst when a wake
  request finally reaches the node.
- Per-guest action sheet: Reboot, Force stop, and Reset (VMs) in addition to the
  existing one-tap Start/Stop. Destructive actions ask for confirmation.
- Live sync footer showing live, reconnecting, or offline state, and
  pull-to-retry while the node is offline.
- Resource bars animate to new values, running guests pulse, and power actions
  give haptic feedback.
- Staggered entrance for the node card, machine list, and footer, with counters
  and resource percentages that count to their new values.
- Start/Stop controls crossfade per guest row and status colours ease between
  states.
- Settings slides over the main screen, and splash, lock, and main crossfade into
  one another.
- Shimmering skeleton rows while the first guest read is in flight; the
  empty-state icon bobs and the lock-screen fingerprint breathes.
- Pull-to-refresh has a custom animated puck: it fills as you drag, gives a
  haptic tick at the trigger point, then spins with the theme accent gradient
  while the API call is in flight. The guest list also remembers its scroll
  position.
- Background refresh is silent: the list re-reads every 10 seconds in place,
  with no spinner, layout jump, or countdown label, and recovers on its own when
  a read fails.

## 1.0.0 - 2026-11-01

First public release.

- Wake a sleeping Proxmox VE host with a Wake-on-LAN magic packet.
- List QEMU VMs and LXC containers with live status.
- Start and gracefully stop individual guests.
- Gracefully shut down the Proxmox node, with confirmation and offline polling.
- Automatic node-name discovery and a connection test in Settings.
- Optional fingerprint lock, applied on a fresh start only.
- Self-signed Proxmox CA support with a recorded SHA-256 fingerprint.
- Unit tests for the Wake-on-LAN packet and the Proxmox API contract.
