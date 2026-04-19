#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <functional>

namespace fingerdance {

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

    // Phase 3b: last-emitted preview-progress sample for throttling. Initial
    // -1.0f is a sentinel that forces the first real sample (even 0.0f) to
    // fire, so the HUD can always sync to a clean starting value.
    float   mLastPreviewProgressValue = -1.0f;
    int     mLastPreviewProgressZone  = 0;

    static std::atomic<bool> sFourDirMode;
};

} // namespace fingerdance
