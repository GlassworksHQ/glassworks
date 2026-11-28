# Try Vitro

Vitro is an experimental way for Glassworks to show your Home Assistant dashboard. Instead of loading the dashboard in a browser, the panel reads the dashboard you already have and draws each card with its own native views. It's meant for low-power panels, where the browser is the heaviest thing running. Your dashboard stays in Home Assistant, written the same way as before, and nothing gets installed there.

> [!NOTE]
> **Feature status:** Vitro is experimental and off by default. Some cards still look slightly different from Home Assistant, and some aren't drawn natively yet. While it's off, the app shows your dashboard in its browser the way it always has, and turning it off again puts the dashboard back the same way.

## Where it runs

Vitro comes with every Glassworks app: the Aqara S1 Plus app, Glassworks for generic Android (Android 8 and newer), and that app's legacy build for Android 6 and 7, which the lululemon Studio Mirror runs. It has been tried most on the S1 Plus and on Android tablets. The legacy build has seen the least testing so far, so expect rough edges there.

## Turn it on

1. Open **Settings → Labs** on the panel, or **Labs** on its [Manage page](manage.md) in a browser.
2. Turn on **Vitro renderer**.
3. The app restarts and opens your dashboard with Vitro.

To go back, turn **Vitro renderer** off the same way. The app restarts and shows your dashboard in its browser again.

## If something goes wrong

If the app keeps failing right after it starts with Vitro on, it turns Vitro off by itself. Two failed starts in a row, each within a minute of starting, are enough. The panel then opens your dashboard in its browser, as it did before you turned Vitro on, and tells you what happened:

- a short message on the panel says Vitro was turned off because the app kept stopping;
- **Settings → Labs** says **Vitro was turned off** and why, until you turn Vitro on again;
- the **Engine** tile on the Manage page's Overview turns red, reads **Vitro turned off after crashes**, and opens Labs.

These count as a failed start:

| What happens | When it counts |
|---|---|
| The app crashes | Within a minute of starting, before or after the dashboard shows |
| The app stops some other way, such as a crash inside a native library or closing it when Android says it "isn't responding" | Before the dashboard shows. After it shows, only on Android 11 and newer, where Android says it was a crash or that the app wasn't responding |
| The app freezes | When it stops responding for 30 seconds within the first minute. The app then closes itself so it can start again |

These never count: the panel restarting or losing power, an app update, Android closing the app to free memory, someone stopping the app, the app restarting itself after a settings change, and a start that shows the setup or sign-in screen instead of a dashboard. Turning Vitro off or on, or resetting the panel, starts the count over.

A dashboard that misbehaves without the app stopping won't turn Vitro off, so turn it off yourself. When the panel's own settings menu is hard to reach, turn Vitro off from **Labs** on the Manage page. Developers with adb can also start the app with the extra `--ez vitro false`, which turns Vitro off and restarts the app.

If Vitro turned itself off, please [report it](#report-a-card-that-looks-wrong) and attach the panel's log: **Download diagnostics** on the Manage page's **Log** saves it.

## What works

Vitro draws 41 of Home Assistant's built-in card types and many popular HACS cards natively, including most of the Mushroom set, Bubble Card's buttons and separators, button-card (when it doesn't use JavaScript templates), auto-entities, swipe-card, stack-in-card and layout-card. It reads your themes, your fonts and the card-mod styles it can draw the way Home Assistant does. Tapping an entity opens a native More Info dialog for lights, climate, covers, fans, locks, media players, vacuums, updates, sensors and more.

Vitro only draws what it can draw exactly the way Home Assistant does, and anything else still shows:

- **A custom card** that Vitro can't draw, or one with an option it doesn't support, opens in a small browser view of its own, so it looks the way it does in Home Assistant.
- **A view that needs Home Assistant itself** opens the way Home Assistant draws it, in the app's browser. That happens for a view with a built-in card Vitro doesn't have (such as the map), a built-in card with an option Vitro doesn't draw yet (such as an hourly forecast or a tile feature it lacks), a view Home Assistant generates from a strategy, and a view with more custom cards than the panel can run in small browser views at once.
- **A More Info dialog** Vitro doesn't draw yet, such as a timer's or a person's, opens Home Assistant's own dialog over the same view.

Vitro follows the whole dashboard from the moment it opens, starting with the view on screen so that one draws first, so switching views doesn't wait for Home Assistant. If a custom card can't get the panel's data, its place says **Connection lost. Reconnecting…** until it can, instead of showing an empty or wrong card.

To see how your own dashboard will do, paste it into the [Vitro compatibility check](https://www.glassworks.dev/blueprints#vitro-check). It runs in your browser and lists every card that won't be drawn natively, and why. The full list of cards, and what each one leaves out, is in [Vitro's README](../engines/engine-vitro/README.md#what-works).

If you use an AI coding agent, it can do the same audit for you and go further. The repo includes an agent skill, [vitro-native-dashboard](../.agents/skills/vitro-native-dashboard/SKILL.md), that checks a dashboard against the same card list and helps rewrite it so its cards draw natively wherever a native card can stand in, keeping your entities, actions and layout. Point your agent at the skill and your dashboard's YAML, and keep a copy of the original before it changes anything.

## See how Vitro draws your dashboard

While Vitro is on, **Settings → Labs** on the panel, and **Labs** on the Manage page, show how it draws your dashboard right now. The page follows the view on screen, so it changes when you switch views or edit the dashboard.

| What Labs shows | What it means |
|---|---|
| **On screen now** | The view on screen and who draws it: Vitro, every card natively; Vitro, with some cards in small browser views of their own; or Home Assistant's frontend, with the reason Vitro left it there |
| A row for each card in a browser view | Where the card sits, such as `home › section 2 › card 3 (custom:mushroom-media-player-card)`, and why Vitro doesn't draw it natively |
| **Each view** | Every view of the dashboard: **Native**, how many **browser views** it runs, or **Home Assistant's frontend** |

Each browser view is a small browser of its own, which costs the panel memory, so a view with fewer of them runs lighter. A view you haven't opened yet is judged from its cards. Some things only show once the view is open, such as the rows an auto-entities card matches, so its line can change when you open it.

On the Manage page, the Overview's **Engine** tile says the same for the view on screen in a few words (**All native**, **2 browser views** or **HA's frontend**) and opens Labs. The Labs page reloads itself every few seconds to keep up; **Pause** stops that.

While Vitro is off, Labs shows only the switch.

## Report a card that looks wrong

Home Assistant's own look is the goal, so any difference is worth a report. [Open a bug report](https://github.com/GlassworksHQ/glassworks/issues/new?template=bug.yml) with:

- the card's YAML, with entity names and anything private taken out,
- a screenshot of the card in Home Assistant and one on the panel with Vitro on,
- your panel and its Glassworks version.

If the card doesn't show at all, or shows as a small browser view you didn't expect, the compatibility check's reason for that card helps too.
