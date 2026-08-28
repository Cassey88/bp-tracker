# BP Tracker

Small Android app for logging blood-pressure readings from an Omron monitor.
One row per reading (date + time), photo of the display optional, CSV export
and import.

## How a reading is entered

1. Tap the state toggle if needed — 😴 **Resting** (default) or 🚴 **Exercise**.
   It resets to Resting every time the app opens, so a stale toggle can never
   silently mislabel a morning reading.
2. Tap **📷 Photo** to photograph the monitor display. Optional — the photo is
   kept as a visual record next to the typed numbers, not read automatically.
3. Type SYS / DIA / PULSE and tap **Save**. Date and time are stamped from the
   clock; the rating is computed, never typed.

Tap a row to open its photo. Long-press a row to delete it.

## Rating bands

Derived from the systolic and diastolic values on save, using the European /
NZ classification. **The worse of the two numbers decides the band** — 130/79
rates as ⚠️ Watch on the systolic alone.

| Rating | Systolic | Diastolic |
|--------|----------|-----------|
| 👍 Good  | under 120 | under 80 |
| 🆗 OK    | 120–129   | 80–84    |
| ⚠️ Watch | 130–139   | 85–89    |
| 😞 High  | 140+      | 90+      |

Readings taken after exercise are left unrated (dimmed –). Those thresholds
only mean anything for a resting measurement, so rating a post-qigong reading
against them would make the history look worse than it is.

## CSV format

```
date,time,state,systolic,diastolic,pulse,rating
2026-08-29,08:35,exercise,130,79,87,-
2026-08-30,07:12,resting,124,78,71,ok
```

- Dates are stored ISO (`yyyy-mm-dd`) and only rendered as dd/mm/yyyy on screen,
  so import/export round-trips stay unambiguous.
- `state` and `rating` are plain words rather than emoji — emoji in CSV mangles
  depending on what opens the file.
- Import matches on **date + time**, so re-importing your own export updates
  rows instead of duplicating them. Restoring onto a new phone is safe.
- Import re-derives the rating from the numbers rather than trusting the file.
- Photos are **not** in the CSV. They live in `Pictures/BPTracker` and don't
  transfer with an export.

Export writes to `Downloads/` and then opens the share sheet.

## Build

No wrapper JAR is committed; the GitHub Actions workflow installs Gradle. Push
to `main` and the APK is published as a Release asset (tappable on the phone)
as well as a build artifact.

Locally:

```
gradle assembleDebug
```

- Kotlin, minSdk 29 (Android 10), targetSdk 34
- **Zero external dependencies** — platform widgets and SQLiteOpenHelper only.
  No AndroidX, so no FileProvider: the camera hand-off and the CSV export both
  go through MediaStore, which also means the app requests no permissions.
