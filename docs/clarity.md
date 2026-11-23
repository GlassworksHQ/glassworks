# Clarity performance grades

Spec sheets don't tell you whether a wall panel will actually feel responsive on a real Home Assistant dashboard. **Clarity** is our open performance benchmark that tests panels running Home Assistant's real frontend and boils the results down to a single honest letter grade from **A+** to **E**.

## What each grade means

Each letter answers one practical question: **how busy a Home Assistant dashboard can this device run while everyday interactions still feel smooth?**

| Grade | What it means |
|---|---|
| **A+** | Handles heavy dashboards smoothly, with 60 fps scrolling and responsive camera playback within tight stall and dropped-frame limits |
| **A** | Great wall panel even for large, dense dashboards |
| **B** | Great wall panel for typical dashboards with a live camera stream |
| **C** | Great wall panel for typical dashboards without a live camera |
| **D** | Best suited for light, focused dashboards |
| **E** | Struggles to feel responsive even on light dashboards |

Because a live WebRTC camera feed is the heaviest load most dashboards carry—and plenty of wall panels don't show cameras—Clarity grades typical dashboards both without a camera (**C**) and with a live 720p camera (**B**). Separate from dashboard load, a cold restart to the reference dashboard that takes 5 seconds or more also caps the grade at **C**, even if the panel passes the heavy dashboard tests.

## What Clarity measures

Instead of synthetic browser benchmarks, Clarity loads a pinned version of the real Home Assistant frontend on standardized **light**, **typical**, and **heavy** reference dashboards served from a local test server. Every device receives the same cards, live entity updates, and camera stream over a wired USB connection or quiet local network—note that wireless ADB or LAN camera streaming over a noisy Wi-Fi link can still affect measurements and mark a result **provisional**.

On each dashboard, Clarity tests the interactions you notice every day on a wall panel:

- **Tapping controls** while live state updates are arriving
- **Scrolling and flinging** through cards
- **Live update latency** — how fast a state change reaches the screen
- **Opening views** — how quickly a view draws and becomes responsive
- **More-info dialogs** — opening lights, thermostats, and sensor history graphs
- **Live camera streams** — how quickly the first frame appears and how smoothly video plays

Some devices, like the lululemon Mirror, have no touchscreen, so nobody can scroll them. Clarity doesn't grade those devices on scrolling. It skips the swipes and grades every other task as usual, and the report says "Scrolling: not applicable, this frame has no touch." A missing scroll result doesn't make their grade provisional, though anything else that's missing still does.

All thresholds come from published web and mobile UX standards (Core Web Vitals, RAIL, and Android vitals) rather than numbers tuned to any particular piece of hardware.

## Reading a device's Clarity card

Every supported device page on [glassworks.dev/frames](https://www.glassworks.dev/frames) shows the device's Clarity grade computed directly from its published measurements, along with:

- **What holds it back:** A plain-English line naming the exact task that kept the device from earning the next letter up.
- **Per-page breakdown:** How the panel performed on light, typical (with and without a camera), and heavy dashboards.
- **Memory and headroom:** Idle CPU, app memory usage, and the RAM impact of optional panes like Assist voice or Sendspin music, so you know how much headroom you have left.
- **Provisional or pending labels:** If a measurement had a missing metric or ran over a noisy link, Clarity marks the grade **provisional** and explains why. Devices that haven't been benchmarked yet show **Measurements pending**.

## Run Clarity yourself

Clarity is completely open—anyone can run it on their own hardware or inspect how every metric is scored. For the full benchmark specification, scoring rubric, and `tools/perf/run.sh` instructions, see the [Clarity benchmark spec](perf.md).
