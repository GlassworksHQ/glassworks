# Setup hotspot: can it pop up the setup page, and can it be called Glassworks?

**Status (2026-11-16): partly working (read and untested for the new parts).** The setup hotspot (#197) works on
the S1: an iPhone joined from the QR code and reached the setup page by its address. Two things the maintainer
wants don't work yet:

- the phone doesn't pop the page up by itself (no captive portal);
- the network is named by Android (`AndroidShare_NNNN`), not by us.

This note says, per kind of device, what it would take. The claims come from the S1 panel run of 2026-11-16
(**verified**), read-only probes of the same panel on 2026-11-16 (**verified**, in
[Measured on the S1](#measured-on-the-s1-2026-11-16)), and from reading AOSP `android10-release` and newer (**read**).
[Corrections](#corrections-2026-11-16) lists what changed after the first version of this note.

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
| Android | `http://connectivitycheck.gstatic.com/generate_204`, with `https://www.google.com/generate_204` in parallel and the fallbacks `http://www.google.com/gen_204` and `http://play.googleapis.com/generate_204` (Android 10's `NetworkStack/res/values/config.xml:14,17,21-22`) | HTTP 204 | any code from 200 to 399 but 204 (`common/CaptivePortalProbeResult.java:73-79`), a 302 included; a 200 of 4 bytes or fewer counts as a failure, not a portal (`NetworkMonitor.java:1651`). The HTTP probe finding a portal ends the wait at once (`NetworkMonitor.java:1708`, `1734`) |

Both checks need two things on the hotspot:

1. **DNS:** an answer for those names, pointing at the panel's address on the hotspot.
2. **HTTP on port 80** at that address, answering the check with a redirect to `http://<address>:8099/setup`.

What the phone gets today on Android 10's local-only hotspot (**read**, `IpServer`, `TetherController`; the
address part is **verified**):
- DHCP from Android's own server: an address in `192.168.43.x/24`, gateway and DNS `192.168.43.1`. The S1 run saw
  `wlan0` take `192.168.43.x`.
- No upstream: a local-only hotspot shares no internet, so DNS gets no useful answer, and nothing listens on port
  80. **Verified** in the S1 run's log: netd starts `dnsmasq` 2.51 for the hotspot (`TetherController: Starting
  tethering services`, then `dnsmasq : warning: no upstream servers configured`), so every lookup fails, and the
  phone never sends a check a redirect could answer. So the phone decides there's no internet and shows no sheet.
- IPv6 too: Android sends router advertisements on the hotspot (`RouterAdvertisementDaemon: RA sendto ff02::1%wlan0`,
  **verified**), whose RDNSS is the AP's own ULA address (`IpServer.java:536-538`, **read**), and dnsmasq listens there
  (`Closing listener [fdxx:…]:53` when it stops, **verified**). A phone may ask that one, so the portal must answer DNS
  over IPv6 as well. That matches the S1 run: the iPhone stayed
  joined, used mobile data for everything else, and reached the page only by its address.

## Per kind of device

| Device | Captive portal | Named `Glassworks-XXXX` |
|---|---|---|
| **S1** (root) | **Possible**: root redirects UDP 53 and TCP 80 on `wlan0` to ports the app listens on | **Possible, untested**: a resource overlay of Android's default name, shipped with the root side |
| **Generic Android 8–15** (no root) | **No**: an app can't listen below port 1024 or change the DHCP's DNS | **No**: the public API takes no name |
| **Generic Android 16+** (no root) | **No**, as above | **No**: the public `startLocalOnlyHotspotWithConfiguration` (API 36) keeps a name only from a system app; for ours Android keeps just the channels ([below](#generic-android-read)) |
| **Mirror** (Android 6.0, our image) | **Possible with the image**: the same redirect as the S1, from an init script in our image | **Already**: the hidden `setWifiApEnabled` takes our own config (`HiddenApHotspot`, #413) |

### S1: the captive portal (read, untested)

- An app can't listen on ports 53 or 80 (below 1024 needs root). It can listen on unprivileged ones: `8099` is the
  setup page already, and a small DNS responder could listen on, say, `8053`.
- The root side redirects the hotspot's traffic to them while the hotspot runs. **Corrected:** not to `8099`. The
  web server there answers only to the panel's own names (`ManageServer.hostAllowed`, against DNS rebinding), so a
  check with `Host: captive.apple.com` gets `400 Open this page with the panel's address.` Android doesn't take a
  400 as a portal (200 to 399 only), and iOS would show that error. The redirect is its own small server (`8098`).
  The rules also name the subnet and the panel's address, and IPv6 DNS goes to the app too:
  ```
  iptables  -t nat -A S1PORTAL -s 192.168.43.0/24 -d 192.168.43.1/32 -i wlan0 -p udp --dport 53 -j DNAT --to-destination 192.168.43.1:8053
  iptables  -t nat -A S1PORTAL -s 192.168.43.0/24 -d 192.168.43.1/32 -i wlan0 -p tcp --dport 80 -j DNAT --to-destination 192.168.43.1:8098
  ip6tables -t nat -A S1PORTAL -d <AP's ULA>/128 -i wlan0 -p udp --dport 53 -j DNAT --to-destination [<AP's ULA>]:8053
  ```
  Removed again when it stops. `PREROUTING` runs before local delivery, so this wins over whatever Android's own
  DNS forwarder listens on (**read**, netfilter; **untested** next to netd's own tether chains).
- The app answers every DNS `A` query with the hotspot's address, and answers the check paths
  (`/hotspot-detect.html`, `/generate_204`, any other path on port 80) with a 302 to `http://<address>:8099/setup`.
- **Corrected, the contract:** no property. On the S1 any app may set properties (SELinux is permissive), and the
  root helpers take no input but files the kiosk owns (`frames/aqara-s1-plus/tools/AGENTS.md`). The helper keys off
  what it can check itself: hostapd runs, the kiosk's uid owns the DNS and redirect sockets on one address, and that
  address is on a Wi-Fi interface. It removes the rules as soon as one of those stops, and at every start.
- Built in a root-helper PR (stacked on #418), which needs a panel run (hard rule 5).

### S1: the name (read, untested)

- Android 10 makes a new local-only network on every start: `<name>_<4 random digits>`, with a random WPA2 key
  (**read**, `WifiApConfigStore.generateLocalOnlyHotspotConfig`). `<name>` is the framework string
  `wifi_localhotspot_configure_ssid_default` ("AndroidShare"). The S1 run saw `AndroidShare_NNNN`.
- No public or hidden call takes our own name for a local-only hotspot on Android 10. From Android 11 a system
  API does (`NETWORK_SETTINGS` or `NETWORK_SETUP_WIZARD`, signature permissions an app doesn't get), and from
  Android 16 a public one does (below). Neither reaches the S1's Android 10.
- With root: a static resource overlay of that string ("Glassworks") would give `Glassworks_NNNN`. On Android 10,
  an overlay of a framework string that isn't declared overlayable must sit on a system partition. **Confirmed**:
  - the S1's `framework-res.apk` declares no overlayable (`aapt2 dump overlayable` is empty, **verified**) and is
    signed with Aqara's own key, not AOSP's test key (`apksigner verify --print-certs`, **verified**);
  - an overlay in /data that targets Q gets idmap2's check, which for such a target needs a preinstalled overlay or
    the target's signature (`IdmapManager.java:187-192`, `cmds/idmap2/libidmap2/Idmap.cpp:281-297`, **read**); one
    that targets less is refused at install without the platform key (`PackageManagerService.java:12015-12052`,
    **read**). So `pm install` plus `cmd overlay enable` can't do it;
  - the string is `AndroidShare` at `0x01040793` in the S1's framework-res (`aapt2 dump resources`, **verified**). The boot
  patch's Magisk side can add it without writing `system`. That is root-side work too (rule 5), and it changes the
  name of Android's own hotspot setting's default as well (**untested**: whether the S1's settings ever show it).
- Starting the access point ourselves as root (`startSoftAp`) could take any name. But it leaves the local-only
  path: tethering, DHCP and the stop all become ours. #38 already judged that too fragile.

### Generic Android (read)

- **Captive portal:** not possible without root: the app can't take ports 53 and 80, nor change the DNS Android's
  DHCP gives out.
- **The name, Android 8 to 15:** not possible. The hotspot keeps Android's (or the maker's) name.
- **The name, Android 16+: corrected, not possible for our app.**
  `WifiManager.startLocalOnlyHotspotWithConfiguration(SoftApConfiguration, Executor, LocalOnlyHotspotCallback)`,
  added in API 36, is public and needs `CHANGE_WIFI_STATE` and `NEARBY_WIFI_DEVICES`
  ([`WifiManager` reference](https://developer.android.com/reference/android/net/wifi/WifiManager#startLocalOnlyHotspotWithConfiguration(android.net.wifi.SoftApConfiguration,%20java.util.concurrent.Executor,%20android.net.wifi.WifiManager.LocalOnlyHotspotCallback))).
  But Android uses the name in that config only for a system app (**read**, `packages/modules/Wifi`):
  - API 36's public `SoftApConfiguration.Builder` has only `setChannels` (`framework/api/current.txt:147-151`,
    `android16-release`). 36.1 adds a public `setWifiSsid` and `setPassphrase`, under the flag
    `public_ssid_bssid_passphrase_for_do_carrier_tethering`: "for DO/Carrier app tethering"
    (`flags/wifi_flags.aconfig:318-325`, `android16-qpr1-release`).
  - The config counts as "exclusive" only when the requester's priority is at least `PRIORITY_SYSTEM`
    (`WifiServiceImpl.java:2934-2936` on `android16-release`, `2992-2996` on `android16-qpr1-release`, `3059-3061` on
    `android16-qpr2-release`). That priority means `FLAG_SYSTEM` (`WorkSourceHelper.java:151-159`).
  - Not exclusive, `WifiApConfigStore.generateLocalOnlyHotspotConfig` keeps only the config's channels, and makes its
    own name and key (`WifiApConfigStore.java:502-515` on `android16-release`).

  A user-installed app gets Android's name on 16 as on 8 to 15. Nothing to build.

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
| `Glassworks` name | Root-side resource overlay, preinstalled (on a system partition, e.g. through a Magisk module): possible, untested, its own PR | Not possible on any version for a user app (on 16, only a system app's name is kept) | Done |

## Open questions

1. ~~Does netd on the S1 keep its own DNS forwarder on `192.168.43.1:53`?~~ Yes, `dnsmasq` with no upstream
   (verified, above). Whether the `PREROUTING` DNAT wins over it on the panel is the portal PR's panel run.
2. Does the S1's radio scan while it is the access point? The S1 run's list was empty, but the radio was off then.
   If it can't scan, the panel should scan once just before it starts the hotspot and serve that list.
3. Does an iPhone show the sheet for a network with no internet, once the check is answered with a redirect? The
   check says it does (**read**). Untested here.

## Measured on the S1 (2026-11-16)

Read-only, on the dev panel (Android 10, kernel 4.9.170, Magisk 31.1), with `adb shell` and `su -c` reads:

```
$ adb shell 'su -c "iptables-save -t nat"'          # oem_nat_pre, tetherctrl_nat_POSTROUTING; nothing else
$ adb shell 'su -c "ip6tables-save -t nat"'         # the IPv6 nat table exists, empty
$ adb shell 'su -c "cat /proc/net/ip_tables_targets /proc/net/ip6_tables_targets"'   # DNAT and REDIRECT in both
$ adb shell 'su -c "zcat /proc/config.gz"' | grep -E 'IP_NF_NAT|IP6_NF_NAT|NF_NAT_REDIRECT'   # all =y
$ adb shell 'su -c "iptables -V"'                    # v1.6.1
$ adb shell 'cat /proc/sys/net/ipv4/ip_unprivileged_port_start'   # no such file: no way for the app to bind < 1024
$ adb shell 'su -c "cmd wifi help"'                  # Android 10's has no softap command
$ adb shell 'su -c "ls /data/adb/modules"'           # exists, empty
$ adb shell cmd overlay list                         # AOSP's theme and cutout overlays only, none of ours
```

Ports 53, 80, 8053 and 8098 were free in `/proc/net/{tcp,tcp6,udp,udp6}`.

## Corrections (2026-11-16)

| First version said | Now | Why |
|---|---|---|
| Android 16+ can name the hotspot through the public API | No, for a user app | Android keeps the name only from a system app (above) |
| Redirect port 80 to `8099` | To a separate redirect, `8098` | `8099` refuses foreign `Host` headers with a 400 |
| Redirect on `-i wlan0` only | Also the subnet and the panel's address; IPv6 DNS too | So a rule can't match anything on another network; the RA points phones at an IPv6 DNS server |
| The app turns the portal on with a property | No input: the helper checks hostapd and the kiosk's own sockets | Any app may set properties on the S1 |
| Android's fallback checks `clients3.google.com`, `connectivitycheck.android.com` | `www.google.com/gen_204`, `play.googleapis.com/generate_204` | Android 10's `NetworkStack` config |
