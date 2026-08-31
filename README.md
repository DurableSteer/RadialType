<div align="center">

# RadialType

**A transparent radial gesture keyboard for Android**

Type without blocking your screen. Write faster with syllable input.

[Status: Prototype] [Platform: Android] [Language: Kotlin] [License: TBD]

</div>

---

## Why RadialType?

Traditional software keyboards occupy a third of your screen and cover the
content you are typing into. RadialType takes a different approach: the
keyboard is **almost completely transparent**, so the underlying screen stays
readable at all times, and input happens through a **single continuous finger
gesture** instead of tapping discrete keys.

On top of that, RadialType introduces **syllable typing**: dwell on a letter
and a radial submenu of frequent two-letter syllables appears, letting you
produce two characters with one gesture — fewer gestures per word, faster
writing.

## How It Works

1. **Press anywhere** in the transparent keyboard area. Your touch point
   becomes the center of a radial menu (invisible in production mode).
2. **Glide to a target.** Two concentric rings with 8 segments each surround
   your finger. The segment you are in selects a character. Distinct haptic
   pulses signal ring and segment changes.
3. **Dwell to enter a syllable menu.** Hold briefly in a segment and the
   primary menu dissolves into a secondary ring of frequent two-letter
   syllables starting with the selected character.
4. **Lift to commit.** The currently highlighted character or syllable is
   inserted into the text field. Pull your finger out of the syllable ring to
   escape back to the primary menu.

While typing, a single floating label above your finger shows the current
selection — the only visible element in production mode.

```
  Finger down ──▶ Glide ──▶ (optional) Dwell ──▶ Glide ──▶ Lift
                  │                          │             │
             primary ring              syllable ring    commit text
             2×8 segments              8 syllables
```

## Feature Overview

- **Fully transparent input area** — the app underneath stays readable while
  you type
- **Single-stroke input** — one continuous gesture from touch-down to lift
- **Radial menus** — 2 rings × 8 segments = 16 primary characters, anchored
  wherever you touch
- **Syllable typing** — dwell on a character to open a ring of frequent
  two-letter syllables; commit two characters per gesture
- **Haptic guidance** — distinct vibration patterns for segment changes, ring
  transitions, and mode switches, so the menu can stay invisible
- **Learning mode** — optional ring outlines and labels rendered on-screen
  while you train muscle memory
- **Configurable** — dwell duration, ring radii, haptics, auto-spacing and
  more
- **Offline & private** — no network permission, no telemetry

## Status

⚠️ **Prototype** — RadialType is an experimental project exploring a new
typing paradigm. Expect rough edges, changing layouts, and frequent breakage.
Not recommended as a daily driver yet.

Current state:

| Area | Status |
|---|---|
| IME scaffold & transparent view | 🔜 Planned |
| Touch state machine & radial geometry | 🔜 Planned |
| Haptics & dwell timer | 🔜 Planned |
| Syllable data & lookup | 🔜 Planned |
| Debug renderer & floating label | 🔜 Planned |
| Text commit & capitalization | 🔜 Planned |
| Settings UI | 🔜 Planned |

## Roadmap

- [ ] Core prototype (Modules 1–13, see project plan)
- [ ] Optimized character layouts informed by letter frequency
- [ ] Configurable character layouts per language
- [ ] German and additional language syllable sets
- [ ] Backspace / editing gestures
- [ ] Progressive learning mode (inner ring first, outer ring unlocks later)
- [ ] Performance instrumentation (gesture-to-commit latency)

## Requirements

- Android 8.0+ (API 26)
- Android Studio (latest stable recommended)
- Kotlin 1.9+

## Building

```bash
git clone https://github.com/<your-username>/radialtype.git
cd radialtype
./gradlew assembleDebug
```

Install the debug build on a connected device:

```bash
./gradlew installDebug
```

Then enable the keyboard under **System Settings → Languages & Input →
On-screen keyboard → RadialType**.

## Contributing

This project is in its exploratory phase and the interaction design is still
evolving. Ideas, feedback on gesture feel, haptic tuning reports, and code
contributions are all welcome. Please open an issue before large changes so
we can discuss the direction first.
