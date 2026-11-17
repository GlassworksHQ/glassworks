# The Manage page

Every panel serves a Manage page at `http://<panel>:8099/` once it's set up. Open it from Home
Assistant (the panel's device page, **Visit**) or type the address: the panel's IP address
(`http://192.0.2.40:8099/`). Its ESPHome name ([names](esphome.md#names)) is not a host name you can
open: the panel's mDNS advert points at Android's own host name, and nothing publishes `<name>.local`.
Other names for it, such as one your router gives it (`panel.lan`) or a name with a trailing dot, get
"Open this page with the panel's
address": the page only answers to its own addresses and names (see below). It shows how the panel is doing,
keeps a log, and runs the everyday actions. It's part of the Glassworks core, so every frame has it.

Status: **new, not yet checked on a panel.** It has the same settings as the panel's own menu, and
changes them the same way.

## What's on it

| Page | What it has |
|---|---|
| Overview | Running or not, the app's version ("· test build <commit>" on a test build) and how long the app has run; Home Assistant, Wi-Fi (connected or not from Android's connectivity service; the network's name and signal where Android gives them to the app), the web engine, memory; every pane the panel can run, with its state (running, off, or can't run now and why). A pane the device has no hardware for isn't listed, here or on the panel. Buttons: **Reload dashboard**, **Restart app**, **Reboot**, **Check for updates** |
| Home Assistant & dashboard, Screen, each pane | Every setting the panel's own menu has, in the same order and words, and you can change each one here. Screen and some panes end with their [blueprints for Home Assistant](blueprints.md#adding-one-from-the-manage-page), such as Panel night mode. A pane's page has its on/off at the top right and shows what it's doing; a pane that's off says what that means. The radio's page links to the Thread join |
| The device's page (S1 Plus, or the device's model) | What belongs to the device and to no pane. On the S1: **Block Aqara cloud**, the cloud daemon's state, which of Aqara's apps are turned off, the radio's mode (it opens the Radio page) and **Silent updates**. Elsewhere: **Silent updates**, whether the app is the device owner, the model and the Android version. **Silent updates** says whether an update from Home Assistant installs with nobody at the panel, and why; where it can't, how to change that on this kind of device, in amber ([Silent updates](esphome.md#silent-updates)) |
| Log | The last 7 days, kept across reboots. Filter by warnings or errors, search, and **Download diagnostics** |
| Security | The browsers signed in, each with **Sign out**. Where the app may answer USB debugging (the Mirror): **Allow a new computer**, for the one asking now, and the **Allowed computers**, each with **Remove** |
| About | Versions, frame, licences |

On a phone the menu folds behind the ☰ button. Light or dark follows your phone or computer.

Not on the page, because they need someone at the panel: the first Wi-Fi, Bluetooth's "Allow",
Aqara's own settings app, and pasting a Thread dataset (it carries the network key, and the page is
plain HTTP). Every pane page has what the panel's page has. The radio page has the role, what to do
next for it (the ZHA serial path, the OpenThread Border Router URL, the joined Thread network), the
Zigbee stack, and the role's buttons: Join and Leave, and **Get it from Home Assistant** for a Thread
repeater. A new role, a new stack and each Leave ask first, with the panel's words. The QR codes stay
on the panel. The wall base page has the relays'
switches and each button's last press, or what a base adds when none is mounted. A relay's switch
shows what the base reports, so reload to see it change. The Bluetooth proxy page shows what the panel's shows: how it's listening, the advertisements, scanner
and connections tiles, the mode (*Listen only* or *Listen and connect*), and its entities, with
**Turn on Bluetooth** or **Turn on location** when one of them stops it.
The time zone page has everything the panel's has: the zone, picked from a
list grouped by region, whether the panel keeps it, and Home Assistant's zone, with **Use it** when
Home Assistant keeps another zone's clock.

## Set up over the network

A panel already on Wi-Fi can be set up from a laptop, with no cable and no tap on its screen (#34). Open
`http://<panel>:8099/setup` on a computer on the same network: the panel's own page, a small build of
Kiln's setup steps. Kiln's Done screen links it after an install over USB. A panel without touch shows
the address and a QR code on its own setup screen (below); the Manage page and the panel's settings say it
too, in **Allow setup over the network**.

1. **Pair.** Press **Show the code on the panel**. The panel shows a 6-digit code ("Set up this panel from
   a computer"). Type it on the page and press **Pair**. Only someone who can see the panel can pair.
2. **Wi-Fi**, only on a panel that is on no network: the page reached it on the panel's own setup Wi-Fi
   (#197). Pick a network the panel sees and type its password. The panel closes its setup Wi-Fi and
   joins; its own screen shows how it goes, since the page loses the panel there. If the page still reaches
   the panel 45 seconds later, its setup Wi-Fi never closed and the join didn't start: the page says so, with
   **Try again**. A panel already on a network skips this step, and refuses Wi-Fi details from the page.
3. **Home Assistant.** The address the panel found, or type one, and sign in on the panel's sign-in page.
4. **Radio.** What the Zigbee and Thread radio does, then what to do in Home Assistant for it.
5. **Done.** The page ends the pairing. Reload it and it asks for a code again (once Home Assistant has the
   panel, allow setup over the network first, below).

The page is plain HTTP on your local network. While you pair, others on the same network can see the code
you type and the session cookie. The session only works from the address that paired, which limits replay.
A Wi-Fi password is no exception: the page sends it in plain text over its WebSocket (`ws://`), like everything
else. The panel takes one only while it is on no network, which in practice means over its own setup Wi-Fi. That
link is WPA2, with a key shown only on the panel's screen, so the password is encrypted on the air; anyone else who
joined the setup Wi-Fi (with the key from the screen) could still read it. On your own network the page doesn't ask
for a password and the panel refuses one, but nothing encrypts the page there either: it is plain `ws://`, as Home
Assistant is over `http://`.

| | |
|---|---|
| A wrong code | 5 tries per code. 10 wrong tries in 15 minutes lock code entry for a while. At most 6 new codes in 10 minutes |
| How long a pairing lasts | Until Done, 30 minutes without a change, or 2 hours. Reading the panel's state doesn't count as a change: after 30 quiet minutes the page asks for a new code and carries on at the same step. If Home Assistant has added the panel in the meantime, allow setup over the network first |
| Which browser | The one that paired, from the address it paired from. A computer whose address changes (a `.local` name that switches between IPv4 and IPv6) pairs again |
| Without pairing | The page and the panel's channel answer what the panel's state is, and refuse every change |

**Pairing is off once Home Assistant has added the panel.** Nobody on the network can pair with it then.
If Home Assistant adds the panel during setup, pairing closes but the session you have carries on. After
Done, or once that session has run out, a new code alone can't resume: allow setup over the network first.
It stays allowed for 30 minutes. Any of these allows it:

- In Home Assistant, the panel's device page: **Allow setup over the network** (a button, under Configuration).
- On the panel: Settings → **Home Assistant & dashboard** → **Allow setup over the network**. The Manage page has
  the same row.
- With Kiln over USB: at Done, Kiln allows it before it links the page.

**Panels without touch.** On a frame whose screen takes no tap (`input = NONE`, the lululemon Mirror's), the
panel's setup screen only shows where to set it up: the page's address and a QR code ("Scan to set it up."),
and Sign in's QR code for a phone. The pairing code shows without Close, and goes when it's used or runs
out. Wi-Fi comes from Kiln over USB first.

**What the page can't protect against.** It is plain HTTP on your network, as the Manage page is: no
browser trusts a certificate for a panel's local address. The session cookie is bound to the address that
paired, so a copy taken off the network doesn't work from another device. Something that can also take
over that address on your network could still use it, until Done or the 30 quiet minutes end it. The panel
has no write timeout on the page's connection: a browser that stops reading holds one of the panel's web
workers until the network gives up (minutes), or until another paired browser or Done ends it.

### The panel's own Wi-Fi

A panel that can't reach any Wi-Fi opens a network of its own for setup (#197), so a phone can set it up with
no cable and no Bluetooth (an iPhone too).

1. **When it opens.** The panel has had no connection for 3 minutes since it started, or you pressed **Set up
   from a phone** on its Wi-Fi step. It never opens while the panel is on a network.
2. **Join it.** The setup screen shows a QR code: scan it with the phone's camera to join. The network's name
   and password are under it too, for typing. Then open the address it shows (`http://<address>:8099/setup`)
   and pair as above.
3. **Send your Wi-Fi.** The page starts with Wi-Fi, since the panel is on no network. Pick yours and type the
   password. The panel closes its own Wi-Fi and joins; its screen shows how it goes. Put the phone back on
   your own Wi-Fi.
4. **When it closes.** Once Wi-Fi is set, after 15 minutes, or with **Close it**, and when setup is done. Then
   the panel tries your Wi-Fi again for 3 minutes, and opens its own again if that still fails. **Close it**
   keeps it closed until the next Wi-Fi is set.
   One limit: Wi-Fi details sent while the panel is still on its old network (over the LAN, Bluetooth or USB)
   keep its own Wi-Fi ready for 3 minutes only. A panel that is still on the old network after that, and fails
   to join the new one later, doesn't open its own Wi-Fi by itself: restart it, or press **Set up from a phone**.

| | |
|---|---|
| The network list | The panel scans just before it opens its own Wi-Fi, and the setup page lists what it saw then: on most devices the radio can't scan while it is the access point. A network that isn't listed can be typed under **Other…** |
| On the panel's screen | **Set up from a phone** shows "Starting the setup Wi-Fi…" at once, then the QR code to join (a few seconds; up to about 20 when the scan is slow). Once a phone on its Wi-Fi opens any page on the panel, the QR code becomes the setup page's address: Android gives an app no list of who joined, so that request is the sign. A start that fails says why there, with **Try again** |
| Who can join | Anyone who sees the panel's screen: the password shows only there, and changes each time (WPA2). Pairing still needs the code the panel shows |
| While it's open | On most devices, the S1 among them, the panel's Wi-Fi client is off: it is on no network until the hotspot closes. 2.4 GHz. Android may close it sooner than 15 minutes when nobody joins (10 minutes on the S1); then it counts as closed, and opens again 3 minutes later if no Wi-Fi works |
| Wi-Fi turned off | From Android 10 an app can't turn Wi-Fi on. With the panel's Wi-Fi off, it doesn't open its own: it couldn't join what the phone sends. The setup screen says so, with **Turn on Wi-Fi** |
| Android 8 and newer | Android's local-only hotspot: Android names it (`AndroidShare_…` on the S1) and picks its key. No setting changes. On Android 13 and newer the panel asks for *Nearby devices* first; on 8 to 12 it needs location on |
| Android 6 and 7 | A hidden Android call opens it as `Glassworks-…`. The panel puts back the device's own hotspot settings and its Wi-Fi when it closes |
| A captive portal | Not yet: the page doesn't open by itself. It comes later, on panels with root |

## Changing settings

A change applies on its own, as on the panel: a switch when you click it, a slider with **Save**, a
value, a time or a choice from its dialog. The panel checks each value as its own controls would (a
slider's range, a duration's choices), so the page can't set anything the panel wouldn't. A change
that only takes effect when the app starts again says so and offers **Restart now**. A switch that
asks first on the panel (turning the radio off) asks here too. The panel's menu, if it's open, shows
the new value within a couple of seconds. The page doesn't follow the panel on its own: its values,
the sliders' included, are the ones from when it was drawn, so reload it to see a change made on the
panel or in Home Assistant. Each change goes in the Log with who made it.

A sign-in (a camera stream's user name and password) says Set or Not set. Its password is write-only:
no page, on the panel or here, shows it again once it is saved. Open it to change the user name, to
type a new password, or to **Clear** both. Leave the password empty to keep the stored one. Write the
password down when you set it: Protect, Home Assistant's ONVIF integration and Frigate need it, and
the panel can't give it back. Forgot it? Set a new one, and type the new one into those too. The Log
records who set or cleared it, never the password.

**Either sign-in can turn panes on and off**, as on the panel. A switch the panel asks about first,
such as turning the radio off, asks the same question here, and nothing changes until you answer it.
In Home Assistant these switches only answer the panel's key; the code on the panel stands for being
at the panel.

## Signing in

1. **Continue with Home Assistant.** You sign in on Home Assistant's own login page, so the panel
   never sees your password. Only **administrators** get in. The panel uses the Home Assistant
   address it already has, and only when that address is on your network (a private IP or a `.local`
   name). It never uses a Nabu Casa or other public address.
2. **Use the code on the panel** when Home Assistant is down. The panel shows a 6-digit code for 5
   minutes; type it in the browser. If you sign in this way before the panel has a Home Assistant
   address, that sign-in ends when the panel first stores one; sign in again then.

**Remember this browser for 30 days** keeps you signed in on that browser. Without it, the sign-in
ends when you close the browser (at the latest after 12 hours). Security lists every signed-in
browser and signs any of them out. All of them are signed out when the panel's Home Assistant
address changes.

**Rebooting asks for the code on the panel** too, because a reboot takes the radio's network down
for everyone for about a minute. The Reboot button only shows where the app can reboot the panel
(when it's the device owner).

**Administrators only, for now.** Like Home Assistant's own device pages, only an administrator can
manage a panel. Whether other users get a say is open (#124).

## How it keeps you safe

- The panel asks Home Assistant who you are, checks you're an administrator, and **revokes Home
  Assistant's token straight away**. It keeps no Home Assistant token from your sign-in. If Home
  Assistant doesn't take the revoke, the panel tries once more and then logs a warning; that token
  then stays under your profile in Home Assistant (*Security → Refresh tokens*) until you delete it. Its own
  long-lived sign-in (the one the dashboard uses) is separate, and the Manage page never shows or
  sends it.
- Your browser gets the panel's own sign-in cookie: `HttpOnly` (scripts can't read it) and
  `SameSite=Strict` (other sites can't use it). The panel stores only a hash of it.
- Every form must come from the page itself: its `Origin` is checked, and it carries a token only
  your signed-in page has. A link or form on another site can't make the panel do anything.
- The code on the panel works once, for one browser and one purpose, for 5 minutes. Five wrong tries
  end a code; ten wrong tries in 15 minutes lock code entry for everyone until they're 15 minutes old.
  No new code goes up within 30 seconds of the last one, even when that one was used up or closed
  (unless it was typed right): asking again from the same browser keeps it, and another browser has
  to wait, so nobody can keep replacing your code. At most 6 new codes go up in 10 minutes, from all
  browsers together, so nobody can keep the screen lit with codes.
- A browser must send its whole request within 10 seconds, so a slow one can't hold the panel's few
  connections.
- If the panel can't draw the code (the app may not draw over other apps), the page says so instead
  of waiting for a code that never shows.
- The page answers only to the panel's own addresses and names (its IP addresses, its ESPHome name as `.local`,
  its Android host name), so another name pointed at it (DNS rebinding, or a spoofed `.local` answer)
  gets nothing.
- No scripts, no outside files, and a strict Content-Security-Policy.
- The log and the diagnostics file have tokens, keys, passwords (including `Basic` credentials and a
  user and password in an address), every value in an address's query (`?auth=…`), Thread datasets and
  MAC addresses removed. It goes by what these look like, so it can miss a secret in an unusual form:
  look over a file before you attach it to an issue.

**What plain HTTP on your network doesn't protect.** The page is `http://`, like Home Assistant on
`http://`. Someone who can read your Wi-Fi traffic can see:

- **The panel's cookie.** With it they can use the Manage page as you until it's signed out. The
  cookie gets them nothing else: not Home Assistant (the panel already revoked that token), and not a
  reboot (that needs the code on the panel's screen).
- **Your Home Assistant password, if Home Assistant itself is on `http://`.** You type it on Home
  Assistant's own login page, not on the panel, so the panel never has it; but over plain HTTP that page
  sends it in the clear, as it does whenever you sign in to Home Assistant over `http://`. Revoking the
  token doesn't change the password. HTTPS for Home Assistant is what protects it on the way;
  multi-factor sign-in adds a second step, so a caught password alone doesn't get anyone in.
- **The sign-in's exchange with Home Assistant, if Home Assistant is on `http://`.** The panel trades
  the one-time code for Home Assistant's tokens, asks who you are and revokes them, over the address it
  has, so over `http://` these pass in the clear for a few seconds. Revoking the refresh token also ends
  its access token, so a caught token works only until then. If Home Assistant refuses the revoke twice,
  the refresh token stays until you delete it (see above), and the Log says so. Someone who can *change* that traffic could
  also answer for Home Assistant and sign in as an administrator. This is a choice: most Home Assistant
  installs on a home network are `http://`, the panel's own dashboard sign-in crosses the same way, and
  the code on the panel is there for whoever would rather not.
- **A camera password you type here.** Setting a camera's password from this page sends it to the
  panel over the same plain HTTP, as every form does. To keep it off the network, set it on the
  panel's own screen instead. The panel never sends a stored password back.
- **Nothing limits how often a signed-in browser changes settings.** Each change needs the session and
  its form token, and goes in the Log, but there is no rate limit on them: a signed-in browser (or
  someone holding its cookie) can change settings as fast as it likes.
- **Nothing stops someone on your network from using up the codes.** Anyone who can reach the panel
  can ask for codes without signing in. After 6 in 10 minutes the panel shows no more, so for up to 10
  minutes nobody can sign in with the code (signing in with Home Assistant still works). The Log says
  *Too many code requests* with the address that asked.

Someone who can *change* traffic can also change the page. Use a network you trust; HTTPS on the panel
may come later.

## For developers

The server is `dev.glassworks.manage.ManageServer`; a frame supplies a `ManageHost` (status, actions,
diagnostics). See the [core's README](../pane-api/src/main/java/dev/glassworks/README.md#the-manage-page).
