---
title: "Introducing Clarity: an open performance grade for Home Assistant wall panels"
date: 2026-12-03
author: "Eduardo"
summary: "Spec sheets can't tell you whether a wall panel will feel snappy on your actual dashboard. Clarity runs the real Home Assistant frontend on fixed reference dashboards and gives every device an honest A+ to E grade."
cover_alt: "We gave our own flagship panel a D, above an A+ to E grade scale: B for the Pixel Tablet (heavy dashboards block 0.9 s), D for the Aqara S1 Plus (typical dashboards block 2.7 s) and E for the lululemon Mirror (taps take 480 ms)."
draft: true
---

<!--
Draft. Text in [brackets] waits on the release; fill it from each frame's clarity/clarity.json
on the day it goes out. The date in the folder name and `date:` is a placeholder too.
-->

Earlier this week I shared [Glassworks 1.0](https://www.glassworks.dev/blog/glassworks-1-0). Today I'm introducing **Clarity**—an open performance benchmark and A+ to E grading system for Home Assistant wall panels.

## The question spec sheets can't answer

Every time I look at a new wall panel or cheap tablet, I have the same question: will this actually feel good to use every day? There are a lot of panels out there—like the SONOFF NSPanel Pro—that look sleek on a product page and fine on paper, only to feel painfully sluggish once you throw a real Home Assistant dashboard at an underpowered processor.

Hardware specs barely help. Knowing a panel has a quad-core A53 and 2 GB of RAM doesn't tell you whether tapping a light feels instant or takes a full second, or whether opening a history graph freezes the screen. Reviews are usually just someone saying "feels snappy" or "a bit laggy" on whatever tiny or massive dashboard they happen to run on their own Wi-Fi.

When a screen hangs on your wall, those tiny interactions are the whole experience. You tap a toggle on your way out the door, glance at an updating sensor, or pull up a camera feed. If those feel sluggish, you stop using the panel. So I built a repeatable way to measure them across any device.

## One letter from A+ to E

Clarity boils a full test run down to a single letter grade, answering a practical question: **how heavy a Home Assistant dashboard can this device run while still feeling responsive?**

| Grade | What it means |
|---|---|
| **A+** | Handles heavy dashboards smoothly, with 60 fps scrolling and responsive camera playback within tight stall and dropped-frame limits |
| **A** | Good wall panel, even with heavy dashboards |
| **B** | Good wall panel for typical dashboards, including a live camera feed |
| **C** | Good wall panel for typical dashboards without a camera feed |
| **D** | Best kept to light, simple dashboards |
| **E** | Struggles even with light dashboards |

Every grade is paired with a single plain-English summary saying what the device handles well and what kept it from the next letter up.

## How the test actually works

Instead of synthetic JavaScript benchmarks, Clarity runs a pinned version of the **real Home Assistant frontend** on three standardized reference dashboards (light, typical, and heavy). A lightweight mock Home Assistant server runs locally on your computer and replays the same entity states and live update stream every time. Because your own server and custom cards aren't part of the equation, two panels tested months apart are measured under matching conditions over a wired USB connection or quiet local network (wireless ADB or LAN camera streaming over noisy Wi-Fi can affect results and marks a run provisional).

On each dashboard, Clarity tests the things you actually do on a wall panel:

- **Tapping controls** while live state updates are streaming in
- **Scrolling and flinging** through cards
- **Live update latency** — how fast a state change from Home Assistant reaches the pixels on screen
- **Switching views** across dashboard tabs
- **Opening more-info dialogs** for lights, thermostats, and sensors with history graphs
- **Live camera streams** — how quickly the first frame appears and how smoothly video plays
- **Cold restart time** back to a usable dashboard

## Built to be hard to game

A benchmark only matters if people trust it, so we set a few strict ground rules from the start:

- **Industry thresholds.** Passing and failing numbers come from established web and mobile guidelines—Google's Core Web Vitals, RAIL, and Android vitals—rather than arbitrary numbers we made up. Anywhere we had to choose a cutoff ourselves, the spec explains why.
- **Perceived responsiveness over vanity numbers.** Humans immediately feel a 400 ms delay after tapping a light; they don't care whether a background repaint hit 54 or 60 fps. The grade weights what you actually notice.
- **No moving the goalposts.** The rubric was written before devices were graded against it. Rules can only change publicly for reasons that apply to every panel—never to bump a specific device up a letter.
- **Everything is open.** [The full spec](../../../docs/perf.md), test runner, reference dashboards, and published results all live in the repo so anyone can verify our numbers or test their own hardware.

## Grading our own panel first

To keep ourselves honest, we started with our own reference device. The Aqara Panel Hub S1 Plus grades **D**—best kept to light dashboards, held back by typical dashboards where the browser's main thread blocks for 2.7 seconds after the view is drawn. It's an older 32-bit chip doing real work on Home Assistant's modern JavaScript frontend. We spent a lot of time trying to squeeze more speed out of it and documented [everything that worked and everything that didn't](../../../frames/aqara-s1-plus/research/view-open.md).

I'd much rather publish an honest D than massage the test into a B. If you know we won't fudge the grade on our own flagship device, you can actually trust the A's when you see them.

That honest D also forced a better question: if we won't bend the test to make a slow browser look fast, why is a wall panel running a browser engine at all? It's why we're building **[Vitro](https://github.com/GlassworksHQ/glassworks/issues/790)** for **Glassworks 1.1** (*Settings → Labs*)—a native Android renderer for the Home Assistant dashboard you already have—and grading it against the web engine under these exact same rules.

![Bar charts of four Clarity measurements for the Pixel Tablet (B), Aqara S1 Plus (D) and lululemon Mirror (E): restart 2.9, 14.0 and 23.5 s; light-dashboard taps 32, 72 and 480 ms; typical dashboards blocked 0 ms, 2.7 s and 4.5 s; heavy dashboards blocked 0.9 s and 31.5 s, and out of memory on the Mirror](./grades-comparison.webp)

Every Glassworks frame gets a Clarity run, and its grade appears right on [glassworks.dev/frames](https://www.glassworks.dev/frames) and inside Kiln:

- **Google Pixel Tablet: B** — good for typical dashboards with a live camera; heavy dashboards block for 0.9 s.
- **Aqara Panel Hub S1 Plus: D** — best kept to light dashboards; typical dashboards block for 2.7 s.
- **lululemon Studio Mirror: E** — 930 MB RAM and Android 6 make taps take 480 ms on light dashboards, and heavy dashboards run out of memory.

## Beyond the letter grade

A Clarity run also logs metrics that don't affect the letter grade but are genuinely useful when setting up a panel: per-process memory usage, idle CPU, animation headroom, restart time on **your own** dashboard, and—when you enable the optional `--pane-ram` flag—the RAM cost of turning on each pane. If you want to know whether enabling Assist voice or Music Assistant will push an older panel over its memory limit, the report shows you directly.

## Run it on your own panel

Even though Clarity started as Glassworks' own benchmark, nothing about it is tied to Glassworks. It already runs fine on other Android wall panels, tablets, and kiosk apps today.

If you build or test wall panels, run Clarity on your hardware and share the results. Join us on [Discord](https://discord.gg/hHpf77fBMj) or [open a discussion](https://github.com/GlassworksHQ/glassworks/discussions) if you spot a flaw in the rubric—the rules evolve in public for every device at once.

Eduardo
