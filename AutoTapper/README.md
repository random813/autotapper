# AutoTapper (core v0.2)

Phone-native auto-tapper for Android. Place tap points on the screen, size them, set a
random or fixed interval, and let them run over any app. No root.

## What's in this build

- Floating **bubble** over other apps → tap it for the menu: **＋ Tap point · ▶ Start · ✕ Close**.
- **Marker** = circle with a red centre dot. Drag to move; drag the **handle** to resize.
  Big circle → each tap lands on a random point inside it. Shrink it below the threshold and
  it becomes an exact **crosshair** (no randomness). Fully reversible.
- **Tap to select, tap again to lock** (locked = frozen, nothing drifts). Unlock from the
  toolbar's lock button (right side).
- **Selected-marker toolbar** (top of screen): ⚙ interval editor · "every 1–3s" · click
  counter · ✛ nudge crosspad (move 1 px) · 🔒 lock.
- **Interval per point**: min–max with ms / s / min units; same min & max = fixed.
- The marker **fades while dragging** so you can see the target; while running the overlay
  goes near-invisible and untouchable so taps pass through. The red dot **flashes on every
  dispatched tap**.
- Positions stored as **normalized coordinates** — setups survive rotation and transfer
  between screen sizes approximately.
- Tap points are **saved automatically** and restored next time.

## Build it free with GitHub Actions

1. Create a free GitHub account and a new repository (public is fine).
2. Upload the contents of this folder to the repo (keep the folder structure, including
   `.github/workflows/android.yml`).
3. Open the repo's **Actions** tab → the **Build APK** workflow runs automatically
   (or press "Run workflow").
4. When it finishes, download the **AutoTapper-debug-apk** artifact and unzip it to get
   `app-debug.apk`.

## Install & first run

1. Copy `app-debug.apk` to your phone and open it (allow "install unknown apps" if asked).
2. Open AutoTapper:
   - Step 1: grant **Draw over other apps**.
   - Step 2: enable **AutoTapper** under **Settings → Accessibility** (this is what
     performs the taps — the service does not read screen content).
   - Step 3: **Start floating controls**.
3. Open the app you want to tap, tap the bubble → **＋ Tap point**, position/size the
   marker, set the interval via ⚙, then bubble → **▶ Start**. Tap the bubble again to stop.

## Project layout (modular — features bolt on next)

- `TapAccessibilityService.kt` — dispatches taps/gestures (the only file that touches input).
- `OverlayService.kt` — foreground service owning the overlay.
- `OverlayController.kt` — bubble, markers, toolbar, nudge pad, interval editor, run loop.
- `MarkerView.kt` — the circle/crosshair marker rendering + touch handling.
- `TapPoint.kt` — the data model (normalized coords) + JSON persistence.

Planned next modules: keyboard presses, system buttons (Back/Home/Recents), swipes,
sequences (in-order mode with repeats), run limits & pause, flashlight/SOS/morse.
