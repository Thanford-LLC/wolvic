#include "ComboWindowEngine.h"

#include <cmath>

namespace fingerdance {

std::atomic<bool> ComboWindowEngine::sFourDirMode{true};

ComboWindowEngine::ComboWindowEngine(EventCallback callback,
                                     ComboProgressCallback onProgress,
                                     PreviewCallback onPreview)
    : mCallback(std::move(callback))
    , mProgressCallback(std::move(onProgress))
    , mPreviewCallback(std::move(onPreview)) {}

// static
int ComboWindowEngine::NodeFromAxis(float x, float y) {
    const float mag = std::sqrt(x * x + y * y);
    if (mag < PREVIEW_MAGNITUDE) return 0;

    constexpr float PI = 3.14159265f;
    float deg = std::atan2(y, x) * (180.0f / PI);
    if (deg < 0) deg += 360.0f;

    if (IsFourDirMode()) {
        // 4×90° quadrants centred on cardinals (0/90/180/270).
        if (deg < 45.0f  || deg >= 315.0f) return 6; // right
        if (deg < 135.0f)                  return 2; // up
        if (deg < 225.0f)                  return 4; // left
        return 8;                                    // down
    }

    // 8×45° wedges centred on the cardinals + diagonals.
    if (deg < 22.5f  || deg >= 337.5f) return 6; // right
    if (deg < 67.5f)                   return 3; // upper-right
    if (deg < 112.5f)                  return 2; // up
    if (deg < 157.5f)                  return 1; // upper-left
    if (deg < 202.5f)                  return 4; // left
    if (deg < 247.5f)                  return 7; // lower-left
    if (deg < 292.5f)                  return 8; // down
    return 9;                                    // lower-right
}

bool ComboWindowEngine::Process(float axisX, float axisY,
                                bool thumbstickBtn, bool gripBtn,
                                int64_t timestampMs) {
    const bool gripJustReleased = !gripBtn && mPrevGrip;
    mPrevGrip          = gripBtn;
    mPrevThumbstickBtn = thumbstickBtn;

    if (!gripBtn) {
        if (gripJustReleased) {
            EmitIfNonEmpty();
            mLastActivatedNode = 0;
            mAtMax = false;
            mCenterDwellStartMs = 0;
            UpdatePreview(0);
        }
        return false;
    }

    const float mag  = std::sqrt(axisX * axisX + axisY * axisY);
    const int   zone = NodeFromAxis(axisX, axisY);

    // Activation logic — two triggers:
    //   E1: magnitude crosses upward into the "at max" region → activate
    //       the current zone. Fires once per push; re-arms only after mag
    //       drops below ACTIVATION_RELEASE (hysteresis).
    //   E2: while already at max, zone changes to a different non-zero
    //       zone → activate that zone (sliding-rim arcs).
    if (!mAtMax) {
        if (mag >= ACTIVATION_MAGNITUDE) {
            mAtMax = true;
            if (zone != 0 && zone != mLastActivatedNode) {
                AppendNode(zone);
                mLastActivatedNode = zone;
            } else if (zone != 0 && zone == mLastActivatedNode) {
                // Re-strike on the same zone (released enough to re-arm).
                AppendNode(zone);
                // mLastActivatedNode unchanged
            }
        }
    } else {
        if (mag < ACTIVATION_RELEASE) {
            mAtMax = false;
        } else if (zone != 0 && zone != mLastActivatedNode) {
            AppendNode(zone);
            mLastActivatedNode = zone;
        }
    }

    // Preview: wedge lights while in preview band AND not at max.
    // At max the wedge turns off — the confirmation dot outside the dial
    // carries the "activated" signal.
    int newPreview = 0;
    if (mag >= PREVIEW_MAGNITUDE && !mAtMax && zone != 0) {
        newPreview = zone;
    }
    UpdatePreview(newPreview);

    // Center-dwell auto-emit: if path has content and joystick returned to
    // center, start a timer; when CENTER_EMIT_DWELL_MS elapses, emit.
    if (mag < CENTER_THRESHOLD && mPathLength > 0) {
        if (mCenterDwellStartMs == 0) {
            mCenterDwellStartMs = timestampMs;
        } else if (timestampMs - mCenterDwellStartMs >= CENTER_EMIT_DWELL_MS) {
            EmitIfNonEmpty();
            mLastActivatedNode = 0;
            mCenterDwellStartMs = 0;
        }
    } else {
        mCenterDwellStartMs = 0;
    }

    return true;
}

void ComboWindowEngine::UpdatePreview(int newPreview) {
    if (newPreview != mPreviewNode) {
        mPreviewNode = newPreview;
        if (mPreviewCallback) mPreviewCallback(newPreview);
    }
}

void ComboWindowEngine::CancelSilent() {
    ResetPath();
    mPrevGrip = false;
    mPrevThumbstickBtn = false;
    mPreviewNode = 0;
    mAtMax = false;
    mLastActivatedNode = 0;
    mCenterDwellStartMs = 0;
}

void ComboWindowEngine::EmitIfNonEmpty() {
    if (mPathLength > 0 && mCallback) {
        ComboEvent event;
        event.path   = mPath;
        event.length = mPathLength;
        mCallback(event);
    }
    const bool hadPath = mPathLength > 0;
    ResetPath();
    if (hadPath && mProgressCallback) {
        ComboEvent empty;
        empty.path   = mPath;
        empty.length = 0;
        mProgressCallback(empty);
    }
}

void ComboWindowEngine::ResetPath() {
    mPath.fill(0);
    mPathLength = 0;
}

void ComboWindowEngine::AppendNode(int node) {
    if (mPathLength < MAX_PATH_LENGTH) {
        mPath[mPathLength++] = node;
        if (mProgressCallback) {
            ComboEvent progress;
            progress.path   = mPath;
            progress.length = mPathLength;
            mProgressCallback(progress);
        }
    }
}

} // namespace fingerdance
