---
topic: panes
evidence: verified
---

# ONVIF and RTSP: what HA and UniFi Protect need from a panel's camera, and what to build it on

**Status (2026-11-14): ported; met Home Assistant on a tablet** ([its pull loop](#home-assistants-pull-loop-on-a-refused-sign-in-2026-11-14)). First desk research for the camera pane's
opt-in stream (#117). Then the maintainer decided that Glassworks follows their own implementation,
which they tested against Home Assistant's ONVIF integration and UniFi Protect (see [Result](#result)).
HA's needs below are read from its source; UniFi Protect's from Ubiquiti's help page and an MIT proxy
whose author reports that Protect adopts it. No panel has met a Protect console yet.

Device: none. Sources, read 2026-11-13 and pinned to the commits that were current then (the files
at these commits are byte-identical to the ones read):

| Source | Commit |
|---|---|
| `home-assistant/core`, `homeassistant/components/onvif/` | `5b84714499e4` (`dev`, 2026-11-13) |
| `openvideolibs/python-onvif-zeep-async`, `onvif/client.py` (HA pins 4.3.0) | `ab5664281877` (`async`, 2026-09-28) |
| `connorgallopo/rtsp-to-onvif` (MIT; a fork of `p10tyr/rtsp-to-onvif`; its README reports Protect 7.1.60 with Hikvision-family cameras) | `d07f08add7e6` (`release`, 2026-10-01) |

## Question

With the pane's "ONVIF stream" switch on, the panel should show up in HA's ONVIF integration,
Frigate, go2rtc and UniFi Protect as a camera. What is the smallest server that does that, and do we
write it or take a library?

## What we tried

1. **What HA's ONVIF integration calls** (2026-11-13, read: `onvif/device.py`, `camera.py`,
   `config_flow.py`, and `onvif/client.py` of `onvif-zeep-async`)

   | Step | Call | Needed? |
   |---|---|---|
   | Discovery | WS-Discovery Probe with scope `onvif://www.onvif.org/Profile/Streaming`; reads the `name` and `hardware` scopes (`config_flow.py:58–98`) | For the "discovered" card. Adding by IP works without |
   | Service addresses | `GetServices`, falling back to `GetCapabilities` (`client.py` `update_xaddrs`) | Yes |
   | Capabilities | `GetCapabilities(Category=All)` for the events flags | Yes |
   | Clock | `GetSystemDateAndTime` (`device.py:184`), for the WS-Security clock offset | Yes |
   | Identity | `GetDeviceInformation`, then `GetNetworkInterfaces` for the MAC as unique id (`device.py:335–360`). A "not implemented" fault falls back to the serial | Yes, MAC optional |
   | Media | `GetServiceCapabilities` (`SnapshotUri`), `GetProfiles`; only profiles whose `VideoEncoderConfiguration.Encoding` is `H264` count, with a resolution (`device.py:412–470`) | Yes |
   | Stream | `GetStreamUri` with `RTP-Unicast` over `RTSP` (`device.py:478–486`) | Yes |
   | Snapshot | `GetSnapshotUri`, then an HTTP GET with Digest or Basic; FFmpeg on the stream otherwise (`camera.py:83–110`) | Optional |
   | Imaging, PTZ | created if offered | No |
   | Events | a PullPoint subscription, tried even if not advertised; failure is fine (`device.py` `async_start_events`) | No |
   | Auth | WS-Security `UsernameToken` with password digest (`client.py:164`) | Yes |

2. **What UniFi Protect needs** (2026-11-13, read: [Third-Party Cameras in UniFi Protect](https://help.ui.com/hc/en-us/articles/26301104828439-Third-Party-Cameras-in-UniFi-Protect),
   [rtsp-to-onvif](https://github.com/connorgallopo/rtsp-to-onvif))
   - Protect 5.0 or newer. Turn on *Discover Third-Party Cameras*; the camera must be on the console's
     subnet, or be adopted by IP (*Advanced Adoption*). It asks for the ONVIF username and password.
   - Ubiquiti names no ONVIF profile. Its notes: time sync, Digest and WS-UsernameToken auth, and
     "if streams change after adoption, remove and re-add the camera". Motion has to come from the
     camera's own events.
   - rtsp-to-onvif is a fork of [p10tyr/rtsp-to-onvif](https://github.com/p10tyr/rtsp-to-onvif), which
     built on Daniela Hase's virtual ONVIF server. Its README says Protect 7.1.60 adopts it, tested by
     its author with Hikvision-family cameras only ("Not tested: … Protect versions other than 7.1.60,
     and other brands"). That is the author's test, not ours. Its ONVIF server answers only `GetCapabilities`,
     `GetDeviceInformation`, `GetNetworkInterfaces`, `GetProfiles`, `GetServices`, `GetSnapshotUri`,
     `GetStreamUri`, `GetSystemDateAndTime`, `GetVideoEncoderConfigurationOptions` and
     `GetVideoSources`, plus a WS-Discovery `ProbeMatch` for `NetworkVideoTransmitter`
     (`src/onvif-server.js`). Events are optional there too.
   - Protect knows a camera by its MAC and ONVIF UUID ("changing it makes Protect see a new camera").
     The panel must keep both stable, and must not put a MAC in logs or the repo (AGENTS.md rule 2).
   - It labels every stream H.264, because ONVIF Media 1 has no H.265.

3. **Libraries** (2026-11-13, read: GitHub's licence field and last push, from the API)
   ```
   $ for r in pedroSG94/RootEncoder pedroSG94/RTSP-Server fyhertz/libstreaming AlexxIT/go2rtc bluenviron/mediamtx rgaufman/live555; do
       gh api repos/$r -q '"\(.full_name) \(.license.spdx_id) \(.pushed_at[0:10])"'; done
   pedroSG94/RootEncoder Apache-2.0 2026-11-13
   pedroSG94/RTSP-Server Apache-2.0 2026-09-04
   fyhertz/libstreaming Apache-2.0 2023-01-26
   AlexxIT/go2rtc MIT 2026-11-05
   bluenviron/mediamtx MIT 2026-11-13
   rgaufman/live555 LGPL-3.0 2026-07-29
   $ gh api repos/AlexxIT/go2rtc/releases/latest -q '.tag_name, (.assets[] | select(.name=="go2rtc_linux_arm") | .size)'
   v1.9.15
   5092480
   ```


   | Project | Licence | Language | Last push | What it gives us |
   |---|---|---|---|---|
   | [pedroSG94/RootEncoder](https://github.com/pedroSG94/RootEncoder) | Apache-2.0 | Kotlin | 2026-11-13 | Camera2 → `MediaCodec` H.264, RTP packetising. A client: it pushes to a server |
   | [pedroSG94/RTSP-Server](https://github.com/pedroSG94/RTSP-Server) | Apache-2.0 | Kotlin | 2026-09-04 | "Plugin of RootEncoder to stream directly to RTSP player": the server side. Only on JitPack |
   | [fyhertz/libstreaming](https://github.com/fyhertz/libstreaming) | Apache-2.0 | Java | 2023-01-26 | An RTSP server for Android, unmaintained |
   | [AlexxIT/go2rtc](https://github.com/AlexxIT/go2rtc) | MIT | Go | 2026-11-05 | RTSP and an ONVIF server, tested with HA's ONVIF integration. A second process; `go2rtc_linux_arm` 1.9.15 is 5.1 MB |
   | [bluenviron/mediamtx](https://github.com/bluenviron/mediamtx) | MIT | Go | 2026-11-13 | RTSP server, no ONVIF |
   | live555, GStreamer `rtsp-server` | LGPL | C/C++ | | Copyleft native code we'd ship. Avoid |
   | gSOAP-based ONVIF servers | GPL or commercial | C | | Ruled out |

## Result

**Superseded on 2026-11-14 by the maintainer's decision: Glassworks follows the maintainer's own,
tested implementation** in [aguilaair/android-ip-camera](https://github.com/aguilaair/android-ip-camera)
(branch `onvif-rtsp-motion`, commit `7b5e6f28`, MIT, a fork of DigitallyRefined/android-ip-camera).
It is ported, not rewritten, in `panes/camera/src/main/java/dev/glassworks/camera/onvif/`. The library
comparison above stays as the record of what was ruled out; RootEncoder and RTSP-Server are no longer
the plan.

| Part | In the port | What it does |
|---|---|---|
| RTSP | `RtspServer`, `RtspProtocol`, `H264Bitstream` | RTSP 1.0 on port 8554, H.264 as RTP (RFC 6184) over TCP-interleaved or UDP, Digest (MD5, qop=auth) and Basic auth, RTCP sender reports |
| ONVIF | `OnvifServer`, `OnvifSoap` | Profile S device, media (ver10) and media2 (ver20) services on port 8000, WS-Security UsernameToken and HTTP Digest/Basic, a snapshot URI (`/onvif/snapshot.jpg`), pull-point motion events on `tns1:RuleEngine/CellMotionDetector/Motion` and `tns1:VideoSource/MotionAlarm`, and the same events pushed to a `wsnt:Subscribe` consumer (since 2026-11-20, [below](#upstream-sync-2026-11-20)) |
| Discovery | `OnvifDiscovery` | WS-Discovery on UDP 3702 (2005/04, and 2009/01 probes answered in kind), Hello and Bye, scopes including `onvif://www.onvif.org/Profile/Streaming` |
| Motion | `MotionDetectorCore` (in `dev.glassworks.camera`) | The panel's presence and the ONVIF events both come from it: see [camera-presence.md](camera-presence.md) |

**What the maintainer tested** (the maintainer, 2026-11-13; not re-run for this note): Home
Assistant's ONVIF integration and UniFi Protect's third-party adoption. The fork's README records the
setup and two Protect notes (read 2026-11-14):

1. Home Assistant: add the camera with the ONVIF integration (host = the device's address, port
   8000), or adopt it in Protect and use the Protect integration. Both give the camera and a motion
   binary sensor.
2. Protect: on the console's network the camera shows up under third-party cameras; otherwise use
   *Try advanced adoption* with `<address>:8000` and the username and password.
3. Protect: turn **off audio recording** for the camera. The stream has no audio track, and Protect
   won't record without one while audio recording is on.
4. Protect: it needs a **motion zone** for a third-party camera before it creates motion events. If
   there is none, add a full-frame zone through the Protect API; the settings UI offers no zones for
   third-party cameras.

**What Glassworks changes** (each ported file lists its own changes in its header):

- With no sign-in set, both servers refuse every request that needs a user. Upstream let everyone
  in. A username with a blank password counts as no sign-in. The stream never runs without a
  sign-in, and there is no default shared between panels. From 2026-11-14 the pane made each panel its
  own; since 2026-11-15 (maintainer) the person sets it, because the panel no longer shows a password
  once it is saved.
- XML with a DTD is refused before it is parsed, in SOAP and in WS-Discovery: parsing runs before
  any sign-in, and Android's parser ignores upstream's `disallow-doctype-decl` flag, so an entity
  bomb or an external entity would otherwise reach it. An entity resolver refuses the rest.
- The RTSP server binds its port before it counts as running, so a port clash throws and a later
  start can still bind.
- Nothing listens until the pane starts the servers, and the pane starts them only while
  `onvif_stream` is on, the camera is in Presence + camera, and privacy is off
  (`CameraPolicy.streamMayRun`).

**Limits we accept, and what bounds them** (2026-11-14):

| Limit | Why it stays | What bounds it |
|---|---|---|
| Plain HTTP and RTSP, no TLS. Basic auth and WS-Security `PasswordText` send the password in a form anyone on the LAN can read | HA's ONVIF integration and UniFi Protect speak ONVIF over HTTP; they choose Digest or `PasswordDigest` when offered | The stream is off by default and needs its own sign-in, not the panel's or HA's. Use a password for nothing else |
| HTTP Digest (ONVIF, RTSP): a nonce stays valid for 12 h (both; an older one gets a fresh challenge), the realm and the requested resource must be the panel's, and `nc` isn't tracked, so a captured `Authorization` header can be replayed within those 12 h | ffmpeg and other RTSP clients reuse the first nonce for a whole session; refusing a reused `nc` breaks them | Replaying needs a captured header from the LAN; it gives what that client already saw. A future round can bind nonces to a connection |
| WS-Security `UsernameToken`: `Created` isn't range-checked and nonces aren't remembered, so a captured token can be replayed | Panel and client clocks differ, and HA corrects for that skew itself | As above: a LAN capture, for the operations that client already made |
| RTSP over UDP: a session ends when its control connection closes (upstream's choice), although RTSP 1.0 lets it outlive the connection | HA and Protect stream over TCP; ending with the connection stops video to an address whose client is gone | Clients that keep their control connection open, as ffmpeg does, are unaffected |

Bounds added in the camera pane PR: at most 16 RTSP connections, a 30 s read timeout on an RTSP
connection until a session plays (none after, since a playing client may stay silent), and at most 64
headers and 32 KiB in an ONVIF request head. The ONVIF server already had a 30 s read timeout and at
most 32 connection threads.

**Ports.** 8554 (RTSP) and 8000 (ONVIF), as upstream. Neither clashes with the panel's own listeners,
read from the code on 2026-11-14: 6053 (ESPHome API), 8099 (Manage page and setup sign-in), 6638 and
6639 (Zigbee over TCP), 8081 (the Thread border router's REST API, Thread mode only). Sendspin only
connects out. WS-Discovery takes UDP 3702, and RTP over UDP an even port pair between 50000 and 60000.

## How to reproduce

Read the sources at the pinned commits (read-only):

```
curl -sfL https://raw.githubusercontent.com/home-assistant/core/5b84714499e4c50e57c39f336e2790e22d3bc2fa/homeassistant/components/onvif/device.py | grep -n "await .*Get"
curl -sfL https://raw.githubusercontent.com/home-assistant/core/5b84714499e4c50e57c39f336e2790e22d3bc2fa/homeassistant/components/onvif/config_flow.py | sed -n 58,98p
curl -sfL https://raw.githubusercontent.com/openvideolibs/python-onvif-zeep-async/ab5664281877870ce2bbe1aa3dd190411b19c1ac/onvif/client.py | grep -n "update_xaddrs\|GetServices\|UsernameToken"
curl -sfL https://raw.githubusercontent.com/connorgallopo/rtsp-to-onvif/d07f08add7e61d8435b8d09dc1dc5b3dfdf0a8ed/src/onvif-server.js | grep -o "Get[A-Za-z]*" | sort -u
```

The library table's commands are in step 3.

## Home Assistant's pull loop on a refused sign-in (2026-11-14)

**Seen on a tablet (verified, 2026-11-14 10:57–10:59, the root's tablet run on build `28c157d`).** The
stream turned on with a sign-in set. Within a second, the maintainer's Home Assistant (an ONVIF entry
for the tablet's address and port 8000, left from the IP camera app the tablet ran before) sent
`Renew`, then `CreatePullPointSubscription`, about every 0.75 s: 127 Renews in about 90 s, and still
going. HA's motion sensor for the tablet stayed `on`. HA logged nothing for ONVIF at WARNING. The
panel's log showed only those two calls: no `PullMessages`, and no subscription created.

**Read (HA 2026.11.1, `homeassistant/components/onvif/event_manager.py`; onvif-zeep-async 4.3.0,
`onvif/managers.py`).** HA's PullPoint manager pulls every 0.75 s (`PULLPOINT_COOLDOWN_TIME`), with a
60 s timeout. When `PullMessages` answers with a SOAP Fault, it assumes the camera restarted and calls
`resume()`, which renews the subscription at once and, when the Renew fails too, creates a new one
(`_renew_or_restart_subscription`). Other errors wait 60 s. The renewal itself runs at 80 % of
`TerminationTime - CurrentTime`; HA asks for `PT600S`, so about every 8 minutes.

**Lab (verified, 2026-11-14):** the pane's `OnvifServer` on this computer, and HA's client driven as HA
drives it (`tools/camera/onvif-lab.sh`, `tools/camera/onvif-ha-client.py`):

| Case | What happened |
|---|---|
| The right sign-in; the server restarts at 30 s and forgets the subscription | One Fault ("Unknown subscription"), one Renew and one Create, then pulls resume. Motion arrives on and off. `TerminationTime` is `CurrentTime` + 600 s, in UTC with a `Z`; no loop |
| The server's sign-in changes at 20 s; the client keeps the old one | Every pull answers "Sender not authorized". HA renews and re-creates: 34 Renews and 35 Creates in 25 s. The server's log shows only those two calls, as on the tablet |

**What it rules out:** the timestamps. Our `CurrentTime` and `TerminationTime` are in UTC with a `Z`,
last the interval asked for, and a Renew moves the end on; `OnvifServerTest` pins all three.

**Confirmed on the tablet (verified, 2026-11-14, root and maintainer):** HA's old entry held the old
app's sign-in. About 110 Creates in a few minutes; once the maintainer re-added it with the new
sign-in (11:13), one CreatePullPointSubscription, then normal pulls. UniFi Protect, also holding an
old sign-in, polled `GetProfiles` about 59 times until it was adopted again. Both PASS after.

**How the panel answers a refused sign-in now (verified in the lab, 2026-11-14, except where the row
says otherwise):**

| Call | Answer | What HA 2026.11.1 does with it |
|---|---|---|
| Device and media (`GetDeviceInformation`, `GetProfiles`, …) | HTTP 400, SOAP 1.2 fault `s:Sender` / `ter:NotAuthorized` | `is_auth_error()` is true: "auth failed" in the config flow, a reauth flow at setup |
| Events (`PullMessages`, `Renew`, `CreatePullPointSubscription`, …) | HTTP 401 with the Digest and Basic challenge, no body | a `TransportError` 401: the pull loop waits 60 s instead of 0.75 s; reauth at setup |
| RTSP (`DESCRIBE`, `SETUP`) | `RTSP/1.0 401` with the challenge | |
| Digest with the right password on a nonce the panel forgot or that is over 12 h old (the SOAP services; the snapshot: `OnvifServerTest`, not yet met a real client) | HTTP 401, challenge with `stale=true` | the client signs in again once |

A wrong sign-in in the events now costs about two requests a minute, not one every 0.75 s: a pull
each minute, and its one retry when the client answers the 401's Digest challenge (aiohttp's
`DigestAuthMiddleware` retries once; the lab's second case again: no Renew or Create after the
change, only refused pulls). HA has no reauth path in its pull
loop; a loaded entry with a stale sign-in asks to reauth the next time it is set up (an HA restart or
a reload). The panel logs a refused sign-in once a minute per client, never with the sign-in:
`ONVIF PullMessages from <address> refused: the sign-in doesn't match the stream's`.

`tools/camera/onvif-lab.sh` reruns the pull loop; the setup and config-flow answers were read with the
same client (`ONVIFCamera(...).create_devicemgmt_service().GetDeviceInformation()` and the media and
events services, with a wrong password).

To run the lab again: `tools/camera/onvif-lab.sh 45 ONVIF_LAB_CREDS_CHANGE_AT=20` (or
`ONVIF_LAB_RESTART_AT=30`). It serves on 127.0.0.1:18000 only.

## Upstream sync (2026-11-20)

The port came from the maintainer's fork at `7b5e6f28`. Upstream (DigitallyRefined/android-ip-camera) took the
same RTSP/ONVIF work as `cb6dd73` (2026-10-24) and has moved on since. Read on 2026-11-20 at upstream `082eb025`:
every commit after `90f9f80f` that touches the RTSP, ONVIF or HTTP server, the encoder or motion (`git log
90f9f80f..origin/HEAD -- '*Onvif*' '*Rtsp*' '*H264*' '*Motion*' '*StreamingServerHelper*' '*HttpResponse*'`),
checked against our code, not only the diff. OpenTunnel, the relay, F-Droid, fastlane and dependency bumps are left
out.

| Upstream commit | Our file | Same bug or gap here? | Done |
|---|---|---|---|
| `ca0085d1` Content-Length on HTTP answers (#150) | `onvif/OnvifServer.kt` | Partly. Every ONVIF answer already had a Content-Length (upstream's close-delimited ones were in its web UI and MJPEG server, which we don't have). HEAD was wrong: the snapshot answered HEAD with `Content-Length: 0`, and a HEAD refused (401) or with no picture (503) sent its body, so a client that kept the connection read those bytes as the next answer's head | Ported: HEAD gets GET's headers, Content-Length included, and no body |
| `c58342b2` WS-BaseNotification push to NVRs (#156) | `onvif/OnvifServer.kt` | A gap: `Subscribe` faulted | Ported, with the changes below (maintainer's call, 2026-11-20) |
| `4c7dbb98` release the H.264 encoder when the camera stops (#138) | `ViewerEncoder.kt`, `H264Encoder.kt` | No. Upstream marked the encoder's surface as handed to CameraX, so its `stop()` waited for a release that never came. We use Camera2 with no GL pipe, and `ViewerEncoder.stop()` takes the surface out of the session (`CameraHub.setEncoder(null)`, bounded by `DETACH_WAIT_MS`), then calls `H264Encoder.release()`, which stops and frees the codec. `CameraStream.stopLocked()` and the pane's stop both reach it | Not needed |
| `53edaef0` no needless camera restarts, no sticky Camera1 fallback (#139) | `ViewerEncoder.kt`, `CameraHub.kt` | No. A viewer joining an encoder that runs only asks for a keyframe (`ViewerEncoder.viewers`); we have no Camera1 path; an encoder whose drain fails calls `onFailure`, and `ViewerEncoder.failed` builds a new one at most every 5 s | Not needed |
| `ee0c453d` stream resolution selection (#134) | `StreamRate.kt` (`yuvSizeFor`, `streamFitFor`) | No. Upstream capped width and height to the encoder's maxima one by one, which changed the shape. We pick from the sizes the camera lists, and ask the encoder (`isSizeSupported`) for the whole size | Not needed |
| `14e7e666` advanced encoding controls (#155) | `H264Encoder.kt`, `OnvifServer.kt` | A feature. Our `GovLength` already matches our 1 s keyframe interval | Follow-up, if wanted |
| `18d24ae6` continuous recording and notification-only modes (#149) | — | A feature for upstream's own recorder | Not for Glassworks (HA records) |
| `a323b32` manual rotation (#123), `1b47ea2` background audio (#126), `7ff72e2` start on boot (#135) | — | Upstream's GL pipe, web UI and service; we have none of them | Not needed |

**What Glassworks changes in the push port** (see `OnvifServer.kt`'s header):

- **Sign-in.** `Subscribe` is in the WS-BaseNotification namespace, so it goes through the same check as
  `CreatePullPointSubscription`: no sign-in set refuses everyone, and a refusal is the bare 401 every event call
  gets.
- **Local network only.** `pushConsumer()` takes plain `http` only, with no user info in the URL, resolves the
  host once, and keeps the first address that `localConsumer()` allows: RFC 1918 (`isSiteLocalAddress`),
  link-local IPv4 and IPv6 (`isLinkLocalAddress`), IPv6 ULA `fc00::/7`, or the subscriber's own source address.
  Loopback, wildcard and multicast addresses are refused, and so is any address of the panel's own interfaces
  (`NetworkInterface.getByInetAddress`; an address it can't check counts as the panel's). The one exception is the
  subscriber's own address: a Subscribe may always name the client that sent it, whatever that address is, and a
  client on the panel itself may name the panel. Apart from that, a Subscribe can't aim the panel at its own ports or
  at the internet. A consumer URL past 2048 characters is refused. The Notify goes to that pinned address, so a name that
  resolves elsewhere later can't move it. Anything else gets a `ter:InvalidArgVal` fault, which makes Home
  Assistant keep its pull point.
- **Its own sockets.** The Notify is one HTTP/1.1 POST on a `java.net.Socket`: no DNS lookup again, no redirects,
  no proxy, and no change to the network security config (it doesn't apply to raw sockets, and both apps already
  set `usesCleartextTraffic` for Home Assistant over HTTP). Connect timeout 2 s, then 2 s in all to write the
  request and read the status line. Reads get the time left, and a watchdog thread closes the socket 0.5 s after the
  deadline, which ends a write to a consumer that reads nothing.
- **The Notify action** is `http://docs.oasis-open.org/wsn/bw-2/NotificationConsumer/Notify`, the operation's
  port type in WS-BaseNotification 1.3's WSDL. Upstream sends `NotificationProducer/Notify`.
- **Caps.** At most 8 push subscriptions, apart from the 16 pull points, so a flood of Subscribes can't push Home
  Assistant's pull point out. At the cap, the push subscription that ends soonest goes. A push subscription ends at
  most 1 h away (Subscribe and Renew), and an expired one gets nothing more. At most 8 Notify bodies wait per
  consumer.
- **Off the caller's thread.** `setMotion` only queues. Two notifier threads send, in order per consumer, one
  Notify a turn, so a dead NVR holds one of them for at most one exchange (about 4 s) at a time and never holds up
  motion or the pull points. The first Notify (`Initialized`, the state now) goes 250 ms after Subscribe, as
  upstream. One lock covers a new subscription's cap check, insertion and first `Initialized`, and each motion
  change, so no consumer gets an old state after a newer one and concurrent Subscribes can't pass the cap.
- **The log** names the consumer's host and port, never its path: Home Assistant's webhook path is the webhook's
  key.

**Home Assistant uses push too (read, 2026-11-20).** `homeassistant/components/onvif/event_manager.py` at
`6a6661b90e7e` (`dev`): `EventManager.async_start` starts the pull point first, then the webhook manager whenever
webhooks are on in the entry's options (the default), whatever `WSSubscriptionPolicySupport` says. The webhook
manager subscribes with `get_url(prefer_external=False)` plus `/api/webhook/<id>` and then calls
`SetSynchronizationPoint`. When the first Notify parses, `async_webhook_working` pauses the pull point (it lets the
pull point expire rather than unsubscribing). Until now our Subscribe faulted, so Home Assistant stayed on the pull
point. Now an entry whose internal URL is plain HTTP on the local network moves to push after the first Notify. One
whose URL is HTTPS, or a name that doesn't resolve to a local address, gets the fault and stays on the pull point.
**Untested** on a panel: the panel plan in the PR checks for "Switching to webhook for events" in Home Assistant's
debug log and that motion still arrives.

Tests (off-device): `OnvifHttpTest` reads several answers on one keep-alive socket byte for byte, each framed by
its Content-Length, HEAD included. `OnvifPushTest` covers the address rule, pinning, the sign-in, the cap, the 1 h
end, the order of Notify, Renew and Unsubscribe, a real consumer on a socket, and one that never answers.

## Open questions

- Digest `nc` isn't tracked (RTSP or ONVIF), so a captured response can be replayed for the nonce's
  12 h. Tracking it breaks clients that reuse a nonce for a session; binding nonces to a connection
  is the follow-up.

- Adoption by a real Protect console (an acceptance criterion of #117). Needs a console and a frame
  with a camera.
- CPU of `MediaCodec` H.264 at 720p next to the presence model on an A53-class SoC. Hardware encoders
  differ by SoC; measure on the target device.
- RootEncoder + RTSP-Server's APK cost, and whether we accept a JitPack dependency.
- Which ports. ONVIF has no fixed port; RTSP's is 554, which an app can't bind, so 8554.
- Events: presence is published as ONVIF motion (cell motion and motion alarm), on and off with
  `camera_presence`. Frigate and Protect's use of it is untested, and so is push to a hardware NVR.
- HTTPS push consumers. Refused for now: on Android 6 and 7 an `SSLSocket` may skip host name checks, and a
  check of our own is more code than the need justifies until a consumer asks for it.
- Whether to slow down a client whose sign-in keeps being refused, so a stale entry costs less than
  a request every 0.75 s. Not done: HA would still loop, only slower.
