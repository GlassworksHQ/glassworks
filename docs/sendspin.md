# Music Assistant player (Sendspin)

Glassworks includes a native **Sendspin** music player, so [Music Assistant](https://music-assistant.io/) sees your panel as a first-class player right alongside your other speakers. It plays synchronized multi-room audio in groups without needing Music Assistant's *Home Assistant MediaPlayers* bridge (which routes through `media_player.<device>_speaker` without synchronization).

The player is powered by our standalone MIT-licensed [`sendspin`](../libs/sendspin/README.md) library (`dev.glassworks.sendspin`), wrapped by the Sendspin pane (`panes/sendspin/src/main/java/dev/glassworks/speaker/`) for settings, ESPHome entities, and sealed key storage. Logs appear under `adb logcat -s Glassworks.Sendspin`.

## Setup

If your panel is already added to Home Assistant via ESPHome with encryption enabled (automatic on Home Assistant 2026.11 and newer) and Music Assistant is connected to Home Assistant, setup happens automatically:

1. The panel discovers Music Assistant's Sendspin server over mDNS (or falls back to your Home Assistant host on port `8927`) and connects.
2. Home Assistant requests the panel's Sendspin **pairing token** over the encrypted ESPHome API and hands it to Music Assistant.
3. Music Assistant pairs automatically and creates a player named after your panel—for example, **Aqara S1 Plus** (`media_player.aqara_s1_plus`, or `media_player.glassworks_panel` / `media_player.s1_panel` on panels paired in earlier releases).

Your Music Assistant player entity is completely separate from the ESPHome speaker entity (`media_player.<device>_speaker`), which continues handling Assist replies, TTS, and Home Assistant announcements. Whenever you trigger a wake word or play an announcement, Sendspin mutes locally while maintaining timeline sync, then fades right back in once the assistant finishes speaking.

### Settings

You can control Sendspin from the panel's settings menu (**Settings → Panes → Sendspin**), from the [Manage page](manage.md), or via Home Assistant entities and adb extras:

| Control | Purpose | Default |
|---|---|---|
| HA switch `switch.<device>_music_assistant_player_sendspin` / `--ez sendspin_enabled` | Enable or disable the Sendspin player | On |
| HA diagnostic `sensor.<device>_music_assistant_player_status` | Live status (for example, `ready, paired with Music Assistant (…)`, `playing from … (FLAC): Artist – Title`, `waiting for pairing or approval by …`, or `…; FLAC off, using PCM: <reason>`) | — |
| `--es sendspin_server <host[:port][/path] or ws://…>` | Override server address (`auto` uses discovery) | `auto` |
| `--ez sendspin_unpaired_access true` | Allow playback without pairing (guest mode); Music Assistant approves automatically | Off |
| `--ez sendspin_flac false` | Offer PCM only instead of FLAC (re-enabling retries FLAC after a fallback) | On (FLAC preferred, PCM fallback) |

Pass `--e…` extras to `adb shell am start -n sh.edm.s1kiosk/.MainActivity` (or `dev.glassworks.generic_android/dev.glassworks.MainActivity` on generic Android). Changing the server address, unpaired access, or FLAC setting reconnects cleanly so the updated capabilities take effect immediately.

Volume, mute, and static output delay are stored independently for Sendspin (`sendspin.xml`) so adjusting music volume does not alter your voice assistant or announcement volume (Android's master media volume scales both).

### If pairing needs you

Automatic pairing requires an encrypted ESPHome connection in Home Assistant and Music Assistant's Home Assistant provider running (the default when Music Assistant runs as a Home Assistant app/add-on). If the status sensor stays on `waiting for pairing or approval by …` and Music Assistant lists the panel as unpaired, you have two options:

- **Pair with token (recommended):** Home Assistant does not include a raw WebSocket sender in its Developer Tools UI, so retrieve the pairing token using any authenticated WebSocket client (such as `websocat` or a short Python script) connected to `ws://<ha-host>:8123/api/websocket` with an administrator long-lived access token:
  1. Find your panel's 43-character `client_id` by sending `{"id": 1, "type": "sendspin/subscribe_clients"}` (it is also logged on the panel under `Glassworks.Sendspin` and shown in Music Assistant's player details).
  2. Request the token by sending `{"id": 2, "type": "sendspin/get_pairing_token", "client_id": "<panel client id>"}`.
  3. Open **Settings → Players → \<Your panel\>** in Music Assistant and paste the returned pairing token. Treat this token like a password—anyone holding it can pair a server with your speaker.
- **Enable guest mode:** Pass `--ez sendspin_unpaired_access true` so Music Assistant approves the panel automatically without a token. Guest mode disables mutual authentication, so only use it on a trusted local network (see [Security](#security)).

## Protocol (what is implemented, with sources)

Glassworks implements the [Sendspin wire protocol](https://github.com/Sendspin/spec) strictly against **`aiosendspin[server]==10.2.1`** (as shipped in Music Assistant 2.10.9), passing with `allow_noncompliant_clients=False` and zero compliance warnings.

### Discovery and connection

- **Client-initiated connections:** The panel dials the server (it does not advertise `_sendspin._tcp`), allowing connections even when the panel sits on a separate VLAN from Music Assistant.
- **Server resolution:** Queries mDNS for `_sendspin-server._tcp` (port `8927`, TXT `path=/sendspin`). If discovery receives no response within 3 seconds, it falls back to `ws://<Home Assistant host>:8927/sendspin`, or uses your configured `sendspin_server`.
- **Transport and reconnects:** Connects over WebSocket (`WsClient`, RFC 6455) with all confidentiality and integrity provided by Noise encryption. Answers server heartbeats (ping/pong) and reconnects after 1 second following an active session, or with exponential backoff from 1 to 60 seconds otherwise.

### Encryption: Noise `KKpsk2_25519_ChaChaPoly_SHA256`

- Handshake flow: `client/init` `{client_id, version: 1, suite}` → `server/init` → Noise message 1 (server-initiated) → message 2, using the raw bytes of `client/init` + `server/init` as the prologue.
- `client_id` is the 43-character unpadded base64url X25519 static public key generated randomly on the device and stored in private app preferences (the Wi-Fi MAC is reported separately in `device_info.mac_address` so Music Assistant can link protocol players).
- Message 1 selects a long-term PSK (`lt`), pairing PSK (`pr`), or Sentinel PSK (`sn`, `SHA-256("sendspin-sentinel-psk-v1")`) via `psk_id = base64url(SHA-256("sendspin-psk-id-v1" || PSK))`. Unknown keys on initial handshake fall back to Sentinel.
- Binary transport frames carry a leading type byte (`0` JSON, `1` fragment reassembled per spec, `4` player audio). Re-handshakes inside transport mode (`noise/handshake`) chain the previous handshake hash as prologue and swap keys immediately after message 2.

### Hello, activation, pairing

- `client/hello` advertises the panel's friendly name, manufacturer/model/version/MAC, `supported_roles: ["player@v1", "metadata@v1"]`, `supported_pair_methods: {"pairing_psk": {}}`, `unpaired_access`, and `player@v1_support` (`buffer_capacity: 1500000`, preferring 48 kHz 16-bit stereo FLAC with PCM fallback).
- `server/activate` enforces spec admission rules per PSK category, rejecting unauthorized roles with `client/goodbye`.
- **Pairing PSK flow:** Sends `client/pair-init` followed by `client/pair-finalize` with a fresh 32-byte random `long_term_psk`, persisting the record only after `server/pair-finalize` arrives (up to 8 LRU pairing records). `server/unpair` removes the record and disconnects cleanly.
- **Pairing token (v0):** Encoded as `"SP:0" + base32(client_key || pairing_psk)` without `=` padding (`2` mapped to `9`).

### ESPHome hand-off (how HA gives MA the token)

On every ESPHome connection, Home Assistant reads `DeviceCapabilitiesResponse.sendspin` (`feature_flags = PAIRING_TOKEN`, `client_id`) and publishes active client IDs over `sendspin/subscribe_clients`. When Music Assistant's `HassAutoPairer` sees an unpaired client, it calls `sendspin/get_pairing_token`, which Home Assistant forwards as `SendspinPairingTokenRequest` (159) → `SendspinPairingTokenResponse` (160):

| Status | Meaning |
|---|---|
| `OK` (1) | Encrypted ESPHome session with a provisioned key and player started |
| `ENCRYPTION_REQUIRED` (2) | Plaintext or zero-PSK session—pairing tokens are never sent unencrypted |
| `NOT_READY` (0) / `DISABLED` (3) | Player not yet started or Sendspin disabled |

### Clock synchronization

- Sends bursts of 8 sequential `client/time` probes (2-second timeout per burst), feeding the burst's lowest-`max_error` sample into the Sendspin spec's 2-D Kalman filter (`TimeFilter`). Bursts fire every 1 second for the first 10 bursts, then every 5 seconds.
- Timestamps are captured directly around encryption/WebSocket framing (`T1` before encryption, `T4` upon final WebSocket byte arrival before decryption).
- Holds the initial `client/state` until the clock filter converges after the first burst, then resends on role activation or volume/mute/delay changes.

### FLAC

- **Negotiation & headers:** Lists 48 kHz 16-bit stereo FLAC ahead of PCM so Music Assistant selects FLAC automatically. Accepts the standard 42-byte Base64 `codec_header` (`fLaC` + 34-byte STREAMINFO) or bare STREAMINFO and validates parameters against `stream/start`.
- **Decoding:** Uses Android's MediaCodec `audio/flac` decoder (`c2.android.flac.decoder` on the S1 Plus) in synchronous mode, splitting incoming chunks into individual validated FLAC frames (`FlacFrames.split`) so every input buffer decodes 1:1 with exact server timestamps preserved into the PCM jitter buffer.
- **Format transitions & clearing:** Mid-stream `stream/start` format switches drain queued decoder frames smoothly without restarting `AudioTrack`. `stream/clear` drops in-flight frames by tag without flushing C2's STREAMINFO state; `stream/end` releases the codec cleanly.
- **Automatic PCM fallback:** Corrupt frames or decoder stalls (> 1.5 s) recycle the decoder cleanly. Three errors within 60 seconds—or a missing/unopenable FLAC decoder—disable FLAC for the current run, reconnect with PCM-only capabilities, and report the reason on `sensor.<device>_music_assistant_player_status`.

### Audio and scheduling

- **Output pipeline:** Decodes incoming chunks (`[4][timestamp i64][send_ahead u32][audio]`) on a dedicated `ss-decode` thread into a timestamp-ordered jitter buffer, dropping chunks that arrive past their playback deadline.
- **Drift & sync correction:** `PlaybackScheduler` writes 10 ms blocks to `AudioTrack` (`USAGE_MEDIA`, 100 ms buffer, `URGENT_AUDIO` priority), comparing target presentation time (`compute_client_time(ts) - output_delay_ms`) against `AudioTrack.getTimestamp()`. Offsets > 3 ms perform a one-shot snap; smaller errors apply soft correction (±1 frame per 10 ms block, ≤ 0.21% speed, ±150 µs dead band).
- **Commands & delay:** Supports `volume` (perceptual `(volume/100)^1.5` curve), `mute`, and `set_output_delay` (0–5000 ms, matching Music Assistant's per-player *static delay*).
- **Voice coexistence:** During Assist wake/listening/speaking and `media_player.<device>_speaker` announcements, Sendspin mutes locally while keeping the clock and stream running so it rejoins your speaker group in exact sync afterward.

## Measured on the panel

Benchmarked on an Aqara S1 Plus streaming a 48 kHz library track from Music Assistant over Wi-Fi across a VLAN:

| Metric | PCM | FLAC |
|---|---|---|
| Steady-state wire bitrate | 1686–1702 kbit/s | 740–930 kbit/s (45–55% lower) |
| Total `wlan0` receive rate (incl. startup burst) | ~1905 kbit/s | ~1172 kbit/s |
| Sendspin CPU share (of one A53 core) | ~10% | ~18% |
| Steady-state scheduling error | 47–179 µs | 124–179 µs |
| Clock drift corrections per 10 s | ~9 frames | ~9–11 frames |
| Underruns / late chunks / decode errors | 0 / 0 / — | 0 / 0 / 0 |

FLAC cuts Wi-Fi bandwidth nearly in half for only ~8% of one CPU core (~2% of the quad-core CPU), with ~10 ms average decode latency against 1.5–4 seconds of buffer and identical sync accuracy. If your panel sounds consistently ahead of or behind another speaker in an acoustic group, adjust **Static delay** on the player's settings page in Music Assistant.

## Tests

Run the unit and live-server compliance suite against `aiosendspin 10.2.1` (requires Python 3.12 or newer):

```sh
python3.12 -m venv /tmp/sendspin-venv && /tmp/sendspin-venv/bin/pip install 'aiosendspin[server]==10.2.1'
SENDSPIN_PYTHON=/tmp/sendspin-venv/bin/python ./gradlew :sendspin:testDebugUnitTest :pane-sendspin:testDebugUnitTest
```

The test suite covers Noise cryptography (`SsCryptoTest`), message framing (`SsMessagesTest`), Kalman clock filtering (`TimeFilterTest`), virtual-clock audio scheduling (`PlaybackSchedulerTest`), FLAC frame splitting and decoding (`FlacTest`, `AudioPipelineTest`), end-to-end strict compliance against a real `aiosendspin` server (`SendspinServerTest`), and ESPHome pairing token hand-off (`EspHomeApiTest`).

## Security

- **Paired-only by default:** Unpaired guest access starts turned off. Pairing tokens leave the panel exclusively over an encrypted ESPHome connection via Home Assistant's admin-only `sendspin/get_pairing_token` API, and every session after pairing is mutually authenticated with a long-term Noise PSK.
- **Sealed key storage:** Identity keys, pairing PSKs, and pairing records are stored encrypted inside private app data (`sendspin.xml`) and are never logged.
- **Guest mode trade-off:** Enabling unpaired access allows any host on your LAN to connect as a server (playback only—the panel exposes no microphone `source` role over Sendspin).

## Limitations

- Supports 48 kHz 16-bit stereo FLAC and PCM only (no Opus or 24-bit output). Devices whose `audio/flac` MediaCodec does not preserve per-frame timestamps fall back cleanly to PCM after 3 errors.
- Pairing uses tokens (automatic via Home Assistant or manual copy-paste)—dynamic and static PIN pairing codes are not implemented.
- `metadata@v1` feeds the status sensor and logs; on-screen album artwork and `controller@v1` transport controls are handled by your Home Assistant dashboard instead.
- `available` always reports `true` so voice prompts mute locally without kicking the panel out of active speaker groups.

## After a factory reset or reinstall: a new Music Assistant player

Because Sendspin identifies every player by its random X25519 public key stored in private app data, a factory reset, full reinstall, or unrooted debug-to-release switch generates a fresh identity. Music Assistant will then discover a new player (for example, `media_player.aqara_s1_plus_2`) alongside the old unavailable one. This is intentional security behavior—deriving keys from a hardware ID would allow impersonation. Simply delete the old player in Music Assistant (**Settings → Players**) and rename the new entity back if desired. Your panel's ESPHome device in Home Assistant is unaffected and reconnects seamlessly.
