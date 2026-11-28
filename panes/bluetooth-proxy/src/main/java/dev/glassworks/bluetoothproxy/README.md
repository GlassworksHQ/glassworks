<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-pane-on-dark.svg">
      <img alt="Glassworks pane" src="https://www.glassworks.dev/brand/banner-pane-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Bluetooth proxy pane</h1>

<p align="center">
  <b>The panel as an ESPHome Bluetooth proxy: Home Assistant hears the BLE sensors near it.</b><br>
  Listening only, off by default, found by Home Assistant with no setup there.
</p>

<p align="center">
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-pane-on-dark.svg">
      <img alt="Glassworks pane" src="https://www.glassworks.dev/brand/badge-pane-on-light.svg" height="30">
    </picture>
  </a>
  <a href="../../../../../../../../pane-api/src/main/java/dev/glassworks/README.md#licence">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-on-dark.svg">
      <img alt="License: MPL-2.0" src="https://www.glassworks.dev/brand/badge-license-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-stable-on-dark.svg">
    <img alt="Status: stable" src="https://www.glassworks.dev/brand/badge-status-stable-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

While it is on, the panel answers Home Assistant as an ESPHome Bluetooth proxy (#121). HA adds it as
a Bluetooth scanner by itself, and hears the thermometers, plant sensors and tags near the panel
through it. The work is the [bluetooth-proxy library](../../../../../../../../libs/bluetooth-proxy/README.md):
one long-running scan, only while HA listens, within Android's limits. This pane is the glue.

| | |
|---|---|
| `BluetoothProxyDefinition` | The on/off (`bluetooth_proxy_enabled`, off by default), the setup card (none without Bluetooth LE), the settings page, and the model the page reads. |
| `BluetoothProxyPane` | Gives the engine the device's ESPHome server while the pane is on, and takes it back when it goes off. In "Listen and connect" it also runs the connection engine (#246). Each of these changes what DeviceInfo says, so Home Assistant reconnects. Adds *Bluetooth advertisements* and *Bluetooth proxy status* (diagnostic). |
| `ProxyPhase` | What the proxy is doing, from the switch, Android's state and the engine's status. The page's line and card, and the status entity, all read it. |
| `ProxyPage` | The page's words, once: its line, card, tiles, note and entities. The frame's view draws them, and `twin` gives the same page as typed items for the Manage page (#269). |
| `ProxyRadio`, `AndroidProxyRadio` | The device's Bluetooth: the scanners and the GATT radio (sharing the devices heard), why it can't scan, and turning Bluetooth and location on. |
| `BluetoothProxyPrefs` | The pane's keys: on/off, `bluetooth_proxy_connect` ("Listen and connect", off by default), and `bluetooth_proxy_adapter_mac` (the adapter's MAC first sent, kept). |
| `AdapterMac` | The Bluetooth adapter's MAC for Home Assistant (`bluetooth_mac_address`, #246), from where the frame says (the S1: its root helper's file). Settled once per process, the first valid one kept for good; any failure (none, an error, no answer in 2 s, a placeholder) sends none, and HA keys the scanner on the device's ESPHome MAC. |

The definition takes the page as a factory and hands it a `BluetoothProxyModel`. Both apps give it
`BluetoothProxyPage`, as approved on #121 (it moved here from the S1 app when the generic app took the
pane): a status line, a card when Bluetooth or location is off, the advertisements, scanner and connections
tiles, then the pane's entities as Home Assistant shows them. Its words come from `ProxyPage`, and the
section's native item carries `ProxyPage.twin`, so the Manage page shows the same page with its own
rows, and its card's button turns Bluetooth or location on from a browser too.

In "Listen and connect" (#246) the page also has the *Connections* tile and the mode pick. While the
pane is on but Bluetooth, location or the permission stops it, the section's `status` says Can't run
with the reason; `unavailable` stays for no Bluetooth LE, which hides the switch. The entities are in
[docs/esphome.md](../../../../../../../../docs/esphome.md#bluetooth-proxy).
