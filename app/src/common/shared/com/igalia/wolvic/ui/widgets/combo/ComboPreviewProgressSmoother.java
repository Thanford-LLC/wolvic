/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.ui.widgets.combo;

/**
 * Phase 3b: EMA smoother for the continuous preview-progress signal that the
 * native combo engine sends over JNI.
 *
 * <p>The native engine throttles emits so we only see samples when the user's
 * lean crosses PREVIEW_PROGRESS_EPSILON or the zone flips. That's great for
 * JNI cost but creates a stair-step value stream; to feed a smooth
 * stick-reactive fill animation (Phase 3c) we apply an EMA within the same
 * zone. Across zone boundaries we snap instead of smoothing — a smoothed
 * mid-point would paint an intermediate wedge that nothing represents.
 *
 * <p>Extracted from {@code ComboHUDWidget} purely so the math can be covered
 * by Robolectric without dragging in the widget's WidgetManagerDelegate
 * context dependency.
 *
 * <p>Thread model: mutated and read from the same thread (the HUD's draw /
 * main thread). No synchronisation.
 */
public final class ComboPreviewProgressSmoother {

    /** Exponential-moving-average coefficient for same-zone samples. */
    public static final float ALPHA = 0.5f;

    private int mZone = 0;            // 0 = idle / below preview threshold
    private float mProgress = 0.0f;   // EMA-smoothed
    private float mProgressRaw = 0.0f; // last raw sample

    public ComboPreviewProgressSmoother() {}

    /**
     * Feed a new (zone, raw-progress) sample from the native engine.
     * Zone-change snaps the smoothed value; same-zone applies the EMA.
     */
    public void accept(int zone, float progressRaw) {
        if (zone != mZone) {
            mZone = zone;
            mProgress = progressRaw;
        } else {
            mProgress = ALPHA * progressRaw + (1.0f - ALPHA) * mProgress;
        }
        mProgressRaw = progressRaw;
    }

    public int   zone()        { return mZone; }
    public float progress()    { return mProgress; }
    public float progressRaw() { return mProgressRaw; }
}
