#include "InputComboRecognizer.h"

namespace fingerdance {

InputComboRecognizer::InputComboRecognizer(ComboCallback callback,
                                           ProgressCallback onProgress,
                                           PreviewCb onPreview)
    : mEngine(std::move(callback), std::move(onProgress), std::move(onPreview)) {}

bool InputComboRecognizer::Process(float axisX, float axisY,
                                   bool thumbstickBtn, bool gripBtn,
                                   int64_t timestampMs) {
    // OpenXR thumbstick: Y positive = down (toward user when hand is neutral).
    // ComboWindowEngine: Y positive = up (node 2).
    // Negate Y here so the engine grid matches the user's mental model.
    return mEngine.Process(axisX, -axisY, thumbstickBtn, gripBtn, timestampMs);
}

void InputComboRecognizer::CancelSilent() {
    mEngine.CancelSilent();
}

void InputComboRecognizer::SetPreviewProgressCallback(PreviewProgressCb cb) {
    mEngine.SetPreviewProgressCallback(std::move(cb));
}

} // namespace fingerdance
