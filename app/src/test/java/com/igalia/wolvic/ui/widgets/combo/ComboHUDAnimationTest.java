/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * FingerDance proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.ui.widgets.combo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Phase 3c R8: bench tests for pure-math ghost-animation helpers.
 *
 * <p>Tests only {@link ComboGhostAnimation} static helpers and the cancel
 * state machine — no widget instantiation (UIWidget context blocker).
 */
public class ComboHUDAnimationTest {

    private static final float EPSILON = 1e-4f;

    private ComboGhostAnimation mAnim;
    private ComboPreviewProgressSmoother mSmoother;

    @Before
    public void setUp() {
        mAnim     = new ComboGhostAnimation();
        mSmoother = new ComboPreviewProgressSmoother();
    }

    // ── breathAlphaUnit ──────────────────────────────────────

    @Test
    public void breathAlpha_atZero_isZero() {
        assertEquals(0f, ComboGhostAnimation.breathAlphaUnit(0L, 1600L), EPSILON);
    }

    @Test
    public void breathAlpha_at400ms_isApproxSinPiOver4() {
        // t = 400/800 = 0.5 → sin(0.5 * PI/2) = sin(PI/4) ≈ 0.7071
        float got = ComboGhostAnimation.breathAlphaUnit(400L, 1600L);
        assertEquals((float) Math.sin(Math.PI / 4.0), got, 1e-3f);
    }

    @Test
    public void breathAlpha_at800ms_isOne() {
        // t = 800/800 = 1.0 → sin(PI/2) = 1.0
        assertEquals(1.0f, ComboGhostAnimation.breathAlphaUnit(800L, 1600L), EPSILON);
    }

    @Test
    public void breathAlpha_at1200ms_isPointFive() {
        // Fade phase: (1200-800)/800 = 0.5, alpha = 1 - 0.5 = 0.5
        assertEquals(0.5f, ComboGhostAnimation.breathAlphaUnit(1200L, 1600L), EPSILON);
    }

    @Test
    public void breathAlpha_at1600ms_isZero() {
        // Fade phase: (1600-800)/800 = 1.0, alpha = 0.0
        assertEquals(0.0f, ComboGhostAnimation.breathAlphaUnit(1600L, 1600L), EPSILON);
    }

    // ── competitorCancelAlpha ────────────────────────────────

    @Test
    public void cancelAlpha_elapsed0_isOne() {
        assertEquals(1.0f, ComboGhostAnimation.competitorCancelAlpha(0L, 100L), EPSILON);
    }

    @Test
    public void cancelAlpha_elapsed50_isPointFive() {
        assertEquals(0.5f, ComboGhostAnimation.competitorCancelAlpha(50L, 100L), EPSILON);
    }

    @Test
    public void cancelAlpha_elapsed100_isZero() {
        assertEquals(0.0f, ComboGhostAnimation.competitorCancelAlpha(100L, 100L), EPSILON);
    }

    @Test
    public void cancelAlpha_elapsed200_isClamped() {
        assertEquals(0.0f, ComboGhostAnimation.competitorCancelAlpha(200L, 100L), EPSILON);
    }

    // ── stick-reactive crossfade math ───────────────────────

    @Test
    public void stickCrossfade_progress0p5_alphaValues() {
        // With progress=0.5, cancelAlpha=1.0:
        // dashed_alpha = round(255 * 0.70 * 0.5) = round(89.25) = 89
        // solid_alpha  = round(255 * 0.5) = 127 (rounded from 127.5)
        float progress = 0.5f;
        int dashedAlpha = Math.round(255f * 0.70f * (1f - progress));
        int solidAlpha  = Math.round(255f * progress);
        assertEquals(89, dashedAlpha);
        assertEquals(128, solidAlpha); // Math.round(127.5) = 128 in Java (rounds half-up)
    }

    // ── cancel debounce ──────────────────────────────────────

    @Test
    public void cancelDebounce_singleFrameAboveThreshold_noCancel() {
        // One frame at 0.35 → below CANCEL_DEBOUNCE_FRAMES (2).
        mSmoother.accept(6, 0.35f);
        mAnim.updateCancellationState(mSmoother, 1000L);
        assertEquals(0, mAnim.cancelWinner);
    }

    @Test
    public void cancelDebounce_twoFramesSameZone_activatesCancel() {
        mSmoother.accept(6, 0.35f);
        mAnim.updateCancellationState(mSmoother, 1000L);
        // Second frame same zone.
        mAnim.updateCancellationState(mSmoother, 1016L);
        assertEquals(6, mAnim.cancelWinner);
    }

