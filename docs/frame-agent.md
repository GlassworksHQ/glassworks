# The frame agent

<a href="https://www.glassworks.dev/brand#parts"><picture><source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-core-on-dark.svg"><img alt="Glassworks Core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30"></picture></a>

The contract between the Glassworks core and a frame's root side, so any frame can implement it.
Spec version 1 (#641, 2026-11-19). The S1's agent is the reference implementation.

## What it is

**The frame agent** is the service a frame runs outside the app to do what the app can't: jobs that
need root, a vendor service stopped, or a port below 1024. The app has no root. It asks the agent,
and the agent reports what it did.

- **It is optional.** A frame without one is a full Glassworks frame. Generic Android, the Pixel
  Tablet and the Mirror have none today. Every pane and the core keep working without it: a feature
  that needs the agent says so where it would show, and nothing else breaks.
- **One per frame.** A frame has one agent, not one root service per pane. On the S1 it is
  `glassworks_agent` (renamed from `s1kiosk_radio` in #549). How a frame builds it inside, one script
  or modules, is its own business (#640).
- **It decides nothing.** The app decides, and Home Assistant decides for the app. The agent applies
  what it is asked and reports back. It never changes a setting on its own.

## Discovery

The core learns about the agent in two steps.

1. **Declared, in `frame.json`.** `"agent": { "capabilities": [...] }` lists what this frame's agent
   can do ([frames/README.md](../frames/README.md#designframejson)). No `agent` field means the frame
   has none. This is fixed per frame: the site, Kiln and the frame's app read the same list.
2. **Offered, at run time.** The device may run an older agent, or none (an S1 whose boot image
   predates it). The frame's code reads what the running agent reports: its **version**, an integer
   that rises with every change the app reads, and which declared capabilities it **offers** now. The
   core never asks for a capability the frame doesn't declare, and the frame never offers one it
   doesn't declare.

**Discovery comes from this boot.** An undo restores the partitions and keeps `/data`, so a file the
agent left there can outlive it. Such a file doesn't count as discovery on its own: pair it with a
marker this boot sets (a property init sets when the service exists, a status the running agent
writes). The exception is a file whose content stays true without the agent, such as the Bluetooth
adapter's address.

A frame reports its agent's version through its own transport. The S1 reports `4` in
`sys.s1kiosk.radio.version` today, and `5` in `sys.glassworks.agent.version` after #549.

## Capabilities

Each capability is a word in `frame.json`, the same in the Kotlin enum `AgentCapability`. A frame
declares only the ones it implements.

| Capability | What the agent does | Desired state (app → agent) | Status (agent → app) | Undo |
|---|---|---|---|---|
| `radio-mode` | Runs the radio in a mode: Zigbee coordinator or router, Thread border router or router, off. Stops the vendor's radio stack for it | The mode id, and the mode's inputs (a Thread dataset, the Zigbee stack) | `<applying\|ok\|error> <mode>[: detail]` | Mode `off`. Removing the agent and restarting brings the vendor's stack back |
| `radio-firewall` | Lets only the allowed addresses reach the radio's network ports (EZSP, OTBR REST) | `""` (nobody but loopback), `any`, or addresses and CIDRs | What is enforced now, and who set it | `any`. Mode `off` removes every rule |
| `radio-firmware` | Flashes a radio firmware image, reads the version back, and rolls back to the vendor's image on failure. Never automatic | One image id with its hash prefix, valid for a short time | `<idle\|flashing\|ok\|error> <id>[: detail]`, and the images it can flash | Flash the vendor's image (`stock`) |
| `frame-bundle` | Checks and installs a signed frame bundle: the agent's own scripts, binaries and firmware | A staged bundle | The installed bundle `<ok\|bad\|none> <version> <floor>`, from a file only root can write, and the last install's result | A bad bundle falls back to the boot image's copy. The floor refuses older ones |
| `app-updates` | Installs a downloaded app update without Android's confirmation | A staged APK | `<running\|ok\|error>: <detail>` | Without it, the app installs with Android's PackageInstaller. An older version is refused, as Android does |
| `time-zone` | Keeps the owner's time zone where the vendor's firmware resets it | An Olson zone id, or `(off)` | `<ok\|error\|none> [<id>][: detail]` | `(off)`: the agent lets go and keeps no zone |
| `hotspot-portal` | While the app's setup hotspot is up, sends its DNS (udp 53) and web (tcp 80) to the app's portal (udp 8053, tcp 8098), so a phone opens the setup page by itself | Passive: the app listening on those ports on the hotspot's address is the request | `off`, `on <iface>`, `error: <why>` | The rules go within seconds of the listeners closing |
| `hotspot-name` | Makes Android name the setup hotspot `Glassworks_NNNN` from the next restart | On by default with the bundle's overlay; `android` to undo | `glassworks`, `android`, `… after a restart`, `error: <why>` | `android`, then a restart |
| `bluetooth-address` | Publishes the Bluetooth adapter's address, which Android 10 hides from apps, for the Bluetooth proxy | None: the agent does it once Android knows a real address | The address, from a file only root can write. Never logged | Read only; nothing to undo |
| `loader-install` | Installs the frame loader into the boot partition from a signed bundle (#139). **Planned**: no frame declares it yet | A staged, signed loader image | Planned | A backup of the partition before the write, a read-back after it, and the frame's own recovery path |

A capability can grow with the agent's version. The S1's radio got the Zigbee stack choice and
*Leave Thread network* at version 4. The pane checks the version for those parts. A new capability
gets a new word, a row here, and a line in the contract test.

## Requests

Every request is **desired state**, not a command.

1. **The app writes what it wants**, whole: the mode, the zone, the allow list. Not "switch to the
   next mode" or "add this address".
2. **Idempotent.** Writing the same state twice does nothing the second time. The agent compares
   with what runs and changes only the difference. A request survives an agent restart, and on a
   persistent transport (the S1's `persist.*`) a reboot: the agent applies it at boot, before the app
   runs.
3. **Status is read back.** The agent reports the state it reached, not the state it was asked for.
   The app shows the agent's status, never its own guess. "Applying" is a state: a mode can take
   seconds to come up.
4. **Errors are status, with the reason.** `error: <why>` in plain words the panel can show
   ("needs the boot image with the agent, update once with Kiln"). An unknown or invalid request is an
   error, never a silent fallback. The agent doesn't retry a request that failed for a reason that
   won't change, until the request changes.
5. **One-shot actions** (flash firmware, install a bundle, install an update, leave a Thread network)
   are desired state too: a request file with an id, which the agent takes once, deletes, and answers
   with a status that names it. A request the agent hasn't taken in time is void, so a stale one
   never runs later.

## Trust

The agent runs as root, and the device may run other apps. On the S1, SELinux is permissive: any app
can set a property or start an init service ([root.md](../frames/aqara-s1-plus/research/root.md#app-update-service)).

- **Code runs only if we signed it.** Scripts, binaries and firmware come from the boot image or a
  bundle signed with the project's key, checked against the key in the boot image, with an
  anti-rollback floor. No request, property or file from the app makes the agent run anything else.
- **Inputs are files the app owns, checked.** The agent reads a request file only when its owner is
  the app's uid (from `pm`, matched with the owner of its data dir), with no symlinks. It copies
  what it keeps into its own root-only directory. Hashes are pinned where the content is code (Silicon Labs' zigbeed).
- **What the core trusts comes from root-owned files.** A property any app may set is for display and
  logs. A decision the app makes from the agent's state (is a bundle installed, which version) reads a
  file owned by root, not a property.
- **Who may ask.** Only the app, which acts for the owner through Home Assistant, the setup screen,
  the installer or the Manage page. A property an app can set is the request channel for low-stakes
  state (a radio mode, a zone). The worst it can do is what the owner could choose. The firewall is
  the exception that matters: another app on the panel could set `any`. The S1 accepts that today.
  `RADIO_ALLOW` in the root-owned `radio.conf` wins over the property, for an owner who wants it fixed.
- **No secrets in properties.** A Thread dataset goes in a file the agent moves into its root-only
  directory and deletes from the app's. The Bluetooth address is never logged.

## Undo

Every capability has its own undo, in the table above. For the whole agent:

- **Turn the feature off** from the panel or Home Assistant: mode `off`, zone `(off)`, hotspot name
  `android`.
- **Remove the agent**: on the S1, Kiln's restore or `fel-install.sh --undo` puts the original boot
  image back, and the vendor's services start at the next boot.
- A capability added later must say its undo here before it ships.

## Transport

How the app and the agent talk is the frame's business: properties, files, a socket, binder. The
core sees only the `FrameAgent` interface ([In the code](#in-the-code)). A frame picks what its
Android allows and documents it next to its agent.

### The S1, the reference

Properties for small state, files in the app's private dir for anything larger or secret, and
`ctl.start` / `ctl.restart` for init services. The full contract is in
[radio.md](../frames/aqara-s1-plus/docs/radio.md#root-helper-contract-v4), the threat model in
[root.md](../frames/aqara-s1-plus/research/root.md). The names below are today's (boot images up to
0.4.3, contract 4). #549 moves them to Glassworks names in the frame loader image (contract 5); the
S1's `FrameAgent` is the one place in the app that changes with them.

| Capability | Request | Status | Offered when |
|---|---|---|---|
| (the agent) | `ctl.restart s1kiosk_radio` | `sys.s1kiosk.radio.version` (version), `sys.s1kiosk.radio.source` | `init.svc.s1kiosk_radio` is set |
| `radio-mode` | `persist.s1kiosk.radio_mode`, `persist.s1kiosk.radio_zigbee`; files `files/radio/thread-dataset`, `thread-leave`, `zigbeed-8.2.2` | `sys.s1kiosk.radio.status`, `.zigbee`, `.thread_leave`, `.thread_stored`, `.otbr`, `.peer` | the agent runs |
| `radio-firewall` | `persist.s1kiosk.radio_allow` | `sys.s1kiosk.radio.allowed` | version 3 and up |
| `radio-firmware` | file `files/radio/fw-request` | `sys.s1kiosk.radio.fw`, `.fw_targets` | version 4 and up |
| `frame-bundle` | stage `files/radio/bundle-in`, `sys.s1kiosk.bundle.status=requested`, `ctl.start s1kiosk_bundle` | `/data/s1kiosk-pub/radio-bundle` (root-owned), `sys.s1kiosk.bundle.status` | `sys.s1kiosk.bundle.loader` is `1` (init sets it at boot when the service exists) and the root-owned file holds a state |
| `app-updates` | file `files/update/pending.apk`, `sys.s1kiosk.update.status=requested`, `ctl.start s1kiosk_update` | `sys.s1kiosk.update.status` | `sys.s1kiosk.update.version` is 1 or more |
| `time-zone` | `persist.s1kiosk.timezone` | `sys.s1kiosk.timezone.status` | version 4 and up |
| `hotspot-portal` | the app listens on udp 8053 and tcp 8098 | `sys.s1kiosk.portal.status` | the status is set |
| `hotspot-name` | `persist.s1kiosk.hotspot_name` (`android` undoes) | `sys.s1kiosk.hotspot_name.status` | the status is set |
| `bluetooth-address` | none | `/data/s1kiosk-pub/bluetooth-address` (root-owned) | the file holds an address. It is the adapter's own, so it stays true when an undo keeps `/data` |

Two S1 details sit outside the agent script but inside this contract. App updates and bundle
installs are separate init services (`s1kiosk_update`, `s1kiosk_bundle`; after #549, slots of the
frame loader), so a bundle can never replace the code that checks bundles. The core doesn't see the
difference: both are capabilities of the S1's agent.

## In the code

The core's side is `dev.glassworks.frame.FrameAgent`, reached as `frame.agent`:

| Member | What |
|---|---|
| `declared` | The capabilities in `frame.json`. Empty: the frame has no agent |
| `version()` | The running agent's version, or null when none runs |
| `offers(capability)` | Whether the running agent does it now. Always false for one not declared |
| `appUpdates` | The `app-updates` transport (`RootUpdates`), null when not declared |
| `bluetoothAddress()` | The `bluetooth-address` value, or null |

`NoAgent` is the frame without one: nothing declared, no version, nothing offered. Frames that don't
override `Frame.agent` get it. A pane checks `offers` (or `declared`, for a passive capability such as
`hotspot-portal`), never the frame's id.

The radio and time zone panes still use the S1's transport directly (`RadioHelper`,
`TimeZoneKeeper`): they run only where the hardware is the S1's (`silabs-radio`, the S1's own pane).
Moving them behind `FrameAgent` comes with the second frame that has a radio.

## The contract test

A frame that declares an agent must pass `FrameAgentContract`, in the style of Kiln's contract
([#621](https://github.com/GlassworksHQ/glassworks/issues/621)). Each frame with an agent has one
driver: a test class that gives the suite the frame's agent over a fake transport. The suite checks:

1. `declared` is exactly the frame's `frame.json` list, and every word is a known capability.
2. With no root side at all (nothing of the agent on the device, not even a file in `/data`), the
   agent reports no version and offers nothing, and nothing throws.
3. With the newest root side, every declared capability is offered.
4. `offers` is never true for a capability the frame doesn't declare, at any version.
5. Each declared capability goes away when its own discovery goes away (the frame names that
   discovery in its driver).
   For a file an undo can leave behind, the driver's case is that undo: this boot's marker gone, the
   file kept. Only a capability whose file stays true without the agent may still be offered then
   ([Discovery](#discovery)); the S1's `bluetooth-address` is the one today, and its driver keeps
   the file in every case but its own.

A gate fails the build when a `frame.json` declares an agent with no driver, or a driver has no
`frame.json` agent. Frames without an agent need nothing.

## Adding an agent to a frame

1. Write down what it does in the frame's docs: transport, threat model, undo, with the evidence
   (CLAUDE.md, *Document the break-in work*).
2. Add `agent.capabilities` to the frame's `frame.json`.
3. Implement `FrameAgent` in the frame's code and return it from `Frame.agent`.
4. Add its contract driver.
5. A capability not in the table above is a change to this page first.
