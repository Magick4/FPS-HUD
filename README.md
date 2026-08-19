# FPS-HUD

A live FPS counter for rooted Android, packaged as a **KernelSU / KernelSU-Next**
module (Magisk and APatch work too). No app to install, no *draw over other apps*
permission, no Xposed — a small root process pushes a system overlay window
straight into `WindowManager` and keeps it above games, video and the launcher.

```
  ┌──────────────────────────┐
  │ FPS 119  120Hz           │  ← always on top, ~0% CPU
  │                          │
  │        your game         │
  └──────────────────────────┘
```

## What's in the box

| | |
|---|---|
| **Overlay** | `app_process` + `ActivityThread` system context → `TYPE_SECURE_SYSTEM_OVERLAY` window |
| **WebUI** | Full settings panel in the KernelSU-Next manager: position, colours, size, source, layer |
| **Action button** | One tap to show/hide the HUD |
| **CLI** | `su -c fpshud …` for everything the WebUI does |
| **Watchdog** | Restarts the overlay if it ever dies, comes back after every reboot |

## Requirements

* KernelSU, **KernelSU-Next**, APatch or Magisk
* Android 8.0+ (tested target: **Android 16 / LineageOS**)
* Any chipset — Snapdragon devices get the exact panel frame rate from the
  kernel, everything else falls back to a vsync counter

Verified against the kernel sources of **LineageOS 23.2 (Android 16) for the
Motorola Edge+ 2022 / Edge 30 Pro (`hiphi`, SM8475)**: its `sde_crtc` driver
registers `measured_fps` on the DRM class, so the node lands at
`/sys/class/drm/sde-crtc-0/measured_fps` and prints
`fps: 143.87 duration:1000 frame_count:144` — exactly the format this module
parses.

## Install

1. Grab the flashable zip: [`dist/fox_live_fps-v2.1.0.zip`](dist/) (prebuilt), or
   from the [Actions artifacts](../../actions/workflows/build-ksu.yml).
2. KernelSU-Next → **Modules → Install from storage** → pick the zip.
3. Reboot (or run `su -c "fpshud start"` right away).
4. Tap **WebUI** on the module card to configure it, or **Action** to toggle it.

Building it yourself needs a JDK and the Android SDK:

```bash
cd ksu-module
ANDROID_HOME=~/Android/Sdk ./build.sh   # -> build/fox_live_fps-v2.1.0.zip
```

## How the FPS number is measured

1. **Kernel node (preferred, Snapdragon).** The display driver publishes the
   real panel commit rate at `/sys/class/drm/sde-crtc-*/measured_fps`. This is
   what the panel is actually pushing, so it drops to 60/30/24 when the ROM
   throttles the refresh rate, and it costs nothing to read.
2. **Vsync counter (fallback, any device).** The overlay registers a
   `Choreographer` frame callback and counts vsyncs over a one-second sliding
   window.

`fpshud probe` prints every node it can find on your device, and the WebUI shows
which one is in use. If your ROM exposes the counter somewhere unusual, set
`source=node` plus `node=/sys/…` and it will read that instead.

## CLI

```
fpshud start | stop | restart        # service control
fpshud toggle | enable | disable     # show/hide the overlay
fpshud status                        # JSON: running, pid, source, current fps
fpshud fps                           # current fps, once
fpshud probe                         # device + available kernel fps nodes
fpshud log [lines]                   # overlay log
fpshud get [key] | set <key> <value> # settings (applied live, no restart)
fpshud reset                         # back to defaults
```

`fpshud` lands in `/system/bin` through the module, so it works from any root
shell once the module is mounted.

## Settings

Everything lives in `/data/adb/fox_live_fps/config.prop` and is **hot-reloaded** —
the overlay notices the file changed and repaints without restarting.

| key | default | notes |
|---|---|---|
| `enabled` | `true` | master switch |
| `source` | `auto` | `auto`, `vsync` or `node` |
| `node` | | sysfs path when `source=node` |
| `interval` | `500` | text refresh, ms (100–5000) |
| `fps_period_ms` | `0` | Snapdragon: kernel averaging window; 0 keeps the 1 s default |
| `position` | `top_left` | `top/middle/bottom` × `left/center/right` |
| `x`, `y` | `16` | offset from that corner, dp |
| `text_size` | `13` | sp |
| `text_color` | `#00E676` | `#RRGGBB` or `#AARRGGBB` |
| `bg_color` | `#99000000` | background, alpha first |
| `bg_radius` | `8` | corner radius, dp |
| `padding_h`, `padding_v` | `8`, `3` | dp |
| `opacity` | `1` | 0.1–1.0 |
| `label` | `FPS` | prefix text, `none` for digits only |
| `show_hz` | `false` | append the panel refresh rate |
| `decimals` | `0` | 0, 1 or 2 |
| `dynamic_color` | `false` | green/amber/red vs. the panel rate |
| `color_good/ok/bad` | | thresholds' colours |
| `window_type` | `secure` | `secure`, `system` or `overlay` |
| `boot_delay` | `10` | seconds to wait after boot |

**Screen recordings:** `secure` sits on the highest layer but Android strips it
from screenshots and recordings. Switch the layer to `system` in the WebUI if
you want the counter captured; `overlay` is a fallback for ROMs that reject the
other two. The overlay tries all three in order and logs which one stuck.

## Troubleshooting

| symptom | fix |
|---|---|
| Nothing appears | `su -c "fpshud log"` — the log says which window type was rejected and why |
| Shows `--` | No kernel node and vsync isn't ticking; try `fpshud set source vsync`, or check `fpshud probe` |
| Reads 0 while idle | Normal: `measured_fps` reports 0 when the panel isn't committing frames |
| Number reacts slowly | `fpshud set fps_period_ms 250` — shrinks the kernel's averaging window |
| Not in screen recordings | `fpshud set window_type system` |
| Gone after an update | Module updates keep `/data/adb/fox_live_fps/config.prop`; just reboot |

## Layout

```
ksu-module/
├── module.prop            module metadata
├── customize.sh           installer (env checks, defaults, fps-node report)
├── service.sh             late_start service → fpshud boot (wait + watchdog)
├── action.sh              KernelSU-Next Action button → toggle
├── uninstall.sh           stops the overlay, keeps your settings
├── config.default.prop    shipped defaults
├── system/bin/fpshud      control CLI (also used by the WebUI)
├── webroot/               WebUI: index.html + style.css + app.js
├── src/fox/fps/           FpsOverlay.java → fps_overlay.dex
└── build.sh               javac + d8 + zip
```

Licensed under the GPL — see [LICENSE](LICENSE).
