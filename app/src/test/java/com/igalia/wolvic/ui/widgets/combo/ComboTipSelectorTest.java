/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * FingerDance proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.ui.widgets.combo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.igalia.wolvic.input.ComboDispatcher;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class ComboTipSelectorTest {

    /** Handcrafted representative pool covering each difficulty bucket. */
    private static List<ComboTipBuilder.TipCandidate> buildFakePool() {
        List<ComboTipBuilder.TipCandidate> pool = new ArrayList<>();
        // 3 MUST_KNOW (meta)
        pool.add(meta("meta:101", 101));
        pool.add(meta("meta:102", 102));
        pool.add(meta("meta:103", 103));
        // 8 EASY (singles + cardinal-doubles)
        pool.add(bind(new int[]{2}));
        pool.add(bind(new int[]{4}));
        pool.add(bind(new int[]{6}));
        pool.add(bind(new int[]{8}));
        pool.add(bind(new int[]{2, 2}));
        pool.add(bind(new int[]{4, 4}));
        pool.add(bind(new int[]{6, 6}));
        pool.add(bind(new int[]{8, 8}));
        // 6 MIDDLE (cross cardinals, triples, single diagonals)
        pool.add(bind(new int[]{2, 8}));
        pool.add(bind(new int[]{2, 4}));
        pool.add(bind(new int[]{4, 6}));
        pool.add(bind(new int[]{2, 2, 2}));
        pool.add(bind(new int[]{1}));
        pool.add(bind(new int[]{3}));
        // 5 HARD (diagonals mid-path / diagonal-doubles / 4+ node)
        pool.add(bind(new int[]{3, 3}));
        pool.add(bind(new int[]{2, 3, 6}));
        pool.add(bind(new int[]{2, 1, 4}));
        pool.add(bind(new int[]{2, 1, 4, 7, 8}));
        pool.add(bind(new int[]{2, 3, 6, 9, 8}));
        return pool;
    }

    private static ComboTipBuilder.TipCandidate meta(String id, int res) {
        return new ComboTipBuilder.TipCandidate(id, null,
                ComboDispatcher.A_NONE, res, ComboTipBuilder.Difficulty.MUST_KNOW);
    }

    private static ComboTipBuilder.TipCandidate bind(int[] path) {
        return new ComboTipBuilder.TipCandidate(
                "bind:" + Arrays.toString(path), path,
                /*actionInt=*/100, /*metaRes=*/0,
                ComboTipBuilder.classify(path));
    }

    // ---- Warm-up --------------------------------------------------------

    @Test
    public void warmUpReturnsUniqueMustKnowTips() {
        ComboTipSelector sel = new ComboTipSelector(new Random(42L));
        List<ComboTipBuilder.TipCandidate> pool = buildFakePool();

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            ComboTipBuilder.TipCandidate t = sel.pickNext(pool, 0L);
            assertNotNull(t);
            assertEquals("warm-up pick #" + i + " must be MUST_KNOW",
                    ComboTipBuilder.Difficulty.MUST_KNOW, t.difficulty);
            assertTrue("no repeats in warm-up", seen.add(t.stableId));
        }
        assertEquals(3, seen.size());
    }

    // ---- Steady-state distribution -------------------------------------

    @Test
    public void steadyStateDistributionWithinFivePercent() {
        runDistributionCheck(42L);
        runDistributionCheck(1337L);
        runDistributionCheck(2026L);
    }

    private void runDistributionCheck(long seed) {
        ComboTipSelector sel = new ComboTipSelector(new Random(seed));
        List<ComboTipBuilder.TipCandidate> pool = buildFakePool();

        // Burn warm-up.
        for (int i = 0; i < 3; i++) sel.pickNext(pool, 0L);

        EnumMap<ComboTipBuilder.Difficulty, Integer> counts = new EnumMap<>(ComboTipBuilder.Difficulty.class);
        for (ComboTipBuilder.Difficulty d : ComboTipBuilder.Difficulty.values()) counts.put(d, 0);

        final int N = 1000;
        for (int i = 0; i < N; i++) {
            ComboTipBuilder.TipCandidate t = sel.pickNext(pool, 0L);
            assertNotNull(t);
            counts.put(t.difficulty, counts.get(t.difficulty) + 1);
        }

        checkWithinTolerance(seed, "MUST_KNOW", counts.get(ComboTipBuilder.Difficulty.MUST_KNOW), 0.25, 0.05, N);
        checkWithinTolerance(seed, "EASY",      counts.get(ComboTipBuilder.Difficulty.EASY),      0.40, 0.05, N);
        checkWithinTolerance(seed, "MIDDLE",    counts.get(ComboTipBuilder.Difficulty.MIDDLE),    0.25, 0.05, N);
        checkWithinTolerance(seed, "HARD",      counts.get(ComboTipBuilder.Difficulty.HARD),      0.10, 0.05, N);
    }

    private static void checkWithinTolerance(long seed, String name, int count,
                                              double expected, double tolerance, int total) {
        double actual = count / (double) total;
        assertTrue("seed=" + seed + " " + name + " expected ~" + expected
                        + " got " + actual + " (count " + count + " / " + total + ")",
                Math.abs(actual - expected) <= tolerance);
    }

    // ---- No-repeat ------------------------------------------------------

    @Test
    public void noImmediateRepeatOverFiveHundredCalls() {
        ComboTipSelector sel = new ComboTipSelector(new Random(42L));
        List<ComboTipBuilder.TipCandidate> pool = buildFakePool();

        String prev = null;
        for (int i = 0; i < 500; i++) {
            ComboTipBuilder.TipCandidate t = sel.pickNext(pool, 0L);
            assertNotNull(t);
            if (prev != null) {
                assertFalse("immediate repeat at iter " + i + ": " + t.stableId,
                        t.stableId.equals(prev));
            }
            prev = t.stableId;
        }
    }

    // ---- Recency bias --------------------------------------------------

    @Test
    public void recencyBoostSurfacesStampedTipWithinTwoPicks() {
        for (long seed = 0; seed < 100; seed++) {
            ComboTipSelector sel = new ComboTipSelector(new Random(seed));
            List<ComboTipBuilder.TipCandidate> pool = buildFakePool();

            // Burn warm-up so recency path is in play.
            for (int i = 0; i < 3; i++) sel.pickNext(pool, 0L);

            String target = "bind:[2, 2]";
            sel.notifyBindingChanged(target, 1_000L);

            ComboTipBuilder.TipCandidate p1 = sel.pickNext(pool, 1_000L);
            ComboTipBuilder.TipCandidate p2 = sel.pickNext(pool, 1_000L);

            boolean surfaced = target.equals(p1.stableId) || target.equals(p2.stableId);
            assertTrue("seed=" + seed + ": recency-boosted tip should surface within 2 picks "
                            + "(p1=" + p1.stableId + " p2=" + p2.stableId + ")",
                    surfaced);
        }
    }

    // ---- Reset / notifyBindingChanged with null -----------------------

    @Test
    public void resetClearsAllState() {
        ComboTipSelector sel = new ComboTipSelector(new Random(42L));
        List<ComboTipBuilder.TipCandidate> pool = buildFakePool();
        for (int i = 0; i < 5; i++) sel.pickNext(pool, 0L);
        assertTrue(sel.cyclesShownForTest() > 0);
        sel.reset();
        assertEquals(0, sel.cyclesShownForTest());

        // Warm-up should restart after reset.
        ComboTipBuilder.TipCandidate t = sel.pickNext(pool, 0L);
        assertEquals(ComboTipBuilder.Difficulty.MUST_KNOW, t.difficulty);
    }

    @Test
    public void notifyBindingChangedNullIsNoop() {
        ComboTipSelector sel = new ComboTipSelector(new Random(42L));
        // Should not throw.
        sel.notifyBindingChanged(null, 1_000L);
    }

    @Test
    public void emptyPoolReturnsNull() {
        ComboTipSelector sel = new ComboTipSelector(new Random(42L));
        assertEquals(null, sel.pickNext(new ArrayList<>(), 0L));
    }

    // ---- Identity / equals sanity on TipCandidate ----------------------

    @Test
    public void tipCandidateEqualsBasedOnStableId() {
        ComboTipBuilder.TipCandidate a = meta("meta:101", 101);
        ComboTipBuilder.TipCandidate b = meta("meta:101", 999);  // same id, diff res
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        ComboTipBuilder.TipCandidate c = meta("meta:102", 101);
        assertNotSame(a, c);
        assertFalse(a.equals(c));
    }
}
