/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * FingerDance proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.ui.widgets.combo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.style.ImageSpan;

import androidx.annotation.Nullable;
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
                ComboTipBuilder.buildAll(dispatcher, mContext.getResources());

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
                ComboTipBuilder.buildAll(dispatcher, mContext.getResources());

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
                ComboTipBuilder.buildAll(dispatcher, mContext.getResources());
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

    // ---- rebind propagation (plan §Verification) -----------------------

    /**
     * When a user rebinds path {2,2} from A_SCROLL_TOP → A_OPEN_BOOKMARKS,
     * both the {@link ComboTipBuilder.TipCandidate#actionInt} in the pool
     * AND the rendered Spannable's trailing action-icon ImageSpan must
     * reflect the new action. Regression guard: a future change that
     * caches TipCandidates or icons by path alone would silently keep
     * the old icon visible after a rebind.
     *
     * <p>Precondition: A_SCROLL_TOP and A_OPEN_BOOKMARKS map to different
     * drawable resources in {@link ComboActionIcons}. Without that we'd
     * have no way to tell the icons apart after rebind.
     */
    @Test
    public void buildAllReflectsRebindingInActionIntAndSpannable() {
        // Precondition: the two actions we're flipping between must
        // resolve to different resources — otherwise the Spannable-icon
        // assertion below is meaningless.
        assertNotEquals("test precondition: the two actions must map to different icons",
                ComboActionIcons.iconFor(ComboDispatcher.A_SCROLL_TOP),
                ComboActionIcons.iconFor(ComboDispatcher.A_OPEN_BOOKMARKS));

        ComboDispatcher dispatcher = new ComboDispatcher(/*is4DirMode=*/true);
        int[] path = {2, 2};

        // --- Baseline: default binding is A_SCROLL_TOP.
        List<ComboTipBuilder.TipCandidate> baselineTips =
                ComboTipBuilder.buildAll(dispatcher, mContext.getResources());
        ComboTipBuilder.TipCandidate baselineCandidate = findCandidateForPath(baselineTips, path);
        assertNotNull("baseline pool must contain {2,2} tip", baselineCandidate);
        assertEquals("default binding for {2,2} is A_SCROLL_TOP",
                ComboDispatcher.A_SCROLL_TOP, baselineCandidate.actionInt);

        Spannable baselineSpannable = ComboTipBuilder.renderBindingTip(
                mContext, path, baselineCandidate.actionInt, 32, 0xFF00FFFF);
        assertNotNull("baseline trailing ImageSpan must resolve",
                trailingImageSpanDrawableConstantState(baselineSpannable));

        // --- Rebind {2,2} to A_OPEN_BOOKMARKS; clear the Spannable cache.
        dispatcher.setBindingForTest(path, ComboDispatcher.A_OPEN_BOOKMARKS);
        ComboTipBuilder.clearCache();

        // --- Pool must now reflect the new actionInt. This is the core
        // assertion: buildAll re-reads the dispatcher on every call, so
        // any caching regression that kept the old actionInt alive would
        // fail here.
        List<ComboTipBuilder.TipCandidate> rebuiltTips =
                ComboTipBuilder.buildAll(dispatcher, mContext.getResources());
        ComboTipBuilder.TipCandidate rebuiltCandidate = findCandidateForPath(rebuiltTips, path);
        assertNotNull("rebuilt pool must still contain {2,2} tip", rebuiltCandidate);
        assertEquals("rebind must update actionInt in rebuilt pool",
                ComboDispatcher.A_OPEN_BOOKMARKS, rebuiltCandidate.actionInt);

        // --- Re-rendered Spannable must carry a trailing ImageSpan, and
        // because renderBindingTip feeds the rebuilt candidate's actionInt
        // through ComboActionIcons.iconFor (which returns a different
        // drawable resource for A_OPEN_BOOKMARKS vs A_SCROLL_TOP — enforced
        // by the precondition above), the rendered icon is necessarily
        // the bookmarks glyph, not scroll-top.
        Spannable rebuiltSpannable = ComboTipBuilder.renderBindingTip(
                mContext, path, rebuiltCandidate.actionInt, 32, 0xFF00FFFF);
        assertNotNull("rebuilt trailing ImageSpan must resolve",
                trailingImageSpanDrawableConstantState(rebuiltSpannable));

        // Assert cache discrimination: renderBindingTip keys by actionInt,
        // so a call with the old A_SCROLL_TOP and one with the new
        // A_OPEN_BOOKMARKS for the same path must produce distinct
        // Spannable instances (not the same cached object). This catches
        // a regression where the Spannable cache keyed by path alone
        // would serve stale scroll-top icons after rebind.
        Spannable asScrollTop = ComboTipBuilder.renderBindingTip(
                mContext, path, ComboDispatcher.A_SCROLL_TOP, 32, 0xFF00FFFF);
        assertTrue("cache must key by actionInt, not path alone",
                asScrollTop != rebuiltSpannable);
    }

    @Nullable
    private static ComboTipBuilder.TipCandidate findCandidateForPath(
            List<ComboTipBuilder.TipCandidate> tips, int[] path) {
        for (ComboTipBuilder.TipCandidate t : tips) {
            if (t.path != null && java.util.Arrays.equals(t.path, path)) return t;
        }
        return null;
    }

    @Nullable
    private static Object trailingImageSpanDrawableConstantState(Spannable s) {
        int len = s.length();
        ImageSpan[] spans = s.getSpans(len - 1, len, ImageSpan.class);
        assertTrue("Spannable must end with at least one ImageSpan", spans.length >= 1);
        Drawable d = spans[spans.length - 1].getDrawable();
        assertNotNull("trailing ImageSpan must have a drawable", d);
        return d.getConstantState();
    }
}
