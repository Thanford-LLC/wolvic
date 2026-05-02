#pragma once

#include "ComboWindowEngine.h"

namespace glyphew {

// OpenXR adapter for ComboWindowEngine.
//
// Responsibilities:
//   - Converts OpenXR axis convention (Y-down) to engine convention (Y-up).
//   - Owns the ComboWindowEngine and forwards its callbacks to the caller.
//
// Thread-safety: same as ComboWindowEngine — single-threaded, call from the
// OpenXR update thread only.
class InputComboRecognizer {
public:
    using ComboCallback           = EventCallback;
    using ProgressCallback        = ComboProgressCallback;
    using PreviewCb               = PreviewCallback;
    using PreviewProgressCb       = PreviewProgressCallback;
    using LongPressCb             = LongPressCallback;

    // callback   — fired when a combo completes.
    // onProgress — fired after each node is activated; used by the HUD. Optional.
    // onPreview  — fired every frame with current preview wedge. Optional.
    explicit InputComboRecognizer(ComboCallback callback,
                                  ProgressCallback onProgress = nullptr,
                                  PreviewCb onPreview = nullptr);

    // Call every frame from OpenXRInputSource::Update().
    //   axisX, axisY  — raw thumbstick axes from OpenXR (Y positive = down in
    //                   OpenXR convention; this class negates Y before forwarding).
    //   thumbstickBtn — thumbstick click button.
    //   gripBtn       — squeeze/grip button; gates combo mode.
    //   timestampMs   — monotonic timestamp in milliseconds (convert from XrTime).
    //
    // Returns true when the input is consumed (grip is held) — the caller should
    // suppress SetScrolledDelta for this frame.
    bool Process(float axisX, float axisY,
                 bool thumbstickBtn, bool gripBtn,
                 int64_t timestampMs);

    // Silent cancel — call when an OS overlay steals focus.
    void CancelSilent();

    // True while no combo nodes have been committed. OpenXR reads this to
    // decide whether a thumbstick click should toggle the HUD (idle) or
    // cancel the in-progress combo (mid-path).
    bool IsPathEmpty() const;

    // Phase 3b: forward the continuous preview-progress callback to the
    // underlying engine. Kept as a setter (not a ctor argument) so the
    // existing constructor signature stays stable.
    void SetPreviewProgressCallback(PreviewProgressCb cb);

    // Phase 6: forward the long-press thumbstick callback. Fires once per
    // qualifying hold (grip-OFF, thumbstick held >= LONG_PRESS_MS). Used to
    // open Combos Settings from anywhere.
    void SetLongPressCallback(LongPressCb cb);

private:
    ComboWindowEngine mEngine;
};

} // namespace glyphew
