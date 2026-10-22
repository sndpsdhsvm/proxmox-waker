# Proxmox Waker

An Android app that turns on your Proxmox server over your LAN and then lets you
start its virtual machines from the phone.

Two jobs, one screen:

1. **Wake the server** — sends a Wake-on-LAN magic packet to the machine's NIC.
2. **Start a VM** — logs into the Proxmox API and lists your VMs and containers,
   each with a Start button.

No cloud service, no account, no telemetry. The app talks straight to your Proxmox
host on your local network.

---

## What it looks like

- **Top card** — "Wake server". Sends the magic packet, then polls the API every
  5 seconds until the host answers (up to 5 minutes), and loads the guest list
  automatically once it does.
- **Machine list** — every QEMU VM and LXC container on the node, with a colour-coded
  status dot and a **Start** button when stopped. Running machines offer **Stop**
  (a graceful ACPI shutdown).
- **Settings** — host, port, node name, credentials, and the wake-on-LAN details.
  Opens automatically on first launch.

The list refreshes by itself every 15 seconds while the app is on screen, so a VM
you started from the web UI shows up as running without any tapping.

---

## Before you build: two things to set up on the server

### 1. Enable Wake-on-LAN on the Proxmox host

On the Proxmox server, check that the NIC supports and has WoL enabled:

```bash
ethtool eth0 | grep -i wake
```

Look for `Wake-on: g` (magic packet). If it says `d`, enable it:

```bash
ethtool -s eth0 wol g
```

To make it survive reboots, add it to `/etc/network/interfaces` under the interface
stanza:

```
post-up /usr/sbin/ethtool -s eth0 wol g
```

Also enable "Wake on LAN" / "Power On By PCI-E" in the motherboard BIOS. WoL only
works from a *powered-off but plugged-in* state (or S3 suspend) — not after pulling
the plug.

Note the NIC's MAC address; `ip link show eth0` prints it. You need it for the app.

> **If WoL doesn't reach the server:** some routers and switches drop broadcast
> packets, and a sleeping machine obviously can't receive anything over IP. If your
> setup gives you trouble, enable "Directed Broadcast" / a static ARP entry on the
> router, or run a tiny always-on device (a Raspberry Pi, another Proxmox node) that
> can send the packet. The app's broadcast address field exists for exactly this.

### 2. Decide which Proxmox user the app logs in with

You chose username/password login, so the app authenticates as a normal Proxmox
user. Using `root@pam` works but is a lot of power to hand a phone app.

A dedicated user is safer. In the Proxmox UI: **Datacenter → Permissions → Users →
Add**, then **Permissions → Add → User Permission**:

- Path: `/vms/<vmid>` (or `/vms` for all guests)
- User: your new user
- Role: **PVEVMUser** covers start/stop and read access

`PVEVMUser` is enough for everything this app does. You do **not** need `PVEAdmin`
or `root`. The app only calls:

| Call | Purpose |
| --- | --- |
| `POST /access/ticket` | log in |
| `GET /version` | connection test |
| `GET /nodes/<node>/qemu`, `/lxc` | list guests |
| `POST /nodes/<node>/{qemu,lxc}/<vmid>/status/start` | start |
| `POST /nodes/<node>/{qemu,lxc}/<vmid>/status/shutdown` | stop |

---

## Building

The toolchain is already set up on this machine:

- **JDK 17** via mise (`mise use -g java@temurin-17`)
- **Android SDK** in `~/Android/Sdk` with platform 35 and build-tools 34.0.0 / 35.0.0

Build the APK:

```bash
./build.sh
```

Nothing to configure by hand: `build.sh` pulls `JAVA_HOME` from mise and points at
`~/Android/Sdk`. `local.properties` (git-ignored) already records the SDK path.

Other useful invocations:

