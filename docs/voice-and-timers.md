# Voice and timers

Glassworks turns your panel into a native Home Assistant Assist satellite, using the same ESPHome voice model as Home Assistant Voice PE. It listens locally for a wake word on the panel itself, streams your command to Home Assistant, shows a clean full-screen overlay while you talk, plays the spoken reply, and displays Home Assistant voice timers right on your dashboard.

## How Assist works on the panel

When Voice is enabled, the panel continuously runs an on-device **microWakeWord** model on LiteRT (TensorFlow Lite). Nothing is sent over the network until the wake word is heard.

When you say the wake word:

1. The panel plays a soft wake chime (if **Wake sound** is enabled) and wakes the screen.
2. Any music playing on the panel pauses automatically, and Music Assistant (Sendspin) mutes locally while staying synchronized with your speaker group.
3. If **Voice overlay** is enabled, light runs along the edge of the screen and swells with your voice, with a small caption of what you said and the assistant's reply, while your dashboard stays in view. If you prefer a full-screen overlay with a reactive orb, pick **Orb** under **Assist** on the **Appearance** page. Tap anywhere on the screen to cancel the run at any time. The colors come from the theme you pick on the **Appearance** page, in the panel's settings or on the Manage page: five themes, each light or dark, or a Custom theme in your own colors. Turn on **Follow Home Assistant's dark mode** there and the glow or orb switches between light and dark along with your dashboard. Or pick the **Home Assistant** theme, which uses your dashboard's own colors.
4. Your voice streams to your chosen Home Assistant Assist pipeline, and the reply plays through the panel's speaker.
5. If your pipeline asks a follow-up question, the panel keeps listening for your answer without requiring the wake word again.
6. Once the reply finishes, your music resumes automatically.

## Setting up voice

You need an Assist pipeline configured in Home Assistant (*Settings → Voice assistants*).

1. **Enable the Voice pane.** Turn Voice on during first-boot setup, in the panel's settings menu (**Settings → Panes → Assist**), on the [Manage page](manage.md), or by enabling and toggling the **Voice** switch on the panel's device page in Home Assistant.
2. **Choose your pipeline and wake word.** Open the panel's ESPHome device page in Home Assistant and pick your **Assist pipeline** and **Wake word**. You can also pick the wake word from the panel's own Assist settings page.
3. **Wait a moment for the first model download.** The first time you select a wake word, the panel downloads the official microWakeWord model from GitHub and verifies its SHA-256 checksum automatically.

### Supported wake words

Glassworks supports the four official ESPHome microWakeWord v2 models:

- **Okay Nabu**
- **Hey Jarvis**
- **Alexa**
- **Hey Mycroft**

One wake word can be active at a time. Panels upgrading from older openWakeWord installs automatically migrate to the matching microWakeWord model; any legacy openWakeWord models already stored on the panel remain selectable with an `(openWakeWord)` label for compatibility.

## Voice timers

Voice timers are managed by Home Assistant and displayed automatically on the panel whenever the Voice pane is active. There is no separate setup required—just ask your assistant to set, pause, cancel, or add time to a timer (for example, *"Set a 10-minute pasta timer"* or *"Pause the pasta timer"*).

- **Running timers** appear as small glass orbs in the bottom-right corner, ordered with the soonest timer closest to the corner. Each orb shows the remaining time, a countdown ring in your theme's two colors, and the timer's name underneath (or `TIMER` if unnamed). The ring turns amber for the last 10 seconds. Paused timers show a pause symbol with a dimmed ring. The timers take the theme you pick on the **Appearance** page, light or dark.
- **Final countdown:** During the last 10 seconds, the orb glows and switches to large digits with a subtle ripple each second.
- **Three or more timers** gather into one card that lists them, soonest first: a small ring, the name and length, and the time left. It moves and hides like the orbs, and on a touch screen it has its own **Hide**.
- **Moving timers:** Drag the timers to any corner or the center of any edge—they snap into place and remember your preferred spot for future timers.
- **Hiding timers:** Tap a running timer and select **Hide timers** to tuck them into the nearest edge as a compact half-orb that still shows the progress arc. Tap the half-orb to expand them again. Hidden timers automatically reappear for their final 10 seconds or whenever a new timer is started.
- **When a timer finishes:** The screen wakes up, displays a full-screen completion screen showing the timer's name and duration (or *"2 timers done"* if several finish together), and plays a chime three times about 3 seconds apart. Tap anywhere to dismiss the screen and silence remaining chimes, or let it close automatically after 60 seconds. On a screen without touch, such as the Mirror, it says so and closes by itself after 60 seconds.

