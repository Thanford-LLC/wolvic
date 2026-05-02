#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <functional>

namespace glyphew {

// Tunable constants — validate on Quest hardware before shipping.
static constexpr float   ACTIVATION_MAGNITUDE       = 0.70f;  // threshold to consider joystick "at max" (edge-triggered activation)
static constexpr float   ACTIVATION_RELEASE         = 0.50f;  // mag must drop below this to leave the "at max" latch (hysteresis)
static constexpr float   PREVIEW_MAGNITUDE          = 0.25f;  // magnitude threshold to light up a wedge preview
static constexpr float   CENTER_THRESHOLD           = 0.20f;  // magnitude below which = center (released)
static constexpr int64_t CENTER_EMIT_DWELL_MS       = 800;    // how long at center before emitting combo
static constexpr int     MAX_PATH_LENGTH            = 8;
// Phase 3b: throttle threshold for the continuous preview-progress callback.
// Process() emits only when |progress - last| >= this OR when the zone snaps.
static constexpr float   PREVIEW_PROGRESS_EPSILON   = 0.02f;
// Round-10: sliding-rim arm dwell. While mAtMax, a zone change no longer
// commits instantly — it must hold for ARM_DWELL_MS first, so the HUD can
// show the stick-reactive fill ramping up to commit instead of snapping.
static constexpr int64_t ARM_DWELL_MS               = 80;
// Phase 6: thumbstick long-press threshold. Gate is grip-OFF; during grip-HELD
// the same thumbstick-click edge is consumed for silent-cancel. Firing once
// at this threshold opens Combos Settings via the registered callback.
static constexpr int64_t LONG_PRESS_MS              = 800;

// Grid layout:
//   1  2  3
//   4  5  6
//   7  8  9
// 5 = center/neutral (never recorded in path).
// Axis convention: X positive = right (node 6), Y positive = up (node 2).

struct ComboEvent {
    std::array<int, MAX_PATH_LENGTH> path{};
    int length = 0;
};

using EventCallback           = std::function<void(const ComboEvent&)>;
using ComboProgressCallback   = std::function<void(const ComboEvent&)>;
using PreviewCallback         = std::function<void(int previewNode)>;
// Phase 3b: continuous progress in [0, 1] for how hard the user is leaning
// toward the current preview zone. zoneId=0 means idle (below preview band).
using PreviewProgressCallback = std::function<void(int zoneId, float progress)>;
// Phase 6: fires once per long-press, after thumbstick button has been held
// for LONG_PRESS_MS with grip released. Called on the OpenXR thread — the
// receiver is expected to marshal to the UI thread before touching view state.
using LongPressCallback       = std::function<void()>;

class ComboWindowEngine {
public:
    explicit ComboWindowEngine(EventCallback callback,
                               ComboProgressCallback onProgress = nullptr,
                               PreviewCallback onPreview = nullptr);

    // Phase 3b: register/replace the continuous preview-progress callback.
    // Safe to call before Process() begins; single-threaded (same rules as
    // the rest of the engine).
    void SetPreviewProgressCallback(PreviewProgressCallback cb) {
        mPreviewProgressCallback = std::move(cb);
    }

    // Phase 6: register/replace the long-press callback. Fires once per
    // qualifying hold; safe to call before Process() begins.
    void SetLongPressCallback(LongPressCallback cb) {
        mLongPressCallback = std::move(cb);
    }

    // Per-frame. Returns true (consumed) while grip is held.
    bool Process(float axisX, float axisY, bool thumbstickBtn, bool gripBtn, int64_t timestampMs);

    // Silent cancel — e.g. OS overlay stole focus.
    void CancelSilent();

    // Mode control. 4-dir uses 4×90° quadrants; 8-dir uses 8×45° wedges.
    // Shared across instances (set from Java preferences via JNI); atomic
    // because the JNI write comes from the UI thread while Process() runs on
    // the OpenXR thread.
    static void SetFourDirMode(bool enabled) { sFourDirMode.store(enabled, std::memory_order_relaxed); }
    static bool IsFourDirMode() { return sFourDirMode.load(std::memory_order_relaxed); }

    int GetPathLength() const { return mPathLength; }
    int GetPathNode(int i) const { return (i >= 0 && i < mPathLength) ? mPath[i] : 0; }
    bool IsPathEmpty() const { return mPathLength == 0; }

private:
    // Instantaneous zone from axis — 4 or 8 zones depending on mode.
    static int NodeFromAxis(float x, float y);

    void EmitIfNonEmpty();
    void ResetPath();
    void AppendNode(int node);
    void UpdatePreview(int newPreview);

    // Phase 3b: fire mPreviewProgressCallback if zone changed OR |progress -
    // last| >= PREVIEW_PROGRESS_EPSILON. Stores the emitted sample so the
    // next call can decide whether to throttle.
    void MaybeEmitPreviewProgress(int zone, float progress);

    EventCallback           mCallback;
    ComboProgressCallback   mProgressCallback;
    PreviewCallback         mPreviewCallback;
    PreviewProgressCallback mPreviewProgressCallback{nullptr};
    // Phase 6: optional long-press callback; nullptr = no settings entry wired.
    LongPressCallback       mLongPressCallback{nullptr};

    bool    mPrevGrip           = false;
    bool    mPrevThumbstickBtn  = false;

    std::array<int, MAX_PATH_LENGTH> mPath{};
    int     mPathLength         = 0;
    int     mPreviewNode        = 0;

    // Edge-triggered activation state. mAtMax tracks whether magnitude is
    // currently in the "at max" region; flips true when mag crosses above
    // ACTIVATION_MAGNITUDE and false when it drops below ACTIVATION_RELEASE
    // (hysteresis prevents jitter at the boundary from double-firing).
    bool    mAtMax              = false;
    int     mLastActivatedNode  = 0;

    // Center-dwell timer for auto-emit.
    int64_t mCenterDwellStartMs = 0;

    // Round-10: sliding-rim arm dwell. During mAtMax, a candidate new zone
    // must remain the instantaneous zone for ARM_DWELL_MS before it commits.
    // mArmingZone tracks the candidate (0 = not arming). mArmingStartMs is
    // the timestamp the candidate first became the instantaneous zone.
    int     mArmingZone         = 0;
    int64_t mArmingStartMs      = 0;

    // Phase 3b: last-emitted preview-progress sample for throttling. Initial
    // -1.0f is a sentinel that forces the first real sample (even 0.0f) to
    // fire, so the HUD can always sync to a clean starting value.
    float   mLastPreviewProgressValue = -1.0f;
    int     mLastPreviewProgressZone  = 0;

    // Phase 6: long-press tracker. Active only in the grip-OFF code path.
    // Two scalar fields; zero heap per CLAUDE.md §5.2.
    int64_t mThumbstickDownTime = 0;
    bool    mLongPressFired     = false;

    static std::atomic<bool> sFourDirMode;
};

} // namespace glyphew
