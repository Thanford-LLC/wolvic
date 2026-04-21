/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * FingerDance proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import com.igalia.wolvic.TestApplication;
import com.thanford.fingerdance.settings.Binding;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class ComboDispatcherTest {

    private ComboDispatcher mDispatcher4Dir;
    private ComboDispatcher mDispatcher8Dir;

    @Before
    public void setUp() {
        // Use test-only constructor — skips native + Windows wiring.
        mDispatcher4Dir = new ComboDispatcher(/*is4DirMode=*/true);
        mDispatcher8Dir = new ComboDispatcher(/*is4DirMode=*/false);
    }

    // ---- getActionForExactPath ------------------------------------------

    @Test
    public void exactPathReturnsActionForBinding() {
        assertEquals(ComboDispatcher.A_SCROLL_UP,
                mDispatcher4Dir.getActionForExactPath(new int[]{2}));
        assertEquals(ComboDispatcher.A_REFRESH,
                mDispatcher4Dir.getActionForExactPath(new int[]{2, 8}));
    }

    @Test
    public void exactPathReturnsNoneForPrefix() {
        // 8-dir mode has [2,1,4,7,8] = READER_MODE. [2,1] is a prefix of that
        // path and is NOT itself bound to any action.
        assertEquals(ComboDispatcher.A_NONE,
                mDispatcher8Dir.getActionForExactPath(new int[]{2, 1}));
    }

    @Test
    public void exactPathReturnsNoneForUnbound() {
        assertEquals(ComboDispatcher.A_NONE,
                mDispatcher4Dir.getActionForExactPath(new int[]{2, 2, 8}));
    }

    @Test
    public void exactPathNullSafe() {
        assertEquals(ComboDispatcher.A_NONE, mDispatcher4Dir.getActionForExactPath(null));
    }

    // ---- getLegalNextNodes ----------------------------------------------

    @Test
    public void legalNextNodesFromEmptyFourDir() {
        // From empty path in 4-dir: starting nodes are cardinals {2,4,6,8}.
        Set<Integer> nexts = mDispatcher4Dir.getLegalNextNodes(new int[]{});
        assertTrue(nexts.contains(2));
        assertTrue(nexts.contains(4));
        assertTrue(nexts.contains(6));
        assertTrue(nexts.contains(8));
        assertFalse("5 is never legal next (center implicit origin)", nexts.contains(5));
        // 4-dir mode uses cardinals only.
        assertFalse(nexts.contains(1));
        assertFalse(nexts.contains(3));
        assertFalse(nexts.contains(7));
        assertFalse(nexts.contains(9));
    }

    @Test
    public void legalNextNodesFromEmptyEightDir() {
        Set<Integer> nexts = mDispatcher8Dir.getLegalNextNodes(new int[]{});
        for (int n : new int[]{1, 2, 3, 4, 6, 7, 8, 9}) {
            assertTrue("8-dir should allow node " + n, nexts.contains(n));
        }
        assertFalse(nexts.contains(5));
    }

    @Test
    public void legalNextNodesFromNullEqualsEmpty() {
        Set<Integer> nullCase = mDispatcher4Dir.getLegalNextNodes(null);
        Set<Integer> emptyCase = mDispatcher4Dir.getLegalNextNodes(new int[]{});
        assertEquals(emptyCase, nullCase);
    }

    @Test
    public void legalNextNodesFromNodeTwo() {
        // From [2] in 4-dir: extensions [2,2] (A_SCROLL_TOP) exists, [2,8]
        // (A_REFRESH), [2,4] (A_PREV_WINDOW), [2,6] (A_NEXT_WINDOW).
        Set<Integer> nexts = mDispatcher4Dir.getLegalNextNodes(new int[]{2});
        assertTrue(nexts.contains(2));
        assertTrue(nexts.contains(4));
        assertTrue(nexts.contains(6));
        assertTrue(nexts.contains(8));
    }

    @Test
    public void legalNextNodesFromDeadEnd() {
        // [2,2,8] is not a prefix of any binding.
        Set<Integer> nexts = mDispatcher4Dir.getLegalNextNodes(new int[]{2, 2, 8});
        assertTrue("dead-end path should have no legal nexts", nexts.isEmpty());
    }

    // ---- BindingsListener -----------------------------------------------

    @Test
    public void bindingsListenerFiresOnReload() {
        AtomicInteger count = new AtomicInteger(0);
        ComboDispatcher.BindingsListener l = count::incrementAndGet;
        mDispatcher4Dir.addBindingsListener(l);
        mDispatcher4Dir.reloadBindings();
        assertEquals(1, count.get());
        mDispatcher4Dir.removeBindingsListener(l);
        mDispatcher4Dir.reloadBindings();
        assertEquals("removed listener should not fire", 1, count.get());
    }

    @Test
    public void bindingsListenerFiresOnSetBindingForTest() {
        AtomicInteger count = new AtomicInteger(0);
        mDispatcher4Dir.addBindingsListener(count::incrementAndGet);
        mDispatcher4Dir.setBindingForTest(new int[]{6, 6}, ComboDispatcher.A_FORWARD);
        assertEquals(1, count.get());
    }

    // ---- getAllBindings -------------------------------------------------

    @Test
    public void getAllBindingsReturnsCurrentTable() {
        assertNotNull(mDispatcher4Dir.getAllBindings());
        assertTrue(mDispatcher4Dir.getAllBindings().containsKey("[2]"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void getAllBindingsIsUnmodifiable() {
        mDispatcher4Dir.getAllBindings().put("[9,9,9,9,9]", Binding.of(999));
    }

    // ---- Legal-next rebuilt after rebinding ----------------------------

    @Test
    public void legalNextCacheRebuildsAfterRebinding() {
        // Pre: [2,2,8] is dead-end.
        assertTrue(mDispatcher4Dir.getLegalNextNodes(new int[]{2, 2, 8}).isEmpty());
        // Rebind a 4-node path starting with 2,2,8 so 8 becomes a legal next of [2,2].
        mDispatcher4Dir.setBindingForTest(new int[]{2, 2, 8, 4}, ComboDispatcher.A_HISTORY);
        Set<Integer> nexts = mDispatcher4Dir.getLegalNextNodes(new int[]{2, 2, 8});
        assertTrue("after rebind, node 4 should be a legal next from [2,2,8]",
                nexts.contains(4));
    }
}
