/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.settings;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.igalia.wolvic.input.ComboDispatcher;
import com.igalia.wolvic.ui.widgets.dialogs.PromptDialogWidget;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
public class ComboBookmarkConfirmViewTest {

    @Test
    public void class_extendsPromptDialogWidget() {
        assertTrue(
                "ComboBookmarkConfirmView must extend PromptDialogWidget",
                PromptDialogWidget.class.isAssignableFrom(ComboBookmarkConfirmView.class));
    }

    @Test
    public void constructor_hasExpectedSignature() throws Exception {
        Constructor<?> ctor = ComboBookmarkConfirmView.class.getDeclaredConstructor(
                Context.class,
                ComboDispatcher.class,
                int[].class,
                String.class,
                String.class);
        assertNotNull("Constructor(Context, ComboDispatcher, int[], String, String) must exist", ctor);
    }

    @Test
    public void show_isOverridden() throws Exception {
        // show() must be declared on the class itself (not just inherited) to call
        // dispatcher.setCaptureMode(true, null) and dim the HUD.
        Method show = ComboBookmarkConfirmView.class.getDeclaredMethod("show", int.class);
        assertNotNull("show(int) must be overridden in ComboBookmarkConfirmView", show);
    }

    @Test
    public void onDismiss_isOverridden() throws Exception {
        Method onDismiss = ComboBookmarkConfirmView.class.getDeclaredMethod("onDismiss");
        assertNotNull("onDismiss() must be overridden to release capture mode", onDismiss);
    }

    @Test
    public void updateUI_isOverridden() throws Exception {
        Method updateUI = ComboBookmarkConfirmView.class.getDeclaredMethod("updateUI");
        assertNotNull("updateUI() must be overridden to build confirm body", updateUI);
    }
}