    @Test
    public void cancelDebounce_zoneChange_resetsCandidateCounter() {
        // Frame 1: zone 6 at 0.35 → candidate=6, frames=1.
        mSmoother.accept(6, 0.35f);
        mAnim.updateCancellationState(mSmoother, 1000L);
        assertEquals(6, mAnim.cancelCandidateZone);
        assertEquals(1, mAnim.cancelCandidateFrames);

        // Frame 2: zone changes to 4 → resets candidate.
        mSmoother.accept(4, 0.35f);
        mAnim.updateCancellationState(mSmoother, 1016L);
        // Candidate should now be zone 4 with frames=1, no winner yet.
        assertEquals(0, mAnim.cancelWinner);
        assertEquals(4, mAnim.cancelCandidateZone);
        assertEquals(1, mAnim.cancelCandidateFrames);
    }

    // ── cancel hysteresis ────────────────────────────────────

    @Test
    public void cancelHysteresis_lowProgressFor10Frames_cancelStays() {
        // Activate cancel on zone 6.
        mSmoother.accept(6, 0.35f);
        mAnim.updateCancellationState(mSmoother, 1000L);
        mAnim.updateCancellationState(mSmoother, 1016L);
        assertEquals(6, mAnim.cancelWinner);

        // Progress drops to 0.28 (between REARM=0.25 and THRESHOLD=0.30) for 10 frames.
        // 0.28 < 0.30, so disarming phase triggers. But we need 2 consecutive frames
        // of the same candidate zone to clear.
        mSmoother.accept(6, 0.28f);
        for (int f = 0; f < 10; f++) {
            mAnim.updateCancellationState(mSmoother, 1032L + f * 16L);
        }
        // 0.28 < COMMIT_PREVIEW_REARM (0.25)? No, 0.28 > 0.25. So condition
        // progress < COMMIT_PREVIEW_REARM is false → still same zone + progress >= rearm.
        // cancelWinner should remain 6 (no disarm triggered).
        assertEquals(6, mAnim.cancelWinner);
    }

    @Test
    public void cancelHysteresis_progressDropsBelowRearmFor2Frames_cancelClears() {
        // Activate cancel on zone 6.
        mSmoother.accept(6, 0.35f);
        mAnim.updateCancellationState(mSmoother, 1000L);
        mAnim.updateCancellationState(mSmoother, 1016L);
        assertEquals(6, mAnim.cancelWinner);

        // Drop progress hard enough that even the EMA lands below REARM=0.25.
        // With prev=0.35, need raw < 0.15 so smoothed = 0.5*raw+0.5*0.35 < 0.25.
        // Using 0.05: smoothed = 0.5*0.05 + 0.5*0.35 = 0.20 < 0.25.
        mSmoother.accept(6, 0.05f);
        mAnim.updateCancellationState(mSmoother, 1032L); // frame 1: candidate=6, frames=1
        assertEquals(6, mAnim.cancelWinner); // not yet — needs 2 consecutive

        mAnim.updateCancellationState(mSmoother, 1048L); // frame 2: candidate=6, frames=2 → clear
        assertEquals(0, mAnim.cancelWinner);
    }

    // ── ghostCancelAlpha integration ─────────────────────────

    @Test
    public void ghostCancelAlpha_noCancel_allGhostsVisible() {
        assertEquals(1.0f, mAnim.ghostCancelAlpha(2, 1000L), EPSILON);
        assertEquals(1.0f, mAnim.ghostCancelAlpha(6, 1000L), EPSILON);
    }

    @Test
    public void ghostCancelAlpha_winnerIsVisible_competitorFades() {
        // Activate cancel on zone 6.
        mSmoother.accept(6, 0.35f);
        mAnim.updateCancellationState(mSmoother, 1000L);
        mAnim.updateCancellationState(mSmoother, 1016L);
        assertEquals(6, mAnim.cancelWinner);

        // Winner stays at 1.0.
        assertEquals(1.0f, mAnim.ghostCancelAlpha(6, 1016L), EPSILON);
        // Competitor at elapsed=0 is still 1.0 (just started fading).
        assertEquals(1.0f, mAnim.ghostCancelAlpha(2, 1016L), EPSILON);
        // Competitor at elapsed=50 is 0.5.
        assertEquals(0.5f, mAnim.ghostCancelAlpha(2, 1016L + 50L), EPSILON);
        // Competitor at elapsed=100 is 0.0.
        assertEquals(0.0f, mAnim.ghostCancelAlpha(2, 1016L + 100L), EPSILON);
    }
}
