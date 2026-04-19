/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * FingerDance proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.ui.widgets.combo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.Spannable;

import androidx.test.core.app.ApplicationProvider;

import com.igalia.wolvic.TestApplication;
import com.igalia.wolvic.input.ComboDispatcher;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class ComboTipBuilderTest {

    private Context mContext;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        ComboTipBuilder.clearCache();
    }

    // ---- classify fixtures (plan §Verification) -------------------------

    @Test public void classifyEmpty() {
        assertEquals(ComboTipBuilder.Difficulty.MUST_KNOW, ComboTipBuilder.classify(new int[]{}));
    }

    @Test public void classifyNull() {
        assertEquals(ComboTipBuilder.Difficulty.MUST_KNOW, ComboTipBuilder.classify(null));
    }

    @Test public void classifySingleCardinal() {
        assertEquals(ComboTipBuilder.Difficulty.EASY, ComboTipBuilder.classify(new int[]{2}));
    }

    @Test public void classifyCardinalDouble() {
        assertEquals(ComboTipBuilder.Difficulty.EASY, ComboTipBuilder.classify(new int[]{2, 2}));
    }

    @Test public void classifyCrossCardinalsHoriz() {
        assertEquals(ComboTipBuilder.Difficulty.MIDDLE, ComboTipBuilder.classify(new int[]{2, 4}));
    }

    @Test public void classifyCrossCardinalsVertOpposite() {
        assertEquals(ComboTipBuilder.Difficulty.MIDDLE, ComboTipBuilder.classify(new int[]{2, 8}));
    }

    @Test public void classifyTripleCardinal() {
        assertEquals(ComboTipBuilder.Difficulty.MIDDLE, ComboTipBuilder.classify(new int[]{2, 2, 2}));
    }

    @Test public void classifySingleDiagonal() {
        assertEquals(ComboTipBuilder.Difficulty.MIDDLE, ComboTipBuilder.classify(new int[]{1}));
    }

    @Test public void classifyDiagonalDouble() {
        assertEquals(ComboTipBuilder.Difficulty.HARD, ComboTipBuilder.classify(new int[]{3, 3}));
    }

    @Test public void classifyMidPathDiagonal() {
        assertEquals(ComboTipBuilder.Difficulty.HARD, ComboTipBuilder.classify(new int[]{2, 3, 6}));
    }

    @Test public void classifyFiveNode() {
        assertEquals(ComboTipBuilder.Difficulty.HARD, ComboTipBuilder.classify(new int[]{2, 1, 4, 7, 8}));
    }

    // Additional sanity: 2-node starting-with-diagonal stays MIDDLE
    // (plan gives single-diagonal as MIDDLE; a diagonal followed by cardinals
    // shouldn't be treated harder than the single-diagonal base case).
    @Test public void classifyLeadingDiagonalTwoNode() {
        assertEquals(ComboTipBuilder.Difficulty.MIDDLE, ComboTipBuilder.classify(new int[]{3, 2}));
    }

    // Diagonal at position ≥ 1 → HARD, even in a 2-node path.
    @Test public void classifyTailDiagonalTwoNode() {
        assertEquals(ComboTipBuilder.Difficulty.HARD, ComboTipBuilder.classify(new int[]{2, 3}));
    }

    // ---- buildAll pool counts ------------------------------------------

    @Test
    public void buildAllFourDirMatchesDispatcher() {
        ComboDispatcher dispatcher = new ComboDispatcher(/*is4DirMode=*/true);
        List<ComboTipBuilder.TipCandidate> tips =
                ComboTipBuilder.buildAll(dispatcher, mContext.getResources(), true);

        int metaCount = 0, bindingCount = 0;
        Set<String> ids = new HashSet<>();
        for (ComboTipBuilder.TipCandidate t : tips) {
            assertTrue("stableIds must be unique", ids.add(t.stableId));
            if (t.metaStringRes != 0) metaCount++;
            else bindingCount++;
        }
        assertEquals("4-dir dispatcher currently ships 22 binding tips", 22, bindingCount);
        assertEquals(3, metaCount);
        assertEquals(25, tips.size());
    }

    @Test
    public void buildAllEightDirMatchesDispatcher() {
        ComboDispatcher dispatcher = new ComboDispatcher(/*is4DirMode=*/false);
        List<ComboTipBuilder.TipCandidate> tips =
                ComboTipBuilder.buildAll(dispatcher, mContext.getResources(), false);

        int metaCount = 0, bindingCount = 0;
        for (ComboTipBuilder.TipCandidate t : tips) {
            if (t.metaStringRes != 0) metaCount++;
            else bindingCount++;
        }
        // 8-dir mode includes all 4-dir cardinal entries (putIfAbsent'd) plus
        // the 10 diagonal-and-multi-diagonal 8-dir-specific bindings. The
        // [8,8,8] entry appears once in both source sets so it's deduped.
        assertEquals("8-dir dispatcher currently ships 31 binding tips", 31, bindingCount);
        assertEquals(3, metaCount);
        assertEquals(34, tips.size());
    }

    @Test
    public void buildAllMarksAllMetaTipsMustKnow() {
        ComboDispatcher dispatcher = new ComboDispatcher(/*is4DirMode=*/true);
        List<ComboTipBuilder.TipCandidate> tips =
                ComboTipBuilder.buildAll(dispatcher, mContext.getResources(), true);
        int metaSeen = 0;
        for (ComboTipBuilder.TipCandidate t : tips) {
            if (t.metaStringRes == 0) continue;
            assertEquals(ComboTipBuilder.Difficulty.MUST_KNOW, t.difficulty);
            assertNull("meta tips must have null path", t.path);
            assertEquals(ComboDispatcher.A_NONE, t.actionInt);
            metaSeen++;
        }
        assertEquals(3, metaSeen);
    }

    // ---- renderBindingTip cache identity -------------------------------

    @Test
    public void renderBindingTipCachesIdenticalArgs() {
        int[] path = {2, 4};
        Spannable first = ComboTipBuilder.renderBindingTip(
                mContext, path, ComboDispatcher.A_PREV_WINDOW, 32, 0xFF00FFFF);
        Spannable second = ComboTipBuilder.renderBindingTip(
                mContext, path, ComboDispatcher.A_PREV_WINDOW, 32, 0xFF00FFFF);
        assertSame("same args → cached Spannable instance", first, second);
    }

    @Test
    public void renderBindingTipCacheBreaksOnColorChange() {
        int[] path = {2, 4};
        Spannable a = ComboTipBuilder.renderBindingTip(
                mContext, path, ComboDispatcher.A_PREV_WINDOW, 32, 0xFF00FFFF);
        Spannable b = ComboTipBuilder.renderBindingTip(
                mContext, path, ComboDispatcher.A_PREV_WINDOW, 32, 0xFFFF0000);
        assertNotNull(a);
        assertNotNull(b);
        // Distinct caller-visible objects when color differs — would bleed tints otherwise.
        assertTrue(a != b);
    }

    @Test
    public void renderBindingTipCacheBreaksOnSizeChange() {
        int[] path = {2, 4};
        Spannable a = ComboTipBuilder.renderBindingTip(
                mContext, path, ComboDispatcher.A_PREV_WINDOW, 32, 0xFF00FFFF);
        Spannable b = ComboTipBuilder.renderBindingTip(
                mContext, path, ComboDispatcher.A_PREV_WINDOW, 48, 0xFF00FFFF);
        assertTrue(a != b);
    }

    @Test
    public void clearCacheInvalidates() {
        int[] path = {2, 4};
        Spannable a = ComboTipBuilder.renderBindingTip(
                mContext, path, ComboDispatcher.A_PREV_WINDOW, 32, 0xFF00FFFF);
        ComboTipBuilder.clearCache();
        Spannable b = ComboTipBuilder.renderBindingTip(
                mContext, path, ComboDispatcher.A_PREV_WINDOW, 32, 0xFF00FFFF);
        assertTrue("cache clear produces fresh instance", a != b);
    }
}
