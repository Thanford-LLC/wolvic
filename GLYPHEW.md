# Glyphew

Glyphew is a fork of Wolvic for Meta Quest that replaces ray-casting pointer navigation with joystick combo gestures. Instead of aiming and clicking, you flick the thumbstick in directional sequences to trigger browser commands. Muscle memory replaces pointer hunting.

This document covers the fork's build/test workflow, what's added or modified relative to upstream Wolvic, and current implementation status. Design-level spec lives at `~/Project/glyphew/PROJECT.md`.

---

## 1. Fork Overview

### 1.1 Relationship to Upstream
This branch (`glyphew`) adds a native combo-input layer on top of Wolvic. Nothing about the Chromium/GeckoView engine or the Wolvic UI has been removed — Glyphew lives alongside the existing ray-cast input as an additional input source.

### 1.2 Package Identity
- `applicationId`: `com.thanford.glyphew` (see `app/build.gradle`)
- Internal Java/JNI namespace: `com.igalia.wolvic` (kept intact so the MPL-licensed Wolvic code doesn't need to be renamed)
- Install target differs from upstream Wolvic (`com.igalia.wolvic`) — both can coexist on a device

### 1.3 License Model
- **New Glyphew files** — proprietary (see §4.1)
- **Modified Wolvic files** — retain MPL 2.0 header; changes ship under MPL 2.0

---

## 2. Quickstart

### 2.1 Build
Same Gradle flow as upstream Wolvic. The combo engine is pulled into `app/CMakeLists.txt`; no extra CMake invocation needed.

```bash
./gradlew :app:assembleOculusvrArm64ChromiumGenericDebug
```

Chromium backend is the default for Glyphew dev/test builds — its WebXR + immersive-session handling is more current than GeckoView (Mozilla's GV WebXR work has been largely frozen).

### 2.2 Install
```bash
~/Android/Sdk/platform-tools/adb -s <device>:5555 install -r \
  app/build/outputs/apk/oculusvrArm64ChromiumGeneric/debug/Wolvic-oculusvr-arm64-chromium-generic-debug.apk
```

### 2.3 Install Hygiene
The combo dispatcher stores user preferences in Android SharedPreferences. When iterating during development, force-stop, uninstall, and clear data before each reinstall — `install -r` alone can leave stale state that makes previews and HUD behavior look inconsistent.

```bash
ADB=~/Android/Sdk/platform-tools/adb
$ADB -s <device>:5555 shell am force-stop com.thanford.glyphew
$ADB -s <device>:5555 uninstall com.thanford.glyphew
$ADB -s <device>:5555 install app/build/outputs/apk/.../Wolvic-...-debug.apk
```

### 2.4 Engine Tests
Native unit tests live in `app/src/glyphew/tests/`. They build with host CMake (not Android):

```bash
cd app/src/glyphew/tests
cmake -B build -S . -DCMAKE_BUILD_TYPE=Debug
cmake --build build
./build/glyphew_tests
```

58 scenarios currently pass. Zero heap allocation on the hot input path is a hard invariant — if you add state, keep it in fixed-size fields on the engine.

---

## 3. Input Model

### 3.1 Activation
**Edge-triggered, not dwell-based.** Two triggers:

- **E1** — magnitude crosses upward into the "at max" region (≥ `ACTIVATION_MAGNITUDE`, 0.70). Activates the current zone. Fires once per push. Re-arms only after magnitude drops below `ACTIVATION_RELEASE` (0.50) — hysteresis prevents jitter at the boundary from double-firing.
- **E2** — while already at max, the zone changes. Activates the new zone. Enables sliding-rim arcs (e.g. `[6,8]` by sweeping from right down to bottom without releasing).

### 3.2 Preview
The HUD lights the wedge when magnitude is in `[0.25, 0.70]` and the stick is in a non-center zone AND the engine is not currently latched at max. At max, the wedge turns off — a confirmation dot outside the dial carries the "activated" signal.

### 3.3 Auto-Emit on Center Dwell
Once the path has content, returning to center (mag < 0.20) for `CENTER_EMIT_DWELL_MS` (800ms) emits the combo to the dispatcher.

### 3.4 Cardinal Enumeration with Run-Length Collapse
The dispatcher runs a two-pass match: first with the raw path (8-dir), then with adjacent-duplicate collapse to absorb spurious diagonals during cardinal arcs (e.g. a `[2,3,6]` mid-arc activation still matches `[2,6]`).

### 3.5 Mode Flag
`ComboWindowEngine::sFourDirMode` (atomic) is toggled from Java prefs via JNI setter. When set, the engine collapses diagonal inputs to nearest cardinal. 4-dir is the current v1.0 default; 8-dir is opt-in via Settings.

---

## 4. File Map

### 4.1 New Files

#### 4.1.1 Native (C++)
- `app/src/glyphew/cpp/ComboWindowEngine.h` / `.cpp` — state machine, edge-triggered activation, 4-dir/8-dir zone detection, hysteresis, center-dwell auto-emit
- `app/src/glyphew/cpp/InputComboRecognizer.h` / `.cpp` — OpenXR adapter feeding axis/button state into the engine

#### 4.1.2 Tests
- `app/src/glyphew/tests/CMakeLists.txt` — host build for gtest
- `app/src/glyphew/tests/ComboWindowEngineTest.cpp` — 58 state-machine scenarios

#### 4.1.3 Java
- `app/src/common/shared/com/igalia/wolvic/input/ComboDispatcher.java` — path → browser-command dispatch table (22 actions routed)
- `app/src/common/shared/com/igalia/wolvic/ui/widgets/ComboHUDWidget.java` — floating in-world HUD: dial, preview wedge, activated dots, collapsed path count

#### 4.1.4 Resources
- `app/src/main/assets/glyphew/homepage.html` — curated home page (search / video / productivity / shopping / social). No affiliate IDs in v1.

#### 4.1.5 CI
- `.github/workflows/glyphew-release.yml` — signed APK release workflow

### 4.2 Modified Wolvic Files

#### 4.2.1 Native
- `app/src/openxr/cpp/OpenXRInputSource.{h,cpp}` — feeds per-frame axis/button state into `ComboWindowEngine::Process()`
- `app/src/main/cpp/BrowserWorld.cpp` — wires the recognizer into the per-frame tick
- `app/src/main/cpp/VRBrowser.{h,cpp}` — JNI plumbing for combo events + 4-dir ↔ 8-dir mode setter

#### 4.2.2 Java
- `app/src/common/shared/com/igalia/wolvic/VRBrowserActivity.java` — JNI entry points for combo dispatch
- `app/src/common/shared/com/igalia/wolvic/ui/widgets/NavigationBarWidget.java` — exposes `focusUrlBar()` and `bookmarkCurrentPage()` for the dispatcher
- `app/src/common/shared/com/igalia/wolvic/ui/views/NavigationURLBar.java` — backing implementations of `focusUrlBar` / `bookmarkCurrentPage`
- `app/src/common/shared/com/igalia/wolvic/ui/widgets/WindowWidget.java` — window-rotation hooks, scroll dispatch
- `app/src/common/shared/com/igalia/wolvic/browser/engine/Session.java` — `about://home` symbolic URL, backend-specific homepage loading, `mOnHomePage` flag
- `app/src/common/shared/com/igalia/wolvic/ui/viewmodel/WindowViewModel.java` — "Home" title bar for `about://home`, insecure-icon suppression
- `app/src/common/shared/com/igalia/wolvic/utils/UrlUtils.java` — `ABOUT_HOME` constant, `isHomeUrl(...)` helper

#### 4.2.3 Build / Manifest
- `app/build.gradle` — `applicationId = "com.thanford.glyphew"`, CMake wiring for `app/src/glyphew/cpp/`

---

## 5. Implementation Status

### 5.1 Working
- ComboWindowEngine (58/58 tests pass)
- InputComboRecognizer OpenXR adapter
- JNI bridge (both directions)
- 4-dir mode flag end-to-end
- ComboDispatcher with 22 actions routed
- ComboHUDWidget (live 3×3 HUD)
- `about://home` symbolic URL + Chromium/Gecko backend-specific loading
- Library combo routing via `showLibrary(ContentType)` (fixes `chrome://history` blank-page bug)
- Homepage (static HTML bundled asset)
- CI/CD pipeline (signed APK release)

### 5.2 Stubbed
- Reader mode combo (recognizer fires, dispatcher no-ops)

### 5.3 Missing
- Combo Trainer (Day-1 curriculum, Day-1 complete screen)
- Settings screen (HUD toggle, Advanced Mode toggle, controller-assignment swap, My Combos editor)
- Custom combo creation + action picker
- Export / Import combos (clipboard + Share intent)
- Conflict detection on combo save
- Visual combo-feedback toast
- Combo debug log (JSONL, last 100)
- Shake-to-Reset (feasibility-gated on Meta IMU extension)
- Homepage 3×3 grip-nav grid (v1.0 ships static)
- Day 2+ "N new combos" HUD badge

### 5.4 Cross-Reference
- Full product spec: `~/Project/glyphew/PROJECT.md`
- Progress tracking: `~/Project/glyphew/PROGRESS.md`
- Method-level dispatch table: `~/Project/glyphew/TODOS.md`
- AI-tool instructions: `~/Project/glyphew/CLAUDE.md`

---

## 6. Current Command Set

The authoritative combo-to-function table lives in `~/Project/glyphew/TODOS.md`. Implemented in v0:

### 6.1 Navigation
- back, forward, refresh, stop

### 6.2 Tabs
- new tab, close tab, duplicate tab, next/prev tab

### 6.3 URL / Search
- focus URL bar, search in page

### 6.4 Bookmarks
- bookmark toggle, bookmark add

### 6.5 Scroll (C++ direct via `ControllerDelegate::SetScrolledDelta`)
- scroll up, down, left, right, top, bottom

### 6.6 Window
- rotate left, rotate right

### 6.7 Stubs
- reader mode (recognized, not yet wired)
