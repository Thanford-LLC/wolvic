/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.ui.widgets.combo;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

/**
 * Phase 3b coverage for the continuous preview-progress smoothing math
 * consumed by {@link com.igalia.wolvic.ui.widgets.ComboHUDWidget#updatePreviewProgress(int, float)}.
 *
 * <p>The widget simply delegates to {@link ComboPreviewProgressSmoother}, so
 * exercising the smoother directly covers the math without dragging the
 * widget's {@code WidgetManagerDelegate} context requirement into the
 * Robolectric setup. Phase 3c's stick-reactive fill consumes the smoothed
 * value; these tests lock the contract it depends on: EMA within same zone,
 * snap across zone boundaries, raw-progress tracking.
 */
public class ComboPreviewProgressSmootherTest {

    private static final float EPSILON = 1e-5f;

    private ComboPreviewProgressSmoother mSmoother;

    @Before
    public void setUp() {
        mSmoother = new ComboPreviewProgressSmoother();
    }

    @Test
    public void initialState_isIdle() {
        assertEquals(0, mSmoother.zone());
        assertEquals(0.0f, mSmoother.progress(), EPSILON);
        assertEquals(0.0f, mSmoother.progressRaw(), EPSILON);
    }

    @Test
    public void firstNonZeroZone_snapsProgressNoSmoothing() {
        // First sample for a zone should land exactly (no EMA on entry).
        mSmoother.accept(2, 0.4f);
        assertEquals(2, mSmoother.zone());
        assertEquals(0.4f, mSmoother.progress(), EPSILON);
        assertEquals(0.4f, mSmoother.progressRaw(), EPSILON);
    }

    @Test
    public void sameZone_appliesEmaSmoothing() {
        mSmoother.accept(2, 0.4f);
        mSmoother.accept(2, 0.6f);
        // alpha=0.5 → 0.5*0.6 + 0.5*0.4 = 0.5
        assertEquals(2, mSmoother.zone());
        assertEquals(0.5f, mSmoother.progress(), EPSILON);
    }

    @Test
    public void chainedSameZoneSamples_smoothTowardTarget() {
        mSmoother.accept(2, 0.4f);
        mSmoother.accept(2, 0.6f); // → 0.5
        mSmoother.accept(2, 0.8f); // → 0.5*0.8 + 0.5*0.5 = 0.65
        assertEquals(0.65f, mSmoother.progress(), EPSILON);
    }

    @Test
    public void zoneChange_snapsAcrossBoundary() {
        mSmoother.accept(2, 0.4f);
        mSmoother.accept(2, 0.6f); // smoothed to 0.5
        mSmoother.accept(4, 0.3f); // zone flipped → snap
        assertEquals(4, mSmoother.zone());
        assertEquals(0.3f, mSmoother.progress(), EPSILON);
    }

    @Test
    public void returnToIdle_snapsToZero() {
        mSmoother.accept(2, 0.8f);
        mSmoother.accept(0, 0.0f);
        assertEquals(0, mSmoother.zone());
        assertEquals(0.0f, mSmoother.progress(), EPSILON);
        assertEquals(0.0f, mSmoother.progressRaw(), EPSILON);
    }

    @Test
    public void rawProgress_tracksMostRecentInputRegardlessOfSmoothing() {
        mSmoother.accept(2, 0.4f);
        assertEquals(0.4f, mSmoother.progressRaw(), EPSILON);
        mSmoother.accept(2, 0.9f);
        // Smoothed sits at 0.65, but raw tracks the latest sample.
        assertEquals(0.9f, mSmoother.progressRaw(), EPSILON);
        assertEquals(0.65f, mSmoother.progress(), EPSILON);
    }
}
