/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.input;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import com.igalia.wolvic.TestApplication;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * TDD Red — Section 3.1: Combo-fire confirmation (in-HUD strip flash).
 *
 * <p>Pins the FireFlashListener contract on ComboDispatcher:
 * <ul>
 *   <li>A recognized combo fires the listener with the resolved action int.
 *   <li>An unbound path does NOT fire the listener.
 *   <li>No listener set → no NPE.
 *   <li>Listener cleared via setFireFlashListener(null) → no subsequent calls.
 * </ul>
 *
 * <p>Uses the test-only {@code ComboDispatcher(boolean)} constructor (mWindows /
 * mAppContext / mHapticController null). Scroll actions are safe — scroll()
 * null-guards focusedWindow(); haptic calls are null-guarded inline.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class ComboDispatcherFireFlashTest {

    private ComboDispatcher mDispatcher;

    @Before
    public void setUp() {
        mDispatcher = new ComboDispatcher(/*is4DirMode=*/true);
    }

    @Test
    public void dispatch_scrollUp_invokesFireFlashListenerWithCorrectAction() {
        AtomicInteger firedAction = new AtomicInteger(-1);
        mDispatcher.setFireFlashListener(action -> firedAction.set(action));

        // [2] = A_SCROLL_UP in the 4-dir default table.
        // scroll() null-guards mWindows, so no NPE in the test ctor.
        mDispatcher.dispatch(new int[]{2}, 1);
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("recognized combo must invoke fire-flash listener with A_SCROLL_UP",
                ComboDispatcher.A_SCROLL_UP, firedAction.get());
    }

    @Test
    public void dispatch_unboundPath_doesNotInvokeFireFlashListener() {
        AtomicInteger firedAction = new AtomicInteger(-1);
        mDispatcher.setFireFlashListener(action -> firedAction.set(action));

        // [2,2,2,2,2] is unbound in both tables — reaches the "unrecognised combo"
        // branch which null-guards mHapticController and mDefaultPrefs.
        mDispatcher.dispatch(new int[]{2, 2, 2, 2, 2}, 5);
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("unbound path must NOT invoke fire-flash listener",
                -1, firedAction.get());
    }

    @Test
    public void dispatch_noListenerSet_doesNotThrow() {
        // Dispatch without registering a listener — must not NPE.
        mDispatcher.dispatch(new int[]{2}, 1);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void setFireFlashListener_null_stopsSubsequentNotifications() {
        AtomicInteger firedAction = new AtomicInteger(-1);
        mDispatcher.setFireFlashListener(action -> firedAction.set(action));
        mDispatcher.setFireFlashListener(null);

        mDispatcher.dispatch(new int[]{2}, 1);
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("listener cleared via null must not fire", -1, firedAction.get());
    }
}
