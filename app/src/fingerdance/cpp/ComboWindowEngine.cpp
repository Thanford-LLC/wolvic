#include "ComboWindowEngine.h"

#include <cmath>

namespace fingerdance {

namespace {
// Phase 3b: local helper (no dependency on <algorithm>) so the hot path stays
// pure arithmetic with no allocation.
inline float ClampUnit(float v) {
    if (v < 0.0f) return 0.0f;
    if (v > 1.0f) return 1.0f;
    return v;
}
} // namespace

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
    const bool gripJustReleased       = !gripBtn && mPrevGrip;
    const bool thumbstickJustPressed  =  thumbstickBtn && !mPrevThumbstickBtn;
    mPrevGrip          = gripBtn;
    mPrevThumbstickBtn = thumbstickBtn;

    // Phase 6: long-press accumulator is grip-OFF only. If grip is held, zero
    // the tracker so a prior incomplete hold can't misfire after grip
    // releases. This also covers the case where grip is grabbed mid-hold.
    if (gripBtn) {
        mThumbstickDownTime = 0;
        mLongPressFired     = false;
    }

    // FingerDance (CLAUDE.md §5.4): joystick click mid-path = silent cancel.
    // No fire, no toast. CancelSilent() already resets state and fires an
    // empty progress event so the HUD drops its path. We re-assert
    // mPrev{Grip,ThumbstickBtn} after the reset because CancelSilent zeroes
    // them for the grip-release path, and we still need edge detection on
    // the next tick.
    if (gripBtn && thumbstickJustPressed && mPathLength > 0) {
        CancelSilent();
        mPrevGrip          = gripBtn;
        mPrevThumbstickBtn = thumbstickBtn;
        return true;
    }

    if (!gripBtn) {
        if (gripJustReleased) {
            EmitIfNonEmpty();
            mLastActivatedNode = 0;
            mAtMax = false;
            mCenterDwellStartMs = 0;
            mArmingZone = 0;
            mArmingStartMs = 0;
            UpdatePreview(0);
            // Phase 3b: force-emit a single (0, 0.0f) so the HUD snaps back
            // to idle brightness, then reset the sentinel so the next grip
            // cycle starts clean.
            MaybeEmitPreviewProgress(0, 0.0f);
            mLastPreviewProgressValue = -1.0f;
            mLastPreviewProgressZone = 0;
        }

        // Phase 6: long-press thumbstick (grip-OFF) opens Combos Settings.
        // Grip-OFF gate is why this can't collide with the silent-cancel
        // branch above (grip-HELD). The edge starts the timer; every
        // subsequent frame we re-check elapsed and fire once at threshold.
        // mLongPressFired latches until release so we fire exactly once.
        if (thumbstickBtn) {
            if (thumbstickJustPressed) {
                mThumbstickDownTime = timestampMs;
                mLongPressFired     = false;
            } else if (!mLongPressFired
                       && mThumbstickDownTime != 0
                       && (timestampMs - mThumbstickDownTime) >= LONG_PRESS_MS) {
                mLongPressFired = true;
                if (mLongPressCallback) mLongPressCallback();
            }
        } else {
            mThumbstickDownTime = 0;
            mLongPressFired     = false;
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
            mArmingZone = 0;
            mArmingStartMs = 0;
        } else if (zone != 0 && zone != mLastActivatedNode) {
            // Round-10: sliding-rim arming. The new zone must hold for
            // ARM_DWELL_MS before committing so the HUD can paint a
            // stick-reactive fill ramp on the candidate ghost. Prior
            // behavior committed instantly — no preview phase in
            // sliding-rim mode.
            if (zone != mArmingZone) {
                mArmingZone = zone;
                mArmingStartMs = timestampMs;
            } else if (timestampMs - mArmingStartMs >= ARM_DWELL_MS) {
                AppendNode(zone);
                mLastActivatedNode = zone;
                mArmingZone = 0;
                mArmingStartMs = 0;
            }
        } else {
            // Back on the last-committed zone → cancel any arming.
            mArmingZone = 0;
            mArmingStartMs = 0;
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

    // Phase 3b: continuous preview-progress signal for the HUD. Maps the
    // band [PREVIEW_MAGNITUDE, ACTIVATION_MAGNITUDE] onto [0, 1]. Throttled
    // by MaybeEmitPreviewProgress so we only cross JNI on meaningful change
    // (|Δ| >= PREVIEW_PROGRESS_EPSILON or zone-change snap).
    float progress = 0.0f;
    int   progressZone = 0;
    if (mAtMax && mArmingZone != 0) {
        // Round-10: sliding-rim arming phase. Map elapsed dwell time onto
        // [0, 1] so the HUD paints the candidate ghost's stick-reactive
        // fill ramping up to commit. The commit itself happens when we
        // cross ARM_DWELL_MS in the activation block above.
        const int64_t elapsed = timestampMs - mArmingStartMs;
        progress = ClampUnit((float)elapsed / (float)ARM_DWELL_MS);
        progressZone = mArmingZone;
    } else if (mag >= PREVIEW_MAGNITUDE && zone != 0 && !mAtMax) {
        progress = ClampUnit((mag - PREVIEW_MAGNITUDE)
                             / (ACTIVATION_MAGNITUDE - PREVIEW_MAGNITUDE));
        progressZone = zone;
    }
    MaybeEmitPreviewProgress(progressZone, progress);

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
    const bool hadPath = mPathLength > 0;
    ResetPath();
    mPrevGrip = false;
    mPrevThumbstickBtn = false;
    mPreviewNode = 0;
    mAtMax = false;
    mLastActivatedNode = 0;
    mCenterDwellStartMs = 0;
    mArmingZone = 0;
    mArmingStartMs = 0;
    // Fire an empty progress event so the HUD clears its path buffer —
    // otherwise a thumbstick-click cancel leaves the committed nodes painted
    // on the dial until the user releases grip. Mirror EmitIfNonEmpty()'s
    // pattern so only callers that actually dropped nodes trigger the
    // redraw.
    if (hadPath && mProgressCallback) {
        ComboEvent empty;
        empty.path   = mPath;
        empty.length = 0;
        mProgressCallback(empty);
    }
    // Phase 3b: cancellation means the HUD should drop back to idle. Force a
    // single (0, 0.0f) emit if we weren't already there, then reset the
    // throttle sentinel so the next grip cycle re-syncs from scratch.
    MaybeEmitPreviewProgress(0, 0.0f);
    mLastPreviewProgressValue = -1.0f;
    mLastPreviewProgressZone = 0;
}

void ComboWindowEngine::MaybeEmitPreviewProgress(int zone, float progress) {
    if (!mPreviewProgressCallback) return;
    const bool zoneChanged = (zone != mLastPreviewProgressZone);
    const float delta = progress - mLastPreviewProgressValue;
    const float absDelta = delta < 0.0f ? -delta : delta;
    const bool crossedEpsilon = absDelta >= PREVIEW_PROGRESS_EPSILON;
    if (!zoneChanged && !crossedEpsilon) return;
    mPreviewProgressCallback(zone, progress);
    mLastPreviewProgressValue = progress;
    mLastPreviewProgressZone = zone;
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
