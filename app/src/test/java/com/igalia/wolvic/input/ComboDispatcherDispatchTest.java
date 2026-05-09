/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.input;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import com.igalia.wolvic.TestApplication;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests dispatch() behaviour: capture-mode routing, 4-dir cardinal-fallback
 * ambiguity handling, and the getLegalNextNodes-never-contains-5 invariant.
 *
 * <p>Uses the test-only {@code ComboDispatcher(boolean)} constructor, which
 * leaves {@code mWindows} / {@code mAppContext} null and skips native wiring.
 * Dispatch paths that resolve to scroll actions are safe (scroll() null-guards
 * focusedWindow). Capture-mode tests never reach runAction().
 *
 * <p>Regression pins:
 * <ul>
 *   <li>Capture-mode routing: if setCaptureMode is called, dispatch() MUST
 *       post the raw path to the listener and return — not fire an action.
 *   <li>Node 5 (center origin) must never appear as a legal next node; it is
 *       implicitly consumed and never mid-path (CLAUDE.md §4.1 + §5.1).
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class ComboDispatcherDispatchTest {

    private ComboDispatcher mDispatcher4Dir;
    private ComboDispatcher mDispatcher8Dir;

    @Before
    public void setUp() {
        mDispatcher4Dir = new ComboDispatcher(/*is4DirMode=*/true);
        mDispatcher8Dir = new ComboDispatcher(/*is4DirMode=*/false);
    }

    // ── Capture-mode routing ──────────────────────────────────────────────────

    @Test
    public void dispatch_captureMode_deliversPathToListenerOnMainThread() {
        AtomicReference<int[]> captured = new AtomicReference<>();
        mDispatcher4Dir.setCaptureMode(true, path -> captured.set(path));

        mDispatcher4Dir.dispatch(new int[]{2, 4, 6}, 3);
        shadowOf(Looper.getMainLooper()).idle();

        assertNotNull("listener must receive captured path", captured.get());
        assertArrayEquals(new int[]{2, 4, 6}, captured.get());
    }

    @Test
    public void dispatch_captureMode_pathLengthRespected_extraElementsExcluded() {
        AtomicReference<int[]> captured = new AtomicReference<>();
        mDispatcher4Dir.setCaptureMode(true, path -> captured.set(path));

        // Buffer has 5 elements but only 2 are meaningful.
        mDispatcher4Dir.dispatch(new int[]{4, 6, 0, 0, 0}, 2);
        shadowOf(Looper.getMainLooper()).idle();

        assertNotNull(captured.get());
        assertArrayEquals("captured path must be trimmed to length=2",
                new int[]{4, 6}, captured.get());
    }

    @Test
    public void dispatch_captureMode_withNullListener_fallsThroughToNormalDispatch() {
        // setCaptureMode(true, null) is treated as disabled (prevents orphan state).
        // Use an unbound path so dispatch reaches the "unrecognized combo" branch,
        // which is safe in the test ctor (mHapticController + mDefaultPrefs are null-guarded).
        // [2,2,2,2,2] is not bound — A_TOGGLE_MODE needs 8 ups in 4-dir.
        mDispatcher4Dir.setCaptureMode(true, null);
        mDispatcher4Dir.dispatch(new int[]{2, 2, 2, 2, 2}, 5); // unbound → unrecognized
    }

    @Test
    public void dispatch_captureMode_disabled_listenerNotCalled() {
        List<int[]> calls = new ArrayList<>();
        mDispatcher4Dir.setCaptureMode(true, calls::add);
        mDispatcher4Dir.setCaptureMode(false, null);

        // Use an unbound path so dispatch hits the null-guarded "unrecognized" branch
        // rather than runAction (focusedWindow() NPEs in test ctor — mWindows is null).
        mDispatcher4Dir.dispatch(new int[]{2, 2, 2, 2, 2}, 5);
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue("listener must not fire after setCaptureMode(false)", calls.isEmpty());
    }

    // ── 4-dir cardinal-fallback ambiguity ─────────────────────────────────────

    @Test
    public void dispatch_4dir_ambiguousDiagonal_doesNotThrow() {
        // [1] = upper-left diagonal. Both candidates [2] (SCROLL_UP) and
        // [4] (SCROLL_LEFT) are bound → ambiguous → no action. mHapticController
        // is null in test ctor so the "illegal combo" branch is also safe.
        mDispatcher4Dir.dispatch(new int[]{1}, 1);
    }

    @Test
    public void dispatch_4dir_unboundPath_doesNotThrow() {
        // [2,2,2,2] is A_HISTORY only; [2,2,2,2,2] is unbound in 4-dir table.
        mDispatcher4Dir.dispatch(new int[]{2, 2, 2, 2, 2}, 5);
    }

    @Test
    public void dispatch_lengthZero_isIgnored() {
        mDispatcher4Dir.dispatch(new int[]{2}, 0);
    }

    @Test
    public void dispatch_lengthNegative_isIgnored() {
        mDispatcher4Dir.dispatch(new int[]{2}, -1);
    }

    // ── getLegalNextNodes never returns 5 ─────────────────────────────────────

    @Test
    public void legalNextNodes_neverContainsFive_fromEmptyPrefix_4dir() {
        assertFalse("center node 5 must never be a legal next node in 4-dir mode",
                mDispatcher4Dir.getLegalNextNodes(new int[]{}).contains(5));
    }

    @Test
    public void legalNextNodes_neverContainsFive_fromEmptyPrefix_8dir() {
        assertFalse("center node 5 must never be a legal next node in 8-dir mode",
                mDispatcher8Dir.getLegalNextNodes(new int[]{}).contains(5));
    }

    @Test
    public void legalNextNodes_neverContainsFive_fromAllSingleNodePrefixes_4dir() {
        // Node 5 is the implicit center; it is never mid-path (CLAUDE.md §4.1 + §5.1).
        for (int node : new int[]{1, 2, 3, 4, 6, 7, 8, 9}) {
            Set<Integer> nexts = mDispatcher4Dir.getLegalNextNodes(new int[]{node});
            assertFalse("from prefix [" + node + "]: node 5 must not appear (4-dir)",
                    nexts.contains(5));
        }
    }

    @Test
    public void legalNextNodes_neverContainsFive_fromAllSingleNodePrefixes_8dir() {
        for (int node : new int[]{1, 2, 3, 4, 6, 7, 8, 9}) {
            Set<Integer> nexts = mDispatcher8Dir.getLegalNextNodes(new int[]{node});
            assertFalse("from prefix [" + node + "]: node 5 must not appear (8-dir)",
                    nexts.contains(5));
        }
    }
}
