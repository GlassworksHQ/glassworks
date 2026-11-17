# Setup hotspot: can it pop up the setup page, and can it be called Glassworks?

**Status (2026-11-16): partly working (read and untested for the new parts).** The setup hotspot (#197) works on
the S1: an iPhone joined from the QR code and reached the setup page by its address. Two things the maintainer
wants don't work yet:

- the phone doesn't pop the page up by itself (no captive portal);
- the network is named by Android (`AndroidShare_NNNN`), not by us.

This note says, per kind of device, what it would take. Nothing here was run on a device: the claims come from
the S1 panel run of 2026-11-16 (**verified**) and from reading AOSP `android10-release` and newer (**read**).

Devices: Aqara S1 Plus (Android 10, root through the boot patch); generic Android 8+ (no root); the lululemon
Mirror (Android 6.0, our image, the app privileged).

## Question

1. **Captive portal.** When a phone joins the setup hotspot, can it show its "sign in to network" sheet with the
   setup page, as the maintainer asked (#38)?
2. **The name.** Can the hotspot be called `Glassworks-XXXX` instead of Android's `AndroidShare_NNNN`?

## What a phone needs for the sign-in sheet

A phone shows the sheet when its connectivity check over the new network gets an answer other than the one it
expects. Sources (**read**, 2026-11-16):
- Android: [`NetworkMonitor.java`](https://cs.android.com/android/platform/superproject/+/android10-release:packages/modules/NetworkStack/src/com/android/server/connectivity/NetworkMonitor.java)
  (`isCaptivePortal`, `sendHttpProbe`: a 204 is valid, a 3xx or a 200 with content is a portal) and the probe URLs in
  [`NetworkStack/res/values/config.xml`](https://cs.android.com/android/platform/superproject/+/android10-release:packages/modules/NetworkStack/res/values/config.xml)
  (`config_captive_portal_http_url`, `config_captive_portal_fallback_urls`).
- iOS: Apple doesn't document its check. `captive.apple.com/hotspot-detect.html` answering a page with `Success`
  is what iOS fetches, as seen from any network that logs it. Treat that row as **read from observed behaviour**,
  not from a specification.

| Phone | Check | Expects | Sheet when |
|---|---|---|---|
| iOS | `http://captive.apple.com/hotspot-detect.html` | a page saying `Success` | any other page, for example a 302 to the setup page |
| Android 10 | `http://connectivitycheck.gstatic.com/generate_204`, with the fallbacks `http://www.google.com/gen_204` and `http://play.googleapis.com/generate_204` (NetworkStack `res/values/config.xml`, lines 20–24) | HTTP 204 | a 200 with content, or a 302 |

Both checks need two things on the hotspot:

1. **DNS:** an answer for those names, pointing at the panel's address on the hotspot.
2. **HTTP on port 80** at that address, answering the check with a redirect to `http://<address>:8099/setup`.

What the phone gets today on Android 10's local-only hotspot:
- An address from Android's own DHCP, with the panel's `wlan0` as gateway: the S1 run saw `wlan0` take
  `192.168.43.x/24` (**verified**, 2026-11-16). What DNS server the DHCP hands out, and whether anything answers
  on it, is **untested**: open question 1 is the read-only check.
- No upstream: a local-only hotspot shares no internet, and nothing of ours listens on port 80. The S1 run fits
  that: the iPhone stayed joined, used mobile data for everything else, reached the page only by its address,
  and showed no sheet (**verified**).

## What we tried

1. **The S1 panel run of #418 on e7d2d49d** (2026-11-16, **verified**, by the root, with the maintainer's iPhone).
   The hotspot started and showed its QR code. The iPhone, with 5G mobile data on, scanned it, joined, and reached
   `http://<panel>:8099/setup` by the address on the screen. No sign-in sheet came up. The network was
   `AndroidShare_NNNN`. `wlan0` (no `wlan1` or `ap0`) took a `192.168.43.x/24` address. The commands are in the
   #197 panel plan; the logs aren't quoted here because they carry the network's name and key.
2. **Reading AOSP** (2026-11-16, **read**): `NetworkMonitor` and NetworkStack's `config.xml` for the checks;
   `WifiApConfigStore` for the generated name; `WifiServiceImpl` and `WifiApConfigStore` at android16-release for
   the Android 16 call, after the #420 verifier corrected the first reading.
3. **Android's reference** for `startLocalOnlyHotspotWithConfiguration` (2026-11-16, **read**): public, API 36,
   with its permissions. It says nothing about which fields an app's configuration keeps; the source above does.

## Per kind of device

| Device | Captive portal | Named `Glassworks-XXXX` |
|---|---|---|
| **S1** (root) | **Possible**: root redirects UDP 53 and TCP 80 on `wlan0` to ports the app listens on | **Possible, untested**: a resource overlay of Android's default name, shipped with the root side |
| **Generic Android 8+** (no root) | **No**: an app can't listen below port 1024 or change the DHCP's DNS | **No**: no API an app may call keeps a name it gives (Android 16's public one keeps only band and channel, below) |
| **Mirror** (Android 6.0, our image) | **Possible with the image**: the same redirect as the S1, from an init script in our image | **Already**: the hidden `setWifiApEnabled` takes our own config (`HiddenApHotspot`, #413) |

### S1: the captive portal (read, untested)

- An app can't listen on ports 53 or 80 (below 1024 needs root). It can listen on unprivileged ones: `8099` is the
  setup page already, and a small DNS responder could listen on, say, `8053`.
- The root side redirects the hotspot's traffic to them while the hotspot runs:
  ```
  iptables -t nat -I PREROUTING -i wlan0 -p udp --dport 53 -j REDIRECT --to-ports 8053
  iptables -t nat -I PREROUTING -i wlan0 -p tcp --dport 80 -j REDIRECT --to-ports 8099
  ```
  Removed again when it stops. `PREROUTING` runs before local delivery, so this wins over whatever Android's own
  DNS forwarder listens on (**read**, netfilter; **untested** next to netd's own tether chains).
- The app answers every DNS `A` query with the hotspot's address, and answers the check paths
  (`/hotspot-detect.html`, `/generate_204`, any other path on port 80) with a 302 to `/setup`.
- The contract: the app asks through a property, as the radio does (`sys.s1kiosk.*`, see the root helper contract
  in `frames/aqara-s1-plus/docs/radio.md`). For example `setprop sys.s1kiosk.portal on|off`, with the interface
  and port as fixed values in the helper. The helper also removes the rules at boot and when the app stops.
- Size: a root helper change (hard rule 5: it needs a real panel before merge), a DNS responder in the core with
  tests, the HTTP redirect in `LanSetup`, and a panel run with an iPhone and an Android phone. Not small, so it
  goes in its own PR after #418.

### S1: the name (read, untested)

- Android 10 makes a new local-only network on every start: `<name>_<4 random digits>`, with a random WPA2 key
  (**read**, `WifiApConfigStore.generateLocalOnlyHotspotConfig`). `<name>` is the framework string
  `wifi_localhotspot_configure_ssid_default` ("AndroidShare"). The S1 run saw `AndroidShare_NNNN`.
- No public or hidden call takes our own name for a local-only hotspot on Android 10. Newer Android has ways for
  system code only (below, under Generic Android). None reaches the S1's Android 10.
- With root: a static resource overlay of that string ("Glassworks") would give `Glassworks_NNNN`. On Android 10,
  an overlay of a framework string that isn't declared overlayable must sit on a system partition. The boot
  patch's Magisk side can add it without writing `system`. That is root-side work too (rule 5), and it changes the
  name of Android's own hotspot setting's default as well (**untested**: whether the S1's settings ever show it).
- Starting the access point ourselves as root (`startSoftAp`) could take any name. But it leaves the local-only
  path: tethering, DHCP and the stop all become ours. #38 already judged that too fragile.

### Generic Android (read)

- **Captive portal:** not possible without root: the app can't take ports 53 and 80, nor change the DNS Android's
  DHCP gives out.
- **The name:** not possible on any version, for an app without system priority. In detail (**read**, 2026-11-16;
  corrected after the #420 verifier):
  - **Android 13 to 15:** the overload of `startLocalOnlyHotspot` that takes a `SoftApConfiguration` is
    `@SystemApi`, with `anyOf = {NETWORK_SETTINGS, NETWORK_SETUP_WIZARD, NEARBY_WIFI_DEVICES}`. The permission isn't
    what stops an app: the hidden-API rules keep a `@SystemApi` out of an ordinary app's reach.
  - **Android 16:** `WifiManager.startLocalOnlyHotspotWithConfiguration(SoftApConfiguration, Executor,
    LocalOnlyHotspotCallback)` is public (API 36, `@FlaggedApi(FLAG_PUBLIC_BANDS_FOR_LOHS)`, `CHANGE_WIFI_STATE` and
    `NEARBY_WIFI_DEVICES`;
    [`WifiManager` reference](https://developer.android.com/reference/android/net/wifi/WifiManager#startLocalOnlyHotspotWithConfiguration(android.net.wifi.SoftApConfiguration,%20java.util.concurrent.Executor,%20android.net.wifi.WifiManager.LocalOnlyHotspotCallback))).
    But it doesn't keep the name. `LohsSoftApTracker.startForFirstRequestLocked` sets
    `mIsExclusive = customConfig != null && priority >= PRIORITY_SYSTEM` (`WifiServiceImpl.java`, lines 2934–2936,
    android16-release). A request that isn't exclusive gets `WifiApConfigStore.generateLocalOnlyHotspotConfig`
    (`WifiApConfigStore.java`, lines 502–570): only the bands and channels carry over, with a random passphrase
    and the SSID `AndroidShare_NNNN`. For an app it is a band and channel API. CodeRabbit's first reading on #420,
    which this note briefly followed, was wrong.

In every case without the portal, the phone reaches the page through the QR code and the address, as on the S1
today.

### Mirror (read)

- The name already works: `HiddenApHotspot` names the network `Glassworks-XXXX`.
- The captive portal would need the same two redirects as the S1, from our image (an init service while the AP runs).
  That goes with the Mirror's image work (#198, #40). Its client addresses come from Android 6's tethering
  (`dnsmasq` there, **read**), so the redirect is the same idea.

## Result

| | S1 | Generic | Mirror |
|---|---|---|---|
| Captive portal | Root redirect plus a DNS responder in the app: its own PR, needs a panel run | Not possible | With its image |
| `Glassworks` name | Root-side resource overlay: possible, untested, its own PR | Not possible (Android 16's public call keeps only band and channel for an app) | Done |

## How to reproduce

Read-only on the panel, with a setup hotspot up (the #197 panel plan says how to start one from the setup screen;
it changes no setting, and **Close it** ends it):

```sh
adb shell dumpsys wifi | grep -iE 'softap|localonly' | head      # the hotspot is up, and its band
adb shell ls /sys/class/net                                      # which interface: wlan0 on the S1
adb shell ip -4 addr show wlan0                                  # its 192.168.43.x/24 address
adb shell ss -lunp 2>/dev/null | grep ':53 '                     # what (if anything) answers DNS (open question 1)
```

Then join from a phone with mobile data on, and note whether a sign-in sheet comes up. Nothing here writes to the
panel. Don't copy the network's name or key into notes: they are on the panel's screen.

## Open questions

1. Does netd on the S1 keep its own DNS forwarder on `192.168.43.1:53` for a local-only hotspot, and does a
   `PREROUTING` redirect win over it? (Check `ss -lunp` and `iptables -t nat -S` on the panel while the hotspot
   runs. Read-only.)
2. Does the S1's radio scan while it is the access point? The S1 run's list was empty, but the radio was off then.
   #418 now scans just before it starts the hotspot and serves that list, so this matters less; the retest
   still notes it.
3. Does an iPhone show the sheet for a network with no internet, once the check is answered with a redirect? The
   check says it does (**read**). Untested here.
