/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.igalia.wolvic.input;

import static org.junit.Assert.assertNotNull;

import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import com.igalia.wolvic.TestApplication;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;

/**
 * TDD Cycle 3 — Phase 8b: A_GOTO_BOOKMARK dispatch.
 *
 * <p>runAction() is private; behavioral tests verifying URL loading require
 * a real SessionStore and are deferred to on-device (plan §9 step 2). These
 * tests verify:
 * <ol>
 *   <li>The param-bearing runAction(int, String) overload exists (signature contract).
 *   <li>Dispatching A_GOTO_BOOKMARK with param=null completes without exception
 *       (the null-param guard fires, no SessionStore access needed).
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class ComboDispatcherGotoBookmarkTest {

    private ComboDispatcher mDispatcher;

    @Before
    public void setUp() {
        mDispatcher = new ComboDispatcher(/*is4DirMode=*/true);
    }

    @Test
    public void runAction_hasParamBearingOverload() throws Exception {
        // runAction(int, String) must exist so the dispatch call site can pass
        // binding.param through to A_GOTO_BOOKMARK without widening the public API.
        Method m = ComboDispatcher.class.getDeclaredMethod("runAction", int.class, String.class);
        assertNotNull("runAction(int, String) must exist on ComboDispatcher", m);
    }

    @Test
    public void dispatch_gotoBookmark_nullParam_doesNotThrow() {
        // setBindingForTest uses Binding.of(actionInt) → param=null.
        // The null-param guard in the A_GOTO_BOOKMARK case must return early
        // without touching SessionStore (which is null in the test ctor).
        mDispatcher.setBindingForTest(new int[]{6, 6, 8}, ComboDispatcher.A_GOTO_BOOKMARK);
        // Should not throw:
        mDispatcher.dispatch(new int[]{6, 6, 8}, 3);
        shadowOf(Looper.getMainLooper()).idle();
    }
}
