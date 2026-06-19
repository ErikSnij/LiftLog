# LiftLog

A fast, **on-device-only** workout logger for Android. No accounts, no network, no
cloud — every set you log lives in a local Room database on your phone. The UI is
built for one thing: getting a set written down in as few taps as possible.

## Features

- **Dense, always-expanded tree** — Category → Area → Exercise → set rows, with
  pinned (sticky) category headers so you always know where you are while scrolling.
- **Exercise name on the left, values on the right** — each set row shows
  `reps × weight`, a trend flag, a mini history graph, and the date. Multiple sets
  of the same exercise wrap onto new lines without repeating the name.
- **Fast write loop** — tap a row's date to arm it, spin the inline iOS-style
  scroll-wheel pickers for reps/weight, then ✓ to commit or ✕ to cancel.
- **Trend flags** — cycle a set between neutral `±`, up `+`, and down `−`.
- **Per-row history chart** — a Canvas line chart with a Weight ↔ estimated-1RM
  (Epley) toggle, plus inline edit/delete of past entries.
- **Long-press actions** — edit note, rename exercise, add a set row, archive, or
  delete, all with an Undo snackbar for the reversible ones.
- **Archive & resurrect** — hide retired set rows, toggle them back into view.
- **JSON export** — share your whole log out via the Android share sheet.

## Tech

- Kotlin + Jetpack Compose + Material 3 (phone, portrait only)
- Room (with KSP) for persistence; WAL mode, dates stored as epoch-day
- `kotlinx.serialization` for seed import / JSON export
- Single-Activity, no navigation library — a simple screen switch in `MainActivity`

## Architecture

```
data/        Room entities, DAOs, type converters, derived-date projections
data/seed/   first-launch seed import + JSON export
ui/          TreeScreen (main list + write loop), TreeViewModel (all state/actions),
             HistoryScreen (chart + edit/delete), WheelPicker, Format (formatting + Epley)
```

The hierarchy is **Category → Area → Exercise → SetRow → LogEntry**. A set row's
"last performed" dates are *derived* (never stored) as the max entry date, excluding
archived rows. `reps`/`weight` are nullable: a null weight renders as `BW`
(bodyweight) and a null reps as `?`.

On first launch the app seeds itself from `app/src/main/assets/liftlog_seed.json`
(guarded by a category count, so it won't re-import on later launches).

## Build & run

```bash
./gradlew :app:installDebug      # build + install to a connected device/emulator
```

Requires the Android SDK (set `sdk.dir` in `local.properties`). Built against a
fresh AGP 9 / Kotlin 2.2 scaffold; `compileSdk 37`.
