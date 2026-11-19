# The Manage page

Every panel serves a Manage page at `http://<panel>:8099/` once it's set up. Open it from Home
Assistant (the panel's device page, **Visit**) or type the address: the panel's IP address
(`http://192.0.2.40:8099/`). Its ESPHome name ([names](esphome.md#names)) is not a host name you can
open: the panel's mDNS advert points at Android's own host name, and nothing publishes `<name>.local`.
Other names for it, such as one your router gives it (`panel.lan`) or a name with a trailing dot, get
"Open this page with the panel's
address": the page only answers to its own addresses and names (see below). It shows how the panel is doing,
keeps a log, and runs the everyday actions. It's part of the Glassworks core, so every frame has it.

Status: **checked on an S1 Plus on 2026-11-14** (#178): sign-in through Home Assistant, every page, and HA's
**Visit**. Not checked on a panel yet: that Home Assistant keeps no token for the page after a sign-in (#33).
It has the same settings as the panel's own menu, and changes them the same way.

## What's on it

| Page | What it has |
|---|---|
| Overview | Running or not, the app's version ("· test build <commit>" on a test build) and how long the app has run. On the legacy build (Android 6 and 7 devices and the Mirror), a **Legacy** chip next to the version: hover it, tap it or tab to it to read its limits (a frozen web engine that loads only your Home Assistant, remote debugging off); Home Assistant, Wi-Fi (connected or not from Android's connectivity service; the network's name and signal where Android gives them to the app), the web engine, memory; every pane the panel can run, with its state (running, off, or can't run now and why). A pane the device has no hardware for isn't listed, here or on the panel. Buttons: **Reload dashboard**, **Restart app**, **Reboot**, **Check for updates** |
| Home Assistant & dashboard, Screen, each pane | Every setting the panel's own menu has, in the same order and words, and you can change each one here. Screen and some panes end with their [blueprints for Home Assistant](blueprints.md#adding-one-from-the-manage-page), such as Panel night mode. A pane's page has its on/off at the top right and shows what it's doing; a pane that's off says what that means. The radio's page links to the Thread join |
| The device's page (S1 Plus, or the device's model) | What belongs to the device and to no pane. On the S1: **Block Aqara cloud**, the cloud daemon's state, which of Aqara's apps are turned off, the radio's mode (it opens the Radio page) and **Silent updates**. Elsewhere: **Silent updates**, whether the app is the device owner, the model and the Android version. **Silent updates** says whether an update from Home Assistant installs with nobody at the panel, and why; where it can't, how to change that on this kind of device, in amber ([Silent updates](esphome.md#silent-updates)) |
| Log | The last 7 days, kept across reboots. Filter by warnings or errors, search, and **Download diagnostics** |
| Security | The browsers signed in, each with **Sign out** |
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
The camera page has what the panel's has, on every frame, touch screen or not: the two cards of
"What may the camera do?" (*Presence only* and *Presence + camera*), privacy, and the ONVIF stream.
Either sign-in can change them. The stream's "Needs Presence + camera." links to them.
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
and Sign in's QR code for a phone. Once the page has signed it in, the screen waits for Home Assistant to add
the panel (Settings → Devices & services, or the old entry's re-authenticate after a reinstall), with the
page's address small below. The pairing code shows without Close, and goes when it's used or runs
out. Wi-Fi comes from Kiln over USB first. Such a screen often has no presence sensor either, so until Home
Assistant has added the panel, the setup screen turns it on when it comes up. A set-up panel that loses Wi-Fi
stays dark.

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
panel or in Home Assistant. One page reloads itself: a tablet's Device page while it has the dock row
(*Docked* or *Not docked*, [esphome.md](esphome.md#docked)), every 3 seconds, but never while a dialog is open.
A line under its heading says so, with **Pause**; paused, the page stays still until you click **Resume**.
Each change goes in the Log with who made it, and a choice with what it is
now: "Alex set Camera › Camera use to Presence + camera".

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
ends when you close the browser (at the latest after 12 hours). The Home Assistant sign-in the panel keeps for
All panels doesn't end when you close the browser: it ends when you sign out, when the Manage page stops, or at the
panel's check about a minute after the session runs out (12 hours at most without *Remember*). Sign out to end it
at once. Security lists every signed-in
browser and signs any of them out. All of them are signed out when the panel's Home Assistant
address changes.

**Rebooting asks for the code on the panel** too, because a reboot takes the radio's network down
for everyone for about a minute. The Reboot button only shows where the app can reboot the panel
(when it's the device owner).

**Home Assistant's name gives more than one address.** A `.local` name can resolve to Home
Assistant's IPv6 address as well as its IPv4 one, also on a network where the panel has no IPv6 (an
mDNS reflector relays it from another VLAN). The panel tries the addresses the way a browser does: an
address that doesn't answer gets a quarter of a second, then the next one starts, and the first to
connect wins. Older versions tried only the first address, and the sign-in failed after about 9
seconds.

**Administrators only, for now.** Like Home Assistant's own device pages, only an administrator can
manage a panel. Whether other users get a say is open (#124).

## How it keeps you safe

- The panel asks Home Assistant who you are and checks you're an administrator. Anyone else's token is
  **revoked straight away**. An administrator's sign-in is kept in the panel's memory for that browser's Manage
  session only, so All panels needs no second sign-in. It is never written to disk, a log or the
  diagnostics. The panel revokes it in Home Assistant when you sign out (here, from Security or on All panels) and
  when its Manage page stops. It also checks about once a minute for sessions that ran out, and revokes their
  sign-ins then. If Home Assistant doesn't take a revoke, the panel tries once more, logs a warning, and tries again
  at its later checks; as the Manage page stops there is no later check, and the Log says the sign-in stays. When
  the panel's Home Assistant address changes, it drops the sign-in without revoking it: the token never goes to the
  old address, where something else may answer now (the panel's own sign-in does the same). The Log says so, and the
  sign-in stays under your profile until you remove it. If the app restarts, the panel forgets it without
  revoking it; it then stays under your profile in Home Assistant (*Security → Refresh tokens*) until it expires
  or you delete it. Its own
  long-lived sign-in (the one the dashboard uses) is separate, and the Manage page never shows or
  sends it.
- Your browser gets the panel's own sign-in cookie: `HttpOnly` (scripts can't read it) and
  `SameSite=Strict` (other sites can't use it). The panel stores only a hash of it.
- Every Manage form must come from the page itself: its `Origin` is checked, and it carries a token only
  your signed-in page has. A link or form on another site can't make the panel do anything. One form is meant to
  come from another page: the sign-in handoff from another panel's All panels (`/manage/ha/token`). It skips the
  `Origin` check and needs a Home Assistant administrator's token instead, which the panel checks with its own Home
  Assistant; without one it does nothing.
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
- No scripts, except on All panels ([below](#other-panels-through-home-assistant)): one script module, in two
  files the panel serves itself. No outside files, and a strict Content-Security-Policy.
- The log and the diagnostics file have tokens, keys, passwords (including `Basic` credentials and a
  user and password in an address), every value in an address's query (`?auth=…`), Thread datasets and
  MAC addresses removed. It goes by what these look like, so it can miss a secret in an unusual form:
  look over a file before you attach it to an issue.

**What plain HTTP on your network doesn't protect.** The page is `http://`, like Home Assistant on
`http://`. Someone who can read your Wi-Fi traffic can see:

- **The panel's cookie.** With it they can use the Manage page as you until it's signed out, but not
  reboot (that needs the code on the panel's screen). If you signed in with Home Assistant, it also gets
  them what All panels gets: fresh Home Assistant access tokens for your administrator account, for as long
  as the session lasts (up to 30 days with *Remember this browser*) or until you sign out of All panels. A
  session signed in with the code on the panel gets no Home Assistant token.
- **Your Home Assistant password, if Home Assistant itself is on `http://`.** You type it on Home
  Assistant's own login page, not on the panel, so the panel never has it; but over plain HTTP that page
  sends it in the clear, as it does whenever you sign in to Home Assistant over `http://`. Revoking the
  token doesn't change the password. HTTPS for Home Assistant is what protects it on the way;
  multi-factor sign-in adds a second step, so a caught password alone doesn't get anyone in.
- **The sign-in's exchange with Home Assistant, if Home Assistant is on `http://`.** The panel trades
  the one-time code for Home Assistant's tokens, asks who you are and revokes them, over the address it
  has, so over `http://` these pass in the clear. Revoking the refresh token also ends its access tokens. An
  administrator's refresh token stays valid while the panel keeps it for All panels, so a caught one works
  until you sign out or the session ends; anyone else's is revoked within seconds. If Home Assistant refuses the revoke twice,
  the refresh token stays until you delete it (see above), and the Log says so. Someone who can *change* that traffic could
  also answer for Home Assistant and sign in as an administrator. This is a choice: most Home Assistant
  installs on a home network are `http://`, the panel's own dashboard sign-in crosses the same way, and
  the code on the panel is there for whoever would rather not.
- **The Home Assistant token All panels carries.** All panels sends your Home Assistant access token, over the
  same plain HTTP, to each listed panel on your network that answers as a panel (its `hello` names the model Home
  Assistant has). One shown as **Not a panel** or **Not on your network** gets no token. Someone who can read your Wi-Fi traffic could use it as you, in Home
  Assistant too, until it runs out (30 minutes) or you press **Sign out** on that page while Home Assistant is
  reachable. The page keeps it in the tab; close the tab or sign out when you're done.
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

## Other panels, through Home Assistant

**All panels**, after Overview in the menu, sets up every panel in the house from one page (#466).

1. **Signing in.** Signed in to this Manage page with Home Assistant, All panels uses that sign-in: the panel
   hands the page a fresh Home Assistant access token from it (same page, its form token), so there is no
   second sign-in. That holds only while the panel keeps it. After **Sign out** on All panels, or when Home
   Assistant no longer takes it, the Manage session stays but All panels asks for **Continue with Home
   Assistant** again. After the app restarts, a browser that ticked *Remember this browser* is still signed in to
   Manage, and All panels asks for Continue; any other browser signs in to Manage again first. If this Manage
   page's own sign-in has ended, All panels says so, with **Sign in**, when it next asks this panel to renew or end the sign-in it keeps: on loading, when its token runs out (Manage and Copy renew it then), or at Sign out. Until then, and in a tab signed in to Home Assistant on its own, Manage and Copy go on with the token the tab has. Only a sign-in with Home Assistant on this
   panel's own Manage page is kept: one that came through **Manage** on another panel's All panels keeps none, so
   All panels on this panel asks for Continue. Signed in with the code on the panel, the
   page asks for Continue too: the
   tab signs in to Home Assistant on its own (with PKCE), with an administrator, and keeps the sign-in until you
   close the tab. **Sign out** on the page ends it in Home
   Assistant too, when Home Assistant answers. If it doesn't, the page says the sign-in may stay active there, with
   **Try again**; you can also remove it in Home Assistant under your profile, Security, Refresh tokens.
2. **The panels.** The page asks Home Assistant for its devices and lists each ESPHome device whose **Visit**
   link is a Manage page (port 8099), with its area, address and version. A panel that doesn't answer within
   6 seconds shows as **Down**; the others still work. A panel on a version from before this page shows as Down
   too. A panel that answers but can't use your sign-in says why instead: **Refused** with its reason (its Home
   Assistant didn't confirm the sign-in, or you're not an administrator), **Busy**, or **No answer** when its
   check with Home Assistant takes longer than 15 seconds. Each row fills in as its panel answers. One that answers as some other device shows as **Not a panel** and gets nothing; one at an address off your
   network shows as **Not on your network** and isn't contacted. A panel busy asking Home Assistant about other
   sign-ins is asked again: 3 tries at most, and 5 seconds of waiting in all.
3. **Manage** opens that panel's own Manage page in a new tab, already signed in.
4. **Copy settings.** Pick a panel to copy from and one to copy to. The page lists every setting that differs,
   ticked, as *its value now → the new value*. Settings the other panel's version or hardware doesn't have,
   values it can't take (an option its version lacks), sign-ins and each panel's own Home Assistant address are
   greyed out and never sent. **Copy** changes only what is ticked, on that panel alone. A change that asks first on the panel (the radio) is
   listed with the panel's question and **Change them too**.

All panels is the one Manage page with a script, served by the panel itself. Its Content-Security-Policy
lets it connect to this panel's Home Assistant and to any HTTP host on port 8099, and post forms to any such
host: the policy can't name the other panels, so it doesn't limit them to Manage pages or to your network. The
script does that itself: it contacts only names and addresses that pass the filter below, and sends the token only
after hello. The filter checks the name or address as Home Assistant lists it, not what a name resolves to. A
policy can't name an IPv6 address, so with the panel's Home Assistant set as one (`http://[fd00::10]:8123`) All
panels says to use a name or an IPv4 address instead. The other pages still have no scripts.

Each panel lets a page on another panel read and change its settings. That page holds a Home
Assistant access token, and each panel checks it with its own Home Assistant. No panel trusts another,
and there are no shared keys and no leader. The page gives your Home Assistant token only to devices Home
Assistant lists as Glassworks panels: an ESPHome device whose Visit link is a Manage page at an address on your
network (a private, link-local or loopback address, `localhost` or a `.local` name; anything else gets no
request at all), and that
first answers a question without the token (`/manage/api/hello`) with the model Home Assistant lists for it.

That check is a filter, not proof. The model is public, so anything can answer with it. A device that takes over
an existing panel's address on your network passes it with nothing done in Home Assistant. Adding a discovered
ESPHome device is one click on a card whose name the device picks. So only add ESPHome devices you recognise, and
use All panels on a network you trust.

- **Who may.** Administrators only, as on the page itself. The panel asks its Home Assistant who the token
  belongs to (`auth/current_user`), at the address it uses, and only when that address is on your network.
  It keeps the answer for a minute, so a token revoked in Home Assistant still works here for up to a minute.
  An answer counts only for the address that gave it: when the panel's Home Assistant address changes, the
  page has to sign in again.
  A token Home Assistant didn't confirm stays refused for 10 seconds, except for a sign-in from another panel's
  page, which always asks. The panel asks Home Assistant about at most two tokens at a time: while Home Assistant
  is slow, the rest are told to try again in a moment. One address (one computer, usually) can start at most
  five such sign-ins in 10 seconds; then it waits.
  Whether other Home Assistant users get a say is still open.
- **What it reads.** Every setting the page can change, with its value as text, the way the page's forms send
  it. A sign-in (a camera's) says only whether it is set: no password or user name leaves the panel.
  Actions such as **Allow setup over the network** aren't listed.
  The panel's own Home Assistant address (which is also its dashboard's) is marked as its own: All panels never
  copies it, and a page on another panel can't change it.
- **What it changes.** One setting at a time, through the same checks and saves as the panel's own menu.
  A change that asks first on the panel (turning the radio off) answers with the panel's question and a
  one-time id. It runs only when the same token sends the same change again with that id, within 5 minutes,
  as the page's own dialogs do. A sign-in and an action can't be changed this way. Each change goes in the Log
  with the user and the address of the page that sent it.
- **Signing in from another panel's page.** That page can also sign your browser in here with its token,
  so the panel's own Manage page opens without another Home Assistant sign-in. The panel asks Home Assistant
  about the token then and there, without the minute-old answer or a recent refusal. The sign-in lasts until
  the browser closes. The panel doesn't keep or revoke the token: it belongs to the other page.
- **What it can't protect.** The token travels over plain HTTP, as it does to a Home Assistant on
  `http://`. Someone who can read your Wi-Fi traffic could use it until it expires (30 minutes) or the
  other page signs out. The panel never writes the token anywhere, including the Log and the diagnostics.

For developers: `GET` and `POST` `/manage/api/settings` with `Authorization: Bearer <token>`, form-encoded
`section`, `key`, `value` and, for a question's answer, `answer=<id>` (`ManageApi`); `POST /manage/ha/token`
with `token` and `next` signs the browser in (`ManageServer.HANDOFF`). `GET /manage/api/hello` needs no token
and answers only that this is a Glassworks panel and its model as Home Assistant shows it. Any page on any
site can read it, so a site you visit can learn that a panel answers at an address on your network, and its
model; no token, setting or name of yours comes with it. Any page may call them (CORS `*`): no cookie counts
there. Chrome's private-network permission goes only to a page on the local
network (a private address, `localhost` or a `.local` name).
