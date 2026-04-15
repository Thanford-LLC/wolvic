# FingerDance

FingerDance is a fork of Wolvic for Meta Quest that replaces ray-casting pointer navigation with joystick combo gestures. Instead of aiming and clicking, you flick the thumbstick in directional sequences to trigger browser commands. Muscle memory replaces pointer hunting.

## What's different from upstream Wolvic

This branch (`fingerdance`) adds a native combo-input layer on top of Wolvic. Nothing about the Chromium/GeckoView engine or the Wolvic UI has been removed — FingerDance lives alongside the existing ray-cast input as an additional input source.

**Added components:**

- `app/src/fingerdance/cpp/` — native combo engine. `ComboWindowEngine` implements edge-triggered activation with 4-dir (90° quadrants) or 8-dir (45° wedges) zone detection, magnitude hysteresis, and center-dwell auto-emit.
- `app/src/common/shared/com/igalia/wolvic/input/ComboDispatcher.java` — Java dispatch table. Maps recognized paths (e.g. `[2,6,6]` = flick up then right twice) to browser commands (forward, new tab, URL bar, etc.) via Wolvic's `Windows`, `Session`, and `NavigationURLBar` APIs.
- `app/src/common/shared/com/igalia/wolvic/ui/widgets/ComboHUDWidget.java` — floating in-world HUD that shows the dial, lit preview wedge, activated-node dots, and collapsed path count. In 4-dir mode the HUD mirrors the dispatcher's cardinal-collapse interpretation.
- `app/src/main/assets/fingerdance/homepage.html` — curated home page with search / video / productivity / shopping / social shortcut sections. No affiliate IDs in v1.

**Modified Wolvic files:**

- `VRBrowserActivity.java` / `VRBrowser.{h,cpp}` / `BrowserWorld.cpp` — JNI plumbing between OpenXR input and the combo engine, plus a JNI setter for 4-dir ↔ 8-dir mode.
- `OpenXRInputSource.{h,cpp}` — feeds per-frame axis/button state into `ComboWindowEngine::Process()`.
- `NavigationBarWidget.java` / `NavigationURLBar.java` — expose `focusUrlBar()` and `bookmarkCurrentPage()` for dispatcher use.
- `WindowWidget.java` / `Session.java` — minor hooks for window rotation and scroll dispatch.

## Build

Same Gradle flow as upstream Wolvic. The combo engine is pulled into `app/CMakeLists.txt`; no extra CMake invocation needed.

```bash
./gradlew :app:assembleOculusvrArm64GeckoGenericDebug
~/Android/Sdk/platform-tools/adb -s <device>:5555 install -r \
  app/build/outputs/apk/oculusvrArm64GeckoGeneric/debug/Wolvic-oculusvr-arm64-gecko-generic-debug.apk
```

### Install hygiene

The combo dispatcher stores user preferences in Android SharedPreferences. When iterating during development, force-stop, uninstall, and clear data before each reinstall — `install -r` alone can leave stale state that makes previews and HUD behavior look inconsistent.

```bash
ADB=~/Android/Sdk/platform-tools/adb
$ADB -s <device>:5555 shell am force-stop com.igalia.wolvic
$ADB -s <device>:5555 uninstall com.igalia.wolvic
$ADB -s <device>:5555 install app/build/outputs/apk/.../Wolvic-...-debug.apk
```

## Engine tests

Native unit tests live in `app/src/fingerdance/tests/`. They build with host CMake (not Android):

```bash
cd app/src/fingerdance/tests
cmake -B build -S . -DCMAKE_BUILD_TYPE=Debug
cmake --build build
./build/fingerdance_tests
```

## Input model

**Activation is edge-triggered, not dwell-based.** Two triggers:

- **E1** — magnitude crosses upward into the "at max" region (≥ `ACTIVATION_MAGNITUDE`, 0.70). Activates the current zone. Fires once per push. Re-arms only after magnitude drops below `ACTIVATION_RELEASE` (0.50) — hysteresis prevents jitter at the boundary from double-firing.
- **E2** — while already at max, the zone changes. Activates the new zone. Enables sliding-rim arcs (e.g. `[6,8]` by sweeping from right down to bottom without releasing).

**Preview** lights the wedge when magnitude is in `[0.25, 0.70]` and the stick is in a non-center zone AND the engine is not currently latched at max. At max, the wedge turns off — a confirmation dot outside the dial carries the "activated" signal.

**Auto-emit on center dwell.** Once the path has content, returning to center (mag < 0.20) for `CENTER_EMIT_DWELL_MS` (800ms) emits the combo to the dispatcher.

**Cardinal enumeration with run-length collapse.** The dispatcher runs a two-pass match: first with the raw path (8-dir), then with adjacent-duplicate collapse to absorb spurious diagonals during cardinal arcs (e.g. a `[2,3,6]` mid-arc activation still matches `[2,6]`).

## Current command set

See `TODOS.md` in the FingerDance project repo for the authoritative combo-to-function table. Implemented in v0: back, forward, new tab, close tab, duplicate tab, next/prev tab, URL bar, search in page, bookmark toggle/add, refresh, stop, scroll {up,down,left,right,top,bottom}, window rotate left/right. Reader mode is stubbed.
