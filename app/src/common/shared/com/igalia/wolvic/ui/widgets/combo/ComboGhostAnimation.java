/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.ui.widgets.combo;

/**
 * Phase 3c: pure-math helpers for the ghost-layer animations in ComboHUDWidget.
 *
 * <p>Extracted so math can be unit-tested without a WidgetManagerDelegate context
 * dependency (UIWidget blocker). All methods are package-private static and
 * allocation-free.
 *
 * <h3>Animation schedule per ghost (period = 1600 ms)</h3>
 * <ul>
 *   <li>Extension (0–800 ms): ease-out via {@code sin(t * PI / 2)}, t in [0,1].</li>
 *   <li>Fade (800–1600 ms): linear from 1 → 0.</li>
 *   <li>Phase offset per ghost: {@code (1600 / numGhosts) * ghostIndex}.</li>
 * </ul>
 *
 * <h3>Cancellation state</h3>
 * Holds the mutable state for the commit-cancellation debounce. A single
 * instance lives on ComboHUDWidget and is updated once per drawGhostLayer call.
 */
public final class ComboGhostAnimation {

    public static final long PERIOD_MS = 1600L;
    public static final long HALF_PERIOD_MS = 800L;

    // Commit-cancellation constants.
    public static final float COMMIT_PREVIEW_THRESHOLD = 0.30f;
    public static final float COMMIT_PREVIEW_REARM     = 0.25f;
    public static final long  COMPETITOR_FADE_MS       = 100L;
    public static final int   CANCEL_DEBOUNCE_FRAMES   = 2;

    // Mutable cancel-state fields. Read/written on the UI thread only
    // (same thread as onDraw). No synchronisation required.
    public int  cancelWinner         = 0;   // zone that triggered cancellation; 0 = none
    public int  cancelCandidateZone  = 0;
    public int  cancelCandidateFrames= 0;
    public long cancelStartedAtMs    = 0L;

    /**
     * Returns the alpha unit [0, 1] for a breathing ghost at {@code localPhaseMs}
     * within the period. Does NOT multiply by any final alpha — callers scale.
     *
     * <ul>
     *   <li>Extension (0–800 ms): ease-out {@code sin(t * PI/2)} where t = phaseMs/800.</li>
     *   <li>Fade (800–1600 ms): linear {@code 1 – (phaseMs-800)/800}.</li>
     * </ul>
     */
    public static float breathAlphaUnit(long localPhaseMs, long periodMs) {
        long halfPeriod = periodMs / 2;
        if (localPhaseMs < halfPeriod) {
            float t = (float) localPhaseMs / halfPeriod;
            return (float) Math.sin(t * Math.PI / 2.0);
        } else {
            float t = (float) (localPhaseMs - halfPeriod) / halfPeriod;
            return 1.0f - t;
        }
    }

    /**
     * Returns the competitor cancel-fade alpha [0, 1] given elapsed milliseconds
     * since cancel was activated. Clamped so it never goes below 0.
     *
     * @param elapsed milliseconds since {@code cancelStartedAtMs}
     * @param fadeMs  total fade duration (use {@link #COMPETITOR_FADE_MS})
     */
    public static float competitorCancelAlpha(long elapsed, long fadeMs) {
        if (elapsed <= 0L) return 1.0f;
        if (elapsed >= fadeMs) return 0.0f;
        return 1.0f - (elapsed / (float) fadeMs);
    }

    /**
     * Update the cancellation state machine. Called once per frame at the top
     * of drawGhostLayer. Reads from {@code smoother}, writes into this object's
     * mutable fields.
     *
     * @param smoother the preview smoother providing current zone + progress
     * @param nowMs    current frame time in milliseconds
     */
    public void updateCancellationState(ComboPreviewProgressSmoother smoother, long nowMs) {
        int  aimedZone = smoother.zone();
        float progress = smoother.progress();

        if (cancelWinner == 0) {
            // Arming phase
            if (aimedZone != 0 && progress >= COMMIT_PREVIEW_THRESHOLD) {
                if (aimedZone == cancelCandidateZone) {
                    cancelCandidateFrames++;
                    if (cancelCandidateFrames >= CANCEL_DEBOUNCE_FRAMES) {
                        cancelWinner          = aimedZone;
                        cancelStartedAtMs     = nowMs;
                        cancelCandidateZone   = 0;
                        cancelCandidateFrames = 0;
                    }
                } else {
                    cancelCandidateZone   = aimedZone;
                    cancelCandidateFrames = 1;
                }
            } else {
                cancelCandidateZone   = 0;
                cancelCandidateFrames = 0;
            }
        } else {
            // Disarming phase (hysteresis)
            if (aimedZone != cancelWinner || progress < COMMIT_PREVIEW_REARM) {
                if (aimedZone == cancelCandidateZone) {
                    cancelCandidateFrames++;
                    if (cancelCandidateFrames >= CANCEL_DEBOUNCE_FRAMES) {
                        cancelWinner          = 0;
                        cancelCandidateZone   = 0;
                        cancelCandidateFrames = 0;
                        cancelStartedAtMs     = 0L;
                    }
                } else {
                    cancelCandidateZone   = aimedZone;
                    cancelCandidateFrames = 1;
                }
            } else {
                cancelCandidateZone   = 0;
                cancelCandidateFrames = 0;
            }
        }
    }

    /**
     * Returns the cancel-fade alpha for a ghost at {@code ghostZone}.
     * Winner and non-cancel state return 1.0; competitors fade over
     * {@link #COMPETITOR_FADE_MS}.
     */
    public float ghostCancelAlpha(int ghostZone, long nowMs) {
        if (cancelWinner == 0)            return 1.0f;
        if (ghostZone == cancelWinner)    return 1.0f;
        long elapsed = nowMs - cancelStartedAtMs;
        return competitorCancelAlpha(elapsed, COMPETITOR_FADE_MS);
    }

    /** Reset all cancel state (e.g. on grip release or path reset). */
    public void resetCancel() {
        cancelWinner          = 0;
        cancelCandidateZone   = 0;
        cancelCandidateFrames = 0;
        cancelStartedAtMs     = 0L;
    }
}