## Announcements across panels

Because every panel with Voice enabled is a Home Assistant Assist satellite, you can broadcast spoken announcements such as *"Dinner is ready"* to one or more panels using Home Assistant's `assist_satellite.announce` action. Each panel plays a pre-announce chime, wakes the screen, displays the message on the voice overlay, speaks the text using its Assist pipeline voice, and resumes any paused music afterward.

For a ready-to-use multi-panel script and dashboard button, see [Announce on every panel](esphome.md#announce-on-every-panel).

## Controls and entities in Home Assistant

Home Assistant creates the standard Assist entities on your panel's device page (**Assist satellite**, **Assist pipeline**, **Wake word**, and **Finished speaking detection**), along with the panel's own voice entities:

| Entity | Enabled by default | What it does |
|---|---|---|
| **Mute microphone** (`switch`) | Yes | Completely releases the microphone for privacy or when using another voice input. |
| **Voice status** (`sensor`) | Yes | Reports `Ready`, `Getting ready`, or a specific error if the wake word cannot start. |
| **Voice** (`switch`) | No | Turns the Voice pane (and timers) on or off. |
| **Wake sound** (`switch`) | No | Plays a soft chime when the wake word triggers (on by default). |
| **Voice overlay** (`switch`) | No | Shows the full-screen voice overlay while listening, thinking, and speaking (on by default). |
| **Mic gain** (`number`, 0.5–4x) | No | Adjusts microphone gain before wake word detection and speech streaming. |
| **Wake word threshold** (`number`, 0.10–0.99) | No | Sets detection sensitivity for the active wake word. Lower values make the panel more sensitive; switching wake words resets this to the new model's default cutoff. |

To enable any disabled entity, open the panel's device page in Home Assistant, expand the hidden entities list, select the entity, and turn on **Enabled**. All of these settings can also be adjusted directly in **Settings → Panes → Assist** on the panel or on the [Manage page](manage.md).

## Troubleshooting

If the panel is not responding to your wake word, check the **Voice status** sensor in Home Assistant or open **Settings → Panes → Assist** on the panel (or the Manage page). When something goes wrong, the Assist page displays the exact reason along with a **Try again** button for retryable errors.

| Voice status | Likely cause and fix |
|---|---|
| `Getting ready` | The panel is downloading or loading the wake word model. Give it a few seconds on first launch. |
| `Download failed` | The panel could not reach GitHub (`github.com` / `raw.githubusercontent.com`) or verify its HTTPS certificate. Check your internet connection, firewall rules, and the panel's date and time. The panel retries automatically with backoff, or you can tap **Try again**. |
| `Model check failed` | The downloaded file did not match the expected SHA-256 checksum (for example, a captive portal or intercepting proxy returned a web page instead of the model). Fix the network path and tap **Try again**. |
| `Model not saved` | App storage is full or not writable. Free up storage space on the device. |
| `Model missing` | A custom legacy openWakeWord model is selected that cannot be downloaded automatically. Pick one of the four official microWakeWord options. |
| `Engine failed` | LiteRT, the wake word engine, failed to start on the device. Open the [Manage page](manage.md) log (filter by *Voice*) and report the issue. |
| `Microphone not allowed` | Android's Microphone permission is denied. Grant **Microphone** permission to Glassworks in Android's app settings. |
| `Microphone unavailable` | The device has no microphone, another app is holding it, or audio capture failed. Close any app using the microphone; Glassworks retries automatically every 5 seconds. |

### Tuning wake word accuracy

- **Too hard to trigger from across the room:** Increase **Mic gain** slightly or lower **Wake word threshold** in small steps.
- **False triggers from TV or conversation:** Raise **Wake word threshold** slightly toward `0.97` or reduce **Mic gain**.

### Known limits

- **No barge-in or echo cancellation yet:** Wake word detections are ignored while the panel is listening or speaking. Tap the voice overlay to stop a response early.
- **Shared microphone with the dashboard:** If you also trigger Home Assistant's in-browser Assist microphone button inside the dashboard, it competes with the on-device wake word engine for the microphone. Turn on **Mute microphone** if you prefer using in-browser Assist instead of the wake word.

For wire-level ESPHome protocol details and microWakeWord parameters, see [Voice assistant in the ESPHome reference](esphome.md#voice-assistant).
