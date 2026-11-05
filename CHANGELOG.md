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
  states. Running rows use the status icon, pulsing dot, and green status label
  instead of tinting the whole row.
- Settings slides over the main screen, and splash, lock, and main crossfade into
  one another.
- Shimmering skeleton rows while the first guest read is in flight; the
  empty-state icon bobs and the lock-screen fingerprint breathes.
- Pull-to-refresh has a custom animated puck: it fills as you drag, gives a
  haptic tick at the trigger point, then spins with the theme accent gradient
  while the API call is in flight. The guest list also remembers its scroll
  position. Pull is disabled when the phone has no network, the node is offline,
  or the screen is already busy loading.
- Starting or stopping a guest no longer replaces the row action with a spinner;
  the Start/Stop button stays in place and is disabled while the command is in
  flight. The follow-up status read is silent, so it does not flash the header
  or footer sync indicators.
- Background refresh is silent: the list re-reads every 3 seconds in place,
  with no spinner, layout jump, or countdown label. A failed read keeps the
  list briefly, then clears it once the node is confirmed unreachable, and
  repopulates on its own when the network returns.
- Fixed the status state machine treating a dropped Wi-Fi connection as an
  offline server. "No connection" now means the phone has no Wi-Fi, Ethernet, or
  VPN route; "Offline" means a route exists but the node is not answering. Reads
  that finish after a route or host change can no longer restore a stale online
  state, and losing Wi-Fi while watching a shutdown no longer makes the node look
  powered off. After a node shutdown, the server card and the machine card now
  switch to their offline states in the same update instead of the list lagging
  a few seconds behind.

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
