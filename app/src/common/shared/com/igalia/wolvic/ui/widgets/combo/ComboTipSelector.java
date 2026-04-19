/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * FingerDance proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.ui.widgets.combo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Stateful selector that cycles binding + meta tips through the HUD.
 *
 * <p>Three phases:
 * <ol>
 *   <li><b>Warm-up</b> — first {@link #WARM_UP_COUNT} picks return unique
 *       MUST_KNOW (meta) tips.
 *   <li><b>Recency bias</b> — after {@link #notifyBindingChanged}, the
 *       boosted tip is forced in within two picks (and always within the
 *       30s window).
 *   <li><b>Steady state</b> — weighted-random by difficulty
 *       (25/40/25/10 must_know/easy/middle/hard), with a 3-tip no-repeat
 *       ring buffer.
 * </ol>
 */
public final class ComboTipSelector {

    private static final int WARM_UP_COUNT = 3;
    private static final int RING_BUFFER_SIZE = 3;
    private static final long RECENCY_WINDOW_MS = 30_000L;

    // Weights — must sum to 100.
    private static final int WEIGHT_MUST_KNOW = 25;
    private static final int WEIGHT_EASY      = 40;
    private static final int WEIGHT_MIDDLE    = 25;
    private static final int WEIGHT_HARD      = 10;

    private final Random mRandom;
    private int mCyclesShown = 0;
    private final ArrayDeque<String> mLastShown = new ArrayDeque<>(RING_BUFFER_SIZE);
    @Nullable private String mRecencyBoostedStableId = null;
    private long mRecencyStampedAtMillis = 0L;
    private int mPicksSinceRecencyStamp = 0;

    // Reusable scratch — avoids per-call allocation during steady-state pickNext.
    private final ArrayList<ComboTipBuilder.TipCandidate> mScratchBucket = new ArrayList<>(32);
    private final ArrayList<ComboTipBuilder.TipCandidate> mScratchFiltered = new ArrayList<>(32);

    public ComboTipSelector() {
        this(new Random());
    }

    /** Deterministic constructor — used by unit tests. */
    @VisibleForTesting
    public ComboTipSelector(@NonNull Random random) {
        mRandom = random;
    }

    public void reset() {
        mCyclesShown = 0;
        mLastShown.clear();
        mRecencyBoostedStableId = null;
        mRecencyStampedAtMillis = 0L;
        mPicksSinceRecencyStamp = 0;
    }

    /**
     * Stamp a binding as freshly changed so the next 1-2 picks surface its
     * tip. Called from the HUD on {@code BindingsListener#onBindingsChanged}
     * with the path-stableId from {@code ComboDispatcher#getLastChangedPathId}.
     */
    public void notifyBindingChanged(@Nullable String stableId, long nowMillis) {
        if (stableId == null) return;
        mRecencyBoostedStableId = stableId;
        mRecencyStampedAtMillis = nowMillis;
        mPicksSinceRecencyStamp = 0;
    }

    /**
     * Pick the next tip for the HUD. Never returns null if {@code pool} has
     * at least one element; the bucket fallbacks always land on a candidate.
     */
    @Nullable
    public ComboTipBuilder.TipCandidate pickNext(
            @NonNull List<ComboTipBuilder.TipCandidate> pool,
            long nowMillis) {
        if (pool.isEmpty()) return null;

        // 1) Warm-up.
        if (mCyclesShown < WARM_UP_COUNT) {
            ComboTipBuilder.TipCandidate warm = pickWarmUp(pool);
            if (warm != null) {
                recordPick(warm);
                return warm;
            }
            // Fall through if pool has no remaining MUST_KNOW tips.
        }

        // 2) Recency bias.
        ComboTipBuilder.TipCandidate recency = pickRecencyBoosted(pool, nowMillis);
        if (recency != null) {
            recordPick(recency);
            mPicksSinceRecencyStamp++;
            if (mPicksSinceRecencyStamp >= 2) {
                mRecencyBoostedStableId = null;
            }
            return recency;
        }
        // Window may have elapsed — clear so future calls skip the recency check.
        if (mRecencyBoostedStableId != null
                && (nowMillis - mRecencyStampedAtMillis) > RECENCY_WINDOW_MS) {
            mRecencyBoostedStableId = null;
        }

        // 3) Steady state.
        ComboTipBuilder.TipCandidate steady = pickWeighted(pool);
        if (steady != null) {
            recordPick(steady);
        }
        return steady;
    }

    private void recordPick(ComboTipBuilder.TipCandidate chosen) {
        while (mLastShown.size() >= RING_BUFFER_SIZE) {
            mLastShown.pollFirst();
        }
        mLastShown.offerLast(chosen.stableId);
        mCyclesShown++;
    }

    @Nullable
    private ComboTipBuilder.TipCandidate pickWarmUp(
            List<ComboTipBuilder.TipCandidate> pool) {
        mScratchBucket.clear();
        for (int i = 0; i < pool.size(); i++) {
            ComboTipBuilder.TipCandidate c = pool.get(i);
            if (c.difficulty != ComboTipBuilder.Difficulty.MUST_KNOW) continue;
            if (containsStableId(mLastShown, c.stableId)) continue;
            mScratchBucket.add(c);
        }
        if (mScratchBucket.isEmpty()) return null;
        return mScratchBucket.get(mRandom.nextInt(mScratchBucket.size()));
    }

    @Nullable
    private ComboTipBuilder.TipCandidate pickRecencyBoosted(
            List<ComboTipBuilder.TipCandidate> pool, long nowMillis) {
        if (mRecencyBoostedStableId == null) return null;
        if ((nowMillis - mRecencyStampedAtMillis) > RECENCY_WINDOW_MS) return null;
        if (mPicksSinceRecencyStamp >= 2) return null;

        ComboTipBuilder.TipCandidate hit = null;
        for (int i = 0; i < pool.size(); i++) {
            ComboTipBuilder.TipCandidate c = pool.get(i);
            if (mRecencyBoostedStableId.equals(c.stableId)) {
                hit = c;
                break;
            }
        }
        if (hit == null) return null;

        // Don't force-repeat on the immediate tail of the ring buffer — lets
        // the steady-state path cover back-to-back stamps gracefully.
        String tail = mLastShown.peekLast();
        if (tail != null && tail.equals(hit.stableId)) return null;
        return hit;
    }

    @Nullable
    private ComboTipBuilder.TipCandidate pickWeighted(
            List<ComboTipBuilder.TipCandidate> pool) {
        int r = mRandom.nextInt(100);
        ComboTipBuilder.Difficulty primary = bucketForRoll(r);
        ComboTipBuilder.TipCandidate picked = pickFromBucket(pool, primary);
        if (picked != null) return picked;

        // Fall-through order: EASY → MIDDLE → MUST_KNOW → HARD.
        final ComboTipBuilder.Difficulty[] fallback = {
                ComboTipBuilder.Difficulty.EASY,
                ComboTipBuilder.Difficulty.MIDDLE,
                ComboTipBuilder.Difficulty.MUST_KNOW,
                ComboTipBuilder.Difficulty.HARD,
        };
        for (ComboTipBuilder.Difficulty d : fallback) {
            if (d == primary) continue;
            picked = pickFromBucket(pool, d);
            if (picked != null) return picked;
        }
        // Pool empty across all tiers — shouldn't happen given pool.isEmpty() check.
        return null;
    }

    private static ComboTipBuilder.Difficulty bucketForRoll(int r) {
        // 0..24 MUST_KNOW, 25..64 EASY, 65..89 MIDDLE, 90..99 HARD.
        if (r < WEIGHT_MUST_KNOW) return ComboTipBuilder.Difficulty.MUST_KNOW;
        if (r < WEIGHT_MUST_KNOW + WEIGHT_EASY) return ComboTipBuilder.Difficulty.EASY;
        if (r < WEIGHT_MUST_KNOW + WEIGHT_EASY + WEIGHT_MIDDLE) return ComboTipBuilder.Difficulty.MIDDLE;
        return ComboTipBuilder.Difficulty.HARD;
    }

    @Nullable
    private ComboTipBuilder.TipCandidate pickFromBucket(
            List<ComboTipBuilder.TipCandidate> pool,
            ComboTipBuilder.Difficulty difficulty) {
        mScratchBucket.clear();
        mScratchFiltered.clear();
        for (int i = 0; i < pool.size(); i++) {
            ComboTipBuilder.TipCandidate c = pool.get(i);
            if (c.difficulty != difficulty) continue;
            mScratchBucket.add(c);
            if (!containsStableId(mLastShown, c.stableId)) {
                mScratchFiltered.add(c);
            }
        }
        if (!mScratchFiltered.isEmpty()) {
            return mScratchFiltered.get(mRandom.nextInt(mScratchFiltered.size()));
        }
        if (!mScratchBucket.isEmpty()) {
            // All members are in the recent ring — repeating is acceptable.
            return mScratchBucket.get(mRandom.nextInt(mScratchBucket.size()));
        }
        return null;
    }

    private static boolean containsStableId(ArrayDeque<String> ring, String id) {
        // Iterator allocation here is unavoidable with ArrayDeque; the ring is
        // ≤3 entries so cost is negligible. If it ever becomes a hotspot, swap
        // to a fixed-size String[3].
        Iterator<String> it = ring.iterator();
        while (it.hasNext()) {
            if (id.equals(it.next())) return true;
        }
        return false;
    }

    @VisibleForTesting
    int cyclesShownForTest() { return mCyclesShown; }
}