```bash
./build.sh test            # run the unit tests
./build.sh assembleRelease # release APK (unsigned)
./build.sh clean
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Installing it on your phone

Enable **Developer options → USB debugging** on the phone, plug it in, accept the
prompt, then:

```bash
./install.sh
```

That installs the APK and launches it. Or copy `app-debug.apk` to the phone and
open it (you'll need to allow installing from unknown sources).

If you have a device connected, `adb devices` should list it. `./build.sh` puts
`adb` on the PATH for you.

---

## First run

The settings screen opens on its own. Fill in:

| Field | Value |
| --- | --- |
| Host or IP | your Proxmox host, e.g. `192.0.2.10` |
| API port | `8006` (default) |
| Node name | the node as shown in the Proxmox UI, e.g. `pve` |
| Username | `root@pam`, or your dedicated user with its realm |
| Password | that user's password |
| Server MAC address | the NIC's MAC, e.g. `AA:BB:CC:DD:EE:FF` |
| WoL port | `9` (default) |
| Broadcast address | `255.255.255.255` (default) |

Tap **Test** to check the connection without saving the guest list, or **Save and
connect** to store it and load your machines right away.

A few notes:

- The **node name** matters. If you have a cluster, this is the node that actually
  runs the VMs you care about. Run `hostname` on the server if unsure.
- **HTTPS is on by default** and that is correct for Proxmox, which always serves
  the API over TLS on 8006. It uses a self-signed certificate; the app trusts
  user-installed CAs, so if you want strict validation, install the Proxmox CA on
  the phone. Turn HTTPS off only if you have deliberately fronted the API with
  plain HTTP.
- **Turn Wi-Fi off and nothing works.** Wake-on-LAN is a broadcast, and the Proxmox
  API is on your LAN. There's no remote access story here by design.

Finding the MAC address: in the Proxmox UI, select the node → **System → Network**,
or over SSH:

```bash
ip link show | grep -A1 "state UP"
```

### Usage flow

1. Server is asleep. Open the app, tap **Wake server**.
2. The card shows "Waiting for the server", and the app polls the API.
3. When Proxmox finishes booting, the card reads "Server is up" and your VMs appear.
4. Tap **Start** next to the machine you want. The list updates a couple of seconds
   later to show it running.

If the server is already on, skip step 1 — just tap **Refresh VMs** (the circular
arrow in the top bar) or let the automatic refresh do it.

---

## Project layout

```
app/src/main/java/dev/sndp/proxmoxwaker/
├── MainActivity.kt              Compose host, starts/stops list polling
├── data/
│   ├── ProxmoxConfig.kt         Config + Guest/GuestType/GuestStatus models
│   └── SettingsRepository.kt    DataStore persistence
├── net/
│   ├── ProxmoxClient.kt         Ticket auth + API calls (OkHttp)
│   ├── WakeOnLan.kt             Magic packet build + send
│   └── NetworkUtils.kt          Local IPv4 discovery
└── ui/
    ├── MainViewModel.kt         State machine: wake → wait → list → start
    ├── MainScreen.kt            Main screen
    ├── SettingsScreen.kt        Config editor
    └── Theme.kt                 Proxmox-orange Material 3 theme
```

`WakeOnLanTest.kt` covers MAC parsing (four formats) and magic-packet construction.

---

## Security notes

- The password is stored in app-private DataStore and is excluded from Android
  backup and device transfer, so it does not leave the phone. It is **not**
  hardware-backed encrypted — anyone with root on an unlocked phone can read it.
  That is the tradeoff for password login instead of tokens. Use a dedicated
  low-privilege Proxmox user so a leak is contained.
- All traffic to Proxmox goes over HTTPS by default.
- `android:allowBackup="false"` is set on the application.
- `network_security_config.xml` permits cleartext, needed only for the
  HTTPS-off case. Tighten it to `cleartextTrafficPermitted="false"` if you never
  intend to use plain HTTP.

---

## If something goes wrong

**"Cannot connect to …"** — the server isn't up yet or the port is wrong. Confirm
the host is reachable: `ping 192.0.2.10`, then browse to
`https://192.0.2.10:8006` from your phone's browser.

**"Login failed"** — the username needs its realm: `root@pam`, not `root`. Also
check for a locked account or a pending password-change requirement.

**"Permission denied (403)"** — the account lacks `VM.Audit` / `VM.PowerMgmt` on
that guest. See the permissions section above.

**"No machines listed"** — check the **node name**. Listing is per-node; if the VMs
live on a different node in the cluster, the query returns empty.

**Waking does nothing** — the packet left your phone but nobody acted on it. Check
BIOS WoL, `ethtool`, and whether the machine is truly in a WoL-capable sleep state.
Try the subnet-directed broadcast (e.g. `192.0.2.255`) instead of
`255.255.255.255`, which the app also tries automatically.

**"The server did not come up within 5 minutes"** — it's slower than the timeout, or
it never actually woke. The banner shows the last error the app saw, which tells
you whether it was timing out (never woke) or refused (woke, services not ready).

---

## Ideas for later

Things deliberately left out that would fit naturally:

- A home-screen widget with one button for a specific VM.
- Proxmox API tokens instead of a password (safer, but a bit more setup).
- Remembering a per-VM "favourite" that the wake flow starts automatically.
- Shutting the host down from the app when the last VM stops.
