# Changelog

All notable changes to this project are documented here.

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
