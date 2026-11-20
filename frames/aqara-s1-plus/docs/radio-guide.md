# Using the Aqara S1 Plus Zigbee and Thread radio

The Aqara Panel Hub S1 Plus has a built-in Silicon Labs EFR32 802.15.4 radio. Glassworks lets you hand that radio to Home Assistant in one role at a time—either from the panel's own settings menu or from the panel's ESPHome device page in Home Assistant.

This page is the everyday setup guide. For deep technical details on firmware, firewall rules, and the root helper, see the [radio technical reference](radio.md).

All active radio modes require the panel's root helper (installed automatically by [Kiln](install.md)). Because most people configure their radio once and leave it alone, Home Assistant creates all radio controls except **Radio status** disabled by default. You can either enable the controls you need on the ESPHome device page ([how](../../../docs/esphome.md#categories-and-defaults)) or configure everything directly from the panel's settings menu ([below](#on-the-panel)) without enabling extra entities in Home Assistant.

## Pick a mode

Choose a role using **Radio mode** in Home Assistant or under **Settings → Radio** on the panel:

| Mode | Role | Status |
|---|---|---|
| **Off** (default) | Radio stays idle and Aqara's stock radio services remain stopped | Ready |
| **Zigbee coordinator (TCP)** | Hosts a Zigbee network over your LAN for ZHA or Zigbee2MQTT | Works with ZHA; experimental with Zigbee2MQTT |
| **Zigbee router (repeater)** | Joins an existing Zigbee network to extend its mesh range | Experimental (tested joining a home network) |
| **Thread border router** | Runs a Thread border router for Home Assistant's OpenThread Border Router integration | Works |
| **Thread router (repeater)** | Joins an existing Thread network to extend its mesh range | Experimental (tested joining a home network) |

Note the distinction between the two Thread roles: **Thread border router** creates and routes a Thread network to your LAN, while **Thread router (repeater)** simply joins a Thread network you already have (from Home Assistant's OpenThread Border Router or another border router) to extend coverage.

*(If you have automations written before v0.4.3, note that the repeater options were renamed from "Zigbee router" and "Thread router" to include "(repeater)".)*

Whenever you change modes or troubleshoot a connection, check **Radio status** first—it reports what the radio is running and which clients are allowed to connect. Keep in mind that ZHA supports only one Zigbee coordinator per instance, and this radio cannot run Zigbee and Thread simultaneously.

## On the panel

Open the hidden settings menu by tapping the top-left corner of the screen five times, then select **Radio**. From there you can:

- Toggle the radio on or off using the top-right switch (turning it off prompts for confirmation first).
- Pick your role grouped by protocol—**Zigbee** (*Coordinator* or *Repeater β*) or **Thread** (*Border router* or *Repeater β*). Switching roles asks for confirmation because it restarts the radio.
- Follow the tailored next steps for your active role, including a QR code your phone can scan to jump straight to the right Home Assistant setup page, plus role-specific buttons (**Zigbee stack**, **Join / Leave Zigbee network**, and **Leave Thread network**).
- Configure [who may connect](#who-may-connect) to the radio's network ports.

Every button on the panel performs the exact same action as its matching Home Assistant control. While Home Assistant controls require an encrypted ESPHome connection, physical access to the panel authorizes changes directly.

## Zigbee coordinator with ZHA

1. Set **Radio mode** to **Zigbee coordinator (TCP)** and wait until **Radio status** starts with `ok`.
2. In Home Assistant, add the **Zigbee Home Automation (ZHA)** integration. Choose radio type **EZSP** and enter the serial path `socket://<panel IP>:6638` (also displayed in **Radio status**).
3. When ZHA asks how to initialize the network, select **Keep the radio's network settings**. *(Creating a brand-new network or restoring a backup hangs on older ZHA builds until upstream fix [zigpy/bellows#774](https://github.com/zigpy/bellows/pull/774) lands in a Home Assistant release.)*

## Zigbee coordinator with Zigbee2MQTT (experimental)

Zigbee2MQTT requires a newer Zigbee stack than the one Aqara ships. Make sure your panel is running Glassworks 0.4.0 or newer along with the matching boot software (check the **Radio helper** sensor in Home Assistant, and see [Updating](install.md#updating) if it needs a refresh). Network formation, restarts, and pairing work on the panel, though long-term multi-device stability is still being tested.

1. Set **Zigbee stack** to **Silicon Labs 8.2.2 (EZSP 18, Zigbee2MQTT, experimental)**. The panel downloads and verifies the stack directly from Silicon Labs' GitHub repository (we cannot bundle it due to licensing). Watch **Radio status** until it reports `EZSP 18`.
2. Set **Radio mode** to **Zigbee coordinator (TCP)**.
3. Configure Zigbee2MQTT's serial section:
   ```yaml
   serial:
     adapter: ember
     port: tcp://<panel IP>:6638
   ```
4. If Zigbee2MQTT runs on a different machine than Home Assistant (Home Assistant add-ons share the same host), add that machine's IP address to **Radio allowed clients** (see [Who may connect](#who-may-connect)).

ZHA works with the Silicon Labs 8.2.2 stack as well, and switching back to **Aqara 7.3.3** restores Aqara's stack and network untouched. Zigbee router mode always uses Aqara's stack.

> [!TIP]
> Zigbee2MQTT waits 5 seconds for a radio reset reply, and the Silicon Labs stack takes around 4.3 seconds to respond. If Zigbee2MQTT occasionally fails to start with a reset timeout, simply restart it and it will usually connect cleanly on the next try.

## Thread border router

> [!IMPORTANT]
> **Same network required:** Your panel must be on the same Wi-Fi/VLAN as Home Assistant with IPv6 enabled (standard on most home routers). A Thread border router advertises routes only on its local subnet; if the panel sits on an isolated IoT VLAN, Home Assistant can connect to the OTBR API but cannot reach your Thread devices through it. See [why](radio.md#same-network-as-home-assistant).

1. Set **Radio mode** to **Thread border router**.
2. In Home Assistant, add the **OpenThread Border Router** integration and enter `http://<panel IP>:8081`. The panel's **Radio** settings page also displays this URL alongside a QR code that opens the setup dialog on your phone.

By default, this mode forms a new Thread network—verified both as a standalone border router with Matter devices and alongside existing Home Assistant or Apple border routers, recovering cleanly after Wi-Fi drops and reboots. If you already run a Thread network and just want to extend its range, use **Thread router (repeater)** below instead. Joining the panel as a second border router on an existing Thread network currently requires manual CLI steps ([see the technical reference](radio.md#thread-border-router-build-profilebr-2026-11-10)).

If the panel forms a separate Thread network while you already have one, both appear in Home Assistant under **Settings → Devices & services → Thread**—just keep your primary network marked as preferred.

## Repeater modes (experimental)

### Zigbee router (repeater)

1. Set **Radio mode** to **Zigbee router (repeater)**.
2. Open pairing in ZHA (*Add device*—you can scan the QR code on the panel to open it quickly).
3. While ZHA is listening, tap **Join Zigbee network** on the panel or in Home Assistant. ZHA adds the panel as a Zigbee router.
4. To leave later, tap **Leave Zigbee network** and remove the device from ZHA.

### Thread router (repeater)

1. Set **Radio mode** to **Thread router (repeater)**.
2. Send your preferred Thread network credentials to the panel using either method:
   - **Automatic (easiest):** Tap **Get it from Home Assistant** on the panel's Thread router card (or press **Join Home Assistant's Thread network** in Home Assistant). If the panel is signed in to Home Assistant as an administrator, it fetches your preferred Thread network automatically. Otherwise, scan the one-time QR code with your phone and sign in as a Home Assistant administrator; the panel reads the dataset once and immediately revokes that temporary login. Check **Thread join** in Home Assistant to follow progress.
   - **Manual copy:** Open Home Assistant's **Thread** page, tap the info icon (ⓘ) under your **Preferred network**, copy **Active dataset TLVs**, and paste it into the panel's **Thread dataset** field under **Configuration** in Home Assistant (or under **Advanced** on the panel, which also supports datasets longer than Home Assistant's 255-character limit). Extra spaces or line breaks are stripped automatically, and the field is write-only.
3. Watch **Radio status**—it starts as *joined as a child* and promotes to *as a router* within a few minutes.

Press **Leave Thread network** (requires boot software from v0.4.0 or newer) whenever you want the panel to forget stored Thread credentials.

> [!CAUTION]
> Your Thread dataset contains your Thread network encryption key. Only paste it into the panel's dedicated field—never share it in chat messages or bug reports.

## Who may connect

Because the radio's TCP and OTBR ports do not have passwords of their own, Glassworks firewalls them to allow only your Home Assistant server by default. The panel automatically learns Home Assistant's IP address from the encrypted ESPHome connection.

To grant access to additional hosts (such as Zigbee2MQTT running on a separate server, or a laptop for testing), enter space- or comma-separated IP addresses or CIDR ranges in **Radio allowed clients** (for example, `192.0.2.10 198.51.100.0/24`), or type `any` to disable the restriction. Leave the field blank for Home Assistant only. You can edit the same list directly on the panel under **Settings → Radio → Who may connect**.

In Zigbee coordinator mode, the panel's Radio settings page also shows who is currently connected (for example, `Home Assistant · 192.0.2.20 · since 14:32` or `Nobody`), mirrored in Home Assistant via the **Radio connected client** sensor.

If **Radio status** remains stuck on `nobody yet (waiting for Home Assistant to connect over ESPHome)`, Home Assistant is likely connecting without encryption (Home Assistant older than 2026.11). Upgrade Home Assistant or add its IP address manually to **Radio allowed clients**.

## Radio firmware

The EFR32 radio runs Aqara's stock RCP firmware, which already supports every mode described above—you do not need to flash new firmware to use Zigbee or Thread.

On panels with boot software from v0.4.0 or newer, Glassworks includes a safe two-step radio flashing tool: pick an image in **Radio firmware image** and press **Flash radio firmware** within two minutes, watching progress on the **Radio firmware** sensor. If anything fails, the helper automatically restores Aqara's original firmware. No newer third-party firmware images are bundled at this time.

## Going back to Aqara's radio

Setting **Radio mode** to **Off** keeps Aqara's stock radio services stopped so the radio stays completely idle. If you want Aqara's built-in smart home hub functionality back, undo the Glassworks installation ([install.md](install.md#undo)) and reboot the panel.
