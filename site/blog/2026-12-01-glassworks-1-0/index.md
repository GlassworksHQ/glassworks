---
title: "Glassworks 1.0"
date: 2026-12-01
author: "The Glassworks project"
summary: "One ESPHome device, your dashboard, and panes for the rest. What 1.0 is, which devices it runs on, and what's next."
draft: true
---

<!--
Draft. Text in [brackets] waits on the release; fill it from CHANGELOG.md, each frame's frame.json
and each pane's pane.json on the day it goes out. The date in the folder name and `date:` is a
placeholder too. Add cover.webp (1200 × 630) and cover_alt, or the post uses its preview card.
-->

Glassworks turns a wall panel into a Home Assistant device. The panel shows your dashboard and
joins Home Assistant as one ESPHome device. There is nothing to install in Home Assistant, no
cloud and no account with us. Today we're tagging **1.0** [version, link to the release].

## What it is

Home Assistant is the brain. The panel is a screen, mics, a speaker, sensors and radios on a wall,
and Glassworks hands them to Home Assistant. The rules are in
[Glassworks Principles](../../../docs/principles.md).

A panel is built in layers. The **core** is what every panel needs: the dashboard, setup, the
ESPHome device, the screen policy and updates through Home Assistant. A **frame** describes one
device. A **pane** is a feature the panel can have or leave out, and says what hardware it needs.

## Frames

| Frame | Status | What it is |
|---|---|---|
| [Aqara S1 Plus](../../../frames/aqara-s1-plus/README.md) | Beta | The reference frame. Every pane runs on it |
| [Generic Android](../../../frames/generic-android/README.md) | Planned | Any Android device, as an app, no root. [Update if it ships in 1.0] |
| [lululemon Studio Mirror](../../../frames/lululemon-mirror/README.md) | Planned | A full-length mirror with an Android screen behind it. [Update if it ships in 1.0] |

## Panes

Voice with Assist, timers on screen, Music Assistant playback over Sendspin, a Zigbee coordinator
or Thread border router, a Bluetooth proxy, notifications, diagnostics and the camera. [List the
panes and their stages at 1.0, from each `pane.json`.]

## Kiln

[Kiln](https://kiln.glassworks.dev) installs a frame from the browser, over USB. It backs the
device up first and can put it back. [Which frames Kiln installs at 1.0, and whether it has done a
full install on a real panel.]

## Clarity

Every frame gets the same performance test and grade. The S1 Plus is graded **[grade at
release]**: [one line from its Clarity card]. How it works: [Clarity](../../../docs/perf.md).

## What's next

[Two or three lines from the roadmap. No dates we can't keep.]

Thanks to everyone who tested a build on a real panel. [Credits.]
