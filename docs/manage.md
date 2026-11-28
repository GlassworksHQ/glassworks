# The Manage page

Every Glassworks panel serves a built-in **Manage** web interface at `http://<panel-ip>:8099/` once set up. The easiest way to open it is clicking **Visit** on your panel's ESPHome device page in Home Assistant, or typing the panel's local IP address directly (for example, `http://192.0.2.40:8099/`).

Because Manage is part of Glassworks Core, every supported frame—from the Aqara S1 Plus to Android tablets and mirrors—has the exact same interface. You can check panel health, adjust every setting, review logs, download diagnostics, and even manage or copy settings across all the Glassworks panels in your house from a single browser tab.

> [!NOTE]
> Always open Manage using the panel's IP address (or Android hostname). Custom router DNS aliases like `panel.lan` or trailing dots are rejected to protect against DNS rebinding attacks.

## What's on it

| Page | What you can see and do |
|---|---|
| **Overview** | App version (plus a **Legacy** badge on Android 6–7 builds explaining engine limits), uptime, Home Assistant status, Wi-Fi status, web engine and memory usage, and every supported pane with its live state. Quick actions: **Reload dashboard**, **Restart app**, **Reboot** (when device owner), and **Check for updates**. |
| **All panels** | View every Glassworks panel in your Home Assistant instance in one place, jump into any panel's Manage page without signing in again, and copy settings from one panel to another ([details below](#other-panels-through-home-assistant)). |
| **Home Assistant & dashboard, Screen, Sound, Appearance, and each pane** | Every setting from the panel's on-device settings menu, laid out in the same order with live editing. **Sound** sets the master volume for everything the panel plays. Under **Appearance**, pick the color theme for Glassworks overlays: five built-in themes in light and dark, a custom theme you can dial in with your browser's color picker, and, once your dashboard has loaded, a **Home Assistant** theme that uses your dashboard's own colors. A switch below the themes follows Home Assistant's dark mode automatically. The voice overlay and the timers both use the theme. Screen and supported panes also include one-click [Home Assistant blueprints](blueprints.md#adding-one-from-the-manage-page). Each pane page has an on/off toggle in the top right and live status details. |
| **Device page** *(e.g. S1 Plus or Pixel Tablet)* | Hardware-specific details that do not belong to a single pane: on the S1 Plus, **Block Aqara cloud**, disabled Aqara apps, radio mode shortcut, and **Silent updates**; on Android tablets, **Silent updates**, device owner status, dock status, model, and Android version ([Silent updates](esphome.md#silent-updates)). |
| **Log** | Rolling 7-day log persisted across reboots. Filter by warnings or errors, search by keyword, or click **Download diagnostics** to export a redacted troubleshooting bundle. |
| **Security** | Lists every browser currently signed in to Manage, with one-click **Sign out**. Below it are the actions that need someone at the panel: **Allow setup over the network**, **Close setup over the network** and **Reset the panel** ([details below](#actions-that-need-the-panels-code)). On devices where Glassworks manages USB debugging authorization (such as the lululemon Mirror), you can also approve or revoke authorized computers here. |
| **About** | App and frame versions, hardware frame ID, and open-source licenses. |

The interface adapts cleanly to mobile browsers (folding navigation behind the `☰` menu) and automatically matches your browser's light or dark theme.

A few actions intentionally stay on the physical panel because they require being in front of the device or handling sensitive keys: initial Wi-Fi Bluetooth approval, opening Aqara's stock Android settings, QR codes, and pasting a raw Thread dataset (which carries your Thread network key and should not travel over plain HTTP). All other pane controls—including radio roles, Zigbee stack selection, Thread join/leave, wall base switches, Bluetooth proxy mode, camera privacy and ONVIF credentials, and time zone selection—work right from Manage.

## Set up over the network

If a panel is already on Wi-Fi (or broadcasting its temporary setup Wi-Fi), you can finish setting it up from a laptop or phone at `http://<panel-ip>:8099/setup` with no USB cable and no typing on the wall screen. Kiln links directly here on its Done screen, and touchless devices like the lululemon Mirror show the address and QR code right on their setup screen.

1. **Pair:** Click **Show the code on the panel**. A 6-digit code appears on the panel's screen; type it into your browser and click **Pair** to prove you are physically near the panel.
2. **Wi-Fi** *(shown only when the panel is not yet connected to a network)*: Choose your Wi-Fi network and enter the password. The panel closes its temporary setup Wi-Fi and joins your home network, showing its progress and new home-network address on its screen. *(If your browser can still reach the setup hotspot 45 seconds later, joining did not start and the page offers **Try again**.)* Once your phone or laptop is back on your home Wi-Fi, open `http://<panel-ip>:8099/setup` at the panel's new IP address and pair again with a fresh 6-digit code—pairing sessions are bound to the client IP, so switching networks starts a new session.
3. **Home Assistant:** Confirm the discovered Home Assistant address (or enter it manually), sign in, and pick a dashboard if your Home Assistant has custom dashboards.
4. **Radio:** On frames with a radio, choose your radio role and view the Home Assistant next steps.
5. **Done:** Finishes setup and closes the pairing session.

| Pairing behavior | Details |
|---|---|
| Rate limits | 5 attempts per code; 10 wrong attempts in 15 minutes temporarily lock entry; at most 6 new codes per 10 minutes. |
| Session duration | Ends on **Done**, after 30 minutes of inactivity, or after 2 hours total. |
| Browser binding | Locked to the specific browser and client IP that paired. |
| Unpaired access | Shows read-only setup status and rejects all changes. |

**Re-enabling network setup after Home Assistant adoption:** Once Home Assistant adopts the panel, network setup pairing closes automatically so nobody else on your LAN can reconfigure it. To reopen pairing for 30 minutes later:

- Press **Allow setup over the network** on the panel's ESPHome device page in Home Assistant (under Configuration), or
- Open **Settings → Security → Allow setup over the network** on the panel, or on the Manage page with the code the panel shows, or
- Reconnect with Kiln over USB (Kiln enables it automatically on the Done screen).

To close it again before the 30 minutes end, use **Settings → Security → Close setup over the network** on the panel, or on the Manage page with the code the panel shows. Closing also ends setup on any computer that paired and closes the panel's setup Wi-Fi.

**Touchless panels:** On devices without a touchscreen (such as the lululemon Studio Mirror), the panel displays its setup QR code and URL automatically, keeps the screen awake until Home Assistant adopts it, and dismisses the pairing code as soon as it is used or expires.

> [!NOTE]
> Because browsers do not trust HTTPS certificates for arbitrary local IP addresses, `/setup` runs over plain HTTP/WebSocket on your LAN. When configuring Wi-Fi over the panel's own setup hotspot, traffic is encrypted over the air with WPA2 using the password displayed on the panel's screen.

### The panel's own Wi-Fi

If a panel cannot reach Wi-Fi during setup, it can broadcast its own temporary hotspot so you can configure it from any phone (including iPhone) with no USB cable and no Bluetooth:

1. **When it starts:** Opens automatically if the panel has had no network connection for 3 minutes after startup, or immediately when you tap **Set up from a phone** on the Wi-Fi setup screen. It never activates while the panel is already connected to a network.
2. **Join the hotspot:** Scan the QR code on the panel with your phone's camera (or enter the displayed network name and password manually). On the Aqara S1 Plus, iOS and Android 11+ open the setup page automatically like a captive portal; on other devices, open `http://<address>:8099/setup` in your browser and pair with the 6-digit code.
3. **Send your home Wi-Fi:** Pick your home network from the scan list (or type a hidden SSID under **Other…**) and enter its password. The panel shuts down its hotspot, connects to your home network, and shows connection status along with its new home-network IP address on its screen (you can also find the IP in your router's DHCP list or in Home Assistant once discovered). Reconnect your phone to your normal Wi-Fi, open `http://<panel-ip>:8099/setup` at that new IP, and pair again with a fresh 6-digit code to continue setting up Home Assistant and the radio.
4. **When it closes:** Stops automatically as soon as Wi-Fi is submitted, after 15 minutes (or Android's shorter idle timeout when nobody joins), or when you tap **Close it**. If connecting to your home Wi-Fi fails, the panel retries for 3 minutes before reopening its setup hotspot (unless you explicitly tapped **Close it**).

**Platform details:** On Android 8+, Glassworks uses Android's local-only 2.4 GHz hotspot (`Glassworks_…` on the S1 Plus with the updated radio bundle, `AndroidShare_…` on most other devices). Android 13+ requests the *Nearby devices* permission, while Android 8–12 require Location enabled. Android 6–7 opens a `Glassworks-…` hotspot and restores previous hotspot settings when finished. If Wi-Fi is switched off completely on Android 10+, the setup screen offers a **Turn on Wi-Fi** shortcut first.

## Changing settings

Every change you make on Manage validates and saves through the exact same checks as the panel's on-device menu: switches apply immediately, sliders apply on **Save**, and dialogs apply on confirmation. If a setting requires an app restart to take effect, the page tells you and offers **Restart now**. Sensitive actions—like turning the radio off—always ask for confirmation first.

Manage renders live values when each page loads rather than streaming updates over JavaScript, so refresh the page if you changed a setting simultaneously on the panel or in Home Assistant. *(The one exception is a docking tablet's Device page, which refreshes every 3 seconds while showing **Docked / Not docked**—with a **Pause** link whenever you want to freeze it—and never refreshes while a dialog is open.)* Every setting change is recorded in the **Log** with the administrator's name and the new value.

**Camera stream credentials:** Passwords for the ONVIF/RTSP stream are strictly write-only—once saved, neither the panel nor Manage will ever display the password again. Open the Sign-in dialog to update the username (leaving the password blank keeps the existing one), set a new password, or **Clear** both. Write the password down in your password manager when creating it so you can enter it into Home Assistant's ONVIF integration, Frigate, or UniFi Protect.

## Signing in

Manage offers two ways to authenticate:

1. **Continue with Home Assistant (recommended):** Redirects to your local Home Assistant login page so the panel never sees your password. Only **Home Assistant administrators** are granted access. Manage only talks to Home Assistant addresses on your local network (private IP or `.local` hostname)—never public or Nabu Casa URLs.
2. **Show a code on the panel:** Use this fallback if Home Assistant is offline. The panel displays a one-time 6-digit code for 5 minutes; enter it in your browser to sign in. *(If you sign in with a code before configuring a Home Assistant URL, that session resets once Home Assistant is configured.)*

Check **Remember this browser for 30 days** to stay signed in across browser restarts; otherwise your session ends when you close the browser (or after 12 hours). You can review and revoke active browser sessions anytime on the **Security** page. Changing the panel's Home Assistant address automatically signs out all active Manage sessions.

**Reboot safety:** Clicking **Reboot** always requires confirming a 6-digit code on the panel itself, since rebooting briefly takes down any Zigbee/Thread radio network hosted on the panel.

### Actions that need the panel's code

Just like **Reboot**, a few sensitive actions verify that someone is physically standing in front of the panel before running. When triggered from Manage, each one displays a one-time 6-digit code on the wall screen that you enter in your browser, so a stolen browser session cannot run them. On the panel itself under **Settings → Security**, those actions run directly without an extra code.

| Action | What it does |
|---|---|
| **Allow setup over the network** | Reopens `/setup` network pairing for 30 minutes ([Set up over the network](#set-up-over-the-network)). |
| **Close setup over the network** | Shown while pairing is open. Closes network pairing right away, disconnects any paired setup browser, and shuts down the panel's setup Wi-Fi. |
| **Reset the panel** | Resets the Glassworks app to factory defaults (without wiping Android). Shows a clear summary of what gets cleared and what stays before asking for the code. |

**What a reset clears:** All Glassworks app and pane settings, setup answers, the dashboard's Home Assistant sign-in, and every browser signed in to Manage—then restarts the app into first-boot setup. Glassworks also asks Home Assistant to revoke the dashboard's refresh token (if Home Assistant is offline or moved, Glassworks clears its local copy anyway and logs a note so you can revoke the old token under your Home Assistant profile → **Security → Refresh tokens**).

**What a reset keeps:** Android's Wi-Fi connection, the installed Glassworks app, the rolling Log, your radio configuration (keeping any active Zigbee or Thread network running—turn the radio off on its own page if you want to stop it), and your panel's device entry and ESPHome encryption key in Home Assistant. To remove the panel from Home Assistant as well, delete it from Home Assistant's ESPHome integration.

**Fast multi-address fallback:** If your Home Assistant `.local` hostname resolves to both IPv6 and IPv4 addresses—for instance, when an mDNS reflector advertises an unreachable IPv6 address—Glassworks races connections across addresses like a modern browser so sign-in succeeds quickly without timing out.

## How it keeps you safe

Manage is built to stay safe on a home LAN with minimal attack surface:

- **Administrator-only verification:** Non-admin Home Assistant tokens are revoked immediately. An admin token is kept strictly in RAM for the duration of your browser session (so **All panels** does not prompt again), never written to disk, logs, or diagnostics, and revoked automatically in Home Assistant when you sign out, when the session expires, or when Manage stops. *(If the app crashes or restarts unexpectedly, you can revoke any leftover token under your Home Assistant profile → Security → Refresh tokens.)* The panel's own dashboard login token is completely separate and never exposed to Manage.
- **Hardened session cookies & CSRF protection:** Session cookies are `HttpOnly` and `SameSite=Strict`, and the panel stores only a hash of each cookie. Every form validates both `Origin` and a per-session CSRF token (the only cross-panel endpoint is `/manage/ha/token`, which verifies a Home Assistant admin token directly with Home Assistant).
- **Brute-force and spam limits:** Panel codes work once for a single browser and expire after 5 minutes. Five wrong guesses invalidate a code, 10 wrong guesses in 15 minutes pause code entry, and global rate limits cap new codes to 6 every 10 minutes (with a 30-second cooldown) so nobody can spam the wall screen.
- **Host header & timeout guards:** Requests must finish within 10 seconds and must target the panel's own IP address or local hostname, blocking DNS rebinding.
- **Zero third-party scripts & strict CSP:** Every page except **All panels** runs with zero JavaScript at all; **All panels** loads only a single local script served by the panel itself under a strict Content-Security-Policy.
- **Automatic log redaction:** Logs and diagnostic downloads automatically scrub tokens, API keys, passwords, query strings, Thread datasets, and MAC addresses. Still, always skim a diagnostics bundle before uploading it publicly in case a secret appears in an unusual format.

### What plain HTTP on a LAN does not protect

Like most local Home Assistant setups, Manage runs over `http://` because browsers do not trust self-signed certificates for private LAN IPs. Anyone able to sniff or intercept traffic on your local Wi-Fi network could potentially see:

- Your Manage session cookie (and, for sessions authenticated through Home Assistant, access tokens used by **All panels**) until you sign out
- Your Home Assistant login exchange or password if Home Assistant itself runs over `http://` (enabling HTTPS and multi-factor authentication on Home Assistant protects your account)
- A camera stream password typed on Manage (to keep it off the network completely, set it directly on the panel's screen instead)

Use Manage only on a trusted home network.

## For developers

The HTTP server lives in `dev.glassworks.manage.ManageServer`, backed by each frame's `ManageHost` implementation. See the [Pane API / Core README](../pane-api/src/main/java/dev/glassworks/README.md#the-manage-page) for integration details.

## Other panels, through Home Assistant

The **All panels** page (right below Overview in the sidebar) lets you monitor and configure every Glassworks panel in your home from a single screen—without any central server, shared keys, or extra Home Assistant integration.

1. **Single sign-in:** If you signed in to Manage using Home Assistant, **All panels** reuses that session automatically. If you signed in with a panel code (or after a panel restart), click **Continue with Home Assistant** once to sign in as an administrator. Opening another panel via **Manage** hands off a short-lived admin session so you do not have to log in again; clicking **Sign out** revokes the session cleanly.
2. **Live discovery:** The page queries Home Assistant for all ESPHome devices whose **Visit** link points to a Manage page on port `8099`, displaying each panel's area, IP address, version, and live status. Unreachable panels show as **Down** after 6 seconds, while panels that reject your credentials show **Refused**, **Busy**, or **No answer**. Any non-panel device or off-LAN address is ignored and never receives your token.
3. **One-click access:** Click **Manage** on any row to open that panel's own Manage page in a new tab, already signed in.
4. **Copy settings:** Pick a source panel and a destination panel to see a checklist of every setting that differs (*current value → new value*). Hardware-specific or unsupported options, credentials, and each panel's Home Assistant URL are automatically grayed out and never copied. Click **Copy** to apply only the checked changes (sensitive changes like radio roles confirm first).

### How cross-panel security works

No panel blindly trusts another panel. Instead, your browser sends your Home Assistant administrator token directly to each target panel over your LAN, and each target panel independently validates that token with its own Home Assistant instance (caching valid checks for 1 minute and rate-limiting requests). Before sending a token to any device, **All panels** confirms the device's address is on your local network and verifies its unauthenticated `/manage/api/hello` response matches the model Home Assistant expects.

Because `hello` only filters by model name and traffic runs over plain HTTP on your LAN, only adopt ESPHome devices you recognize and only use **All panels** on a trusted network. Sign out or close the tab when finished.

**Developer endpoints:** `GET`/`POST /manage/api/settings` (Bearer token, form-encoded `section`, `key`, `value`, and optional `answer=<id>` for confirmation prompts), `POST /manage/ha/token` (`token`, `next` for browser sign-in handoff), and `GET /manage/api/hello` (public model identification). All API endpoints allow CORS `*` and ignore cookies so cross-origin web pages cannot act on your Manage session.
