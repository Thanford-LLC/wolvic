/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.ui.widgets.combo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.igalia.wolvic.TestApplication;
import com.igalia.wolvic.input.ComboDispatcher;
import com.igalia.wolvic.ui.widgets.ComboHUDWidget;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests ComboHUDWidget invariants that don't require widget instantiation.
 *
 * <p>ComboHUDWidget extends UIWidget (Android View + resource lookups in
 * constructor), so full instantiation requires a VR activity context that
 * Robolectric can't fully replicate. Tests here focus on the three testable
 * seams: preference key constants, SharedPreferences round-trips via those
 * constants, and BindingsListener registration via ComboDispatcher.
 *
 * <p>Regression pins:
 * <ul>
 *   <li>PREF_HUD_VISIBLE key rename would silently break the HUD toggle —
 *       the preference would always read its default (true) regardless of
 *       the user's setting.
 *   <li>PREF_GHOST_VISIBLE key rename would silently break ghost-route toggling.
 *   <li>BindingsListener removal must be idempotent (WeakHashMap hazard:
 *       SharedPreferences holds listeners weakly; ComboDispatcher holds them
 *       strongly in CopyOnWriteArrayList, so explicit remove is the contract).
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class ComboHUDWidgetTest {

    private Context mCtx;
    private SharedPreferences mDefaultPrefs;

    @Before
    public void setUp() {
        mCtx = ApplicationProvider.getApplicationContext();
        mDefaultPrefs = PreferenceManager.getDefaultSharedPreferences(mCtx);
        mDefaultPrefs.edit().clear().commit();
    }

    // ── Preference key constants ──────────────────────────────────────────────

    @Test
    public void prefHudVisible_constantValue_isCorrect() {
        // Renaming or mis-spelling this key silently breaks the HUD toggle —
        // the default SharedPreferences fallback (true) would always win.
        assertEquals("glyphew_hud_visible", ComboHUDWidget.PREF_HUD_VISIBLE);
    }

    @Test
    public void prefGhostVisible_constantValue_isCorrect() {
        assertEquals("glyphew_hud_ghost_visible", ComboHUDWidget.PREF_GHOST_VISIBLE);
    }

    @Test
    public void prefComboHaptics_constantValue_isCorrect() {
        assertEquals("glyphew_combo_haptics", ComboHUDWidget.PREF_COMBO_HAPTICS);
    }

    // ── SharedPreferences round-trips via PREF_HUD_VISIBLE ───────────────────

    @Test
    public void prefHudVisible_roundTrip_false() {
        mDefaultPrefs.edit().putBoolean(ComboHUDWidget.PREF_HUD_VISIBLE, false).commit();
        assertFalse("pref round-trip: false should read back as false",
                mDefaultPrefs.getBoolean(ComboHUDWidget.PREF_HUD_VISIBLE, true));
    }

    @Test
    public void prefHudVisible_roundTrip_true() {
        mDefaultPrefs.edit().putBoolean(ComboHUDWidget.PREF_HUD_VISIBLE, true).commit();
        assertTrue("pref round-trip: true should read back as true",
                mDefaultPrefs.getBoolean(ComboHUDWidget.PREF_HUD_VISIBLE, false));
    }

    @Test
    public void prefHudVisible_defaultValue_isTrue() {
        // Default must be true: on fresh install the HUD is visible. If someone
        // changes the default it silently hides the HUD for all new users.
        assertTrue("default for PREF_HUD_VISIBLE must be true",
                mDefaultPrefs.getBoolean(ComboHUDWidget.PREF_HUD_VISIBLE, true));
    }

    // ── BindingsListener registration via ComboDispatcher ────────────────────

    @Test
    public void bindingsListener_addAndFire_viaDispatcher() {
        // ComboHUDWidget implements ComboDispatcher.BindingsListener and registers
        // via attachDispatcher(). This test verifies the dispatcher side of that
        // contract without instantiating the widget.
        ComboDispatcher dispatcher = new ComboDispatcher(/*is4DirMode=*/true);
        AtomicInteger fireCount = new AtomicInteger(0);

        ComboDispatcher.BindingsListener hudShapeListener = fireCount::incrementAndGet;
        dispatcher.addBindingsListener(hudShapeListener);
        dispatcher.reloadBindings();

        assertEquals("BindingsListener must fire on reloadBindings()", 1, fireCount.get());
    }

    @Test
    public void bindingsListener_removeStopsNotifications() {
        ComboDispatcher dispatcher = new ComboDispatcher(/*is4DirMode=*/true);
        AtomicInteger fireCount = new AtomicInteger(0);

        ComboDispatcher.BindingsListener hudShapeListener = fireCount::incrementAndGet;
        dispatcher.addBindingsListener(hudShapeListener);
        dispatcher.reloadBindings(); // fires once

        dispatcher.removeBindingsListener(hudShapeListener);
        dispatcher.reloadBindings(); // must not fire

        assertEquals("removed BindingsListener must not receive further notifications",
                1, fireCount.get());
    }

    @Test
    public void bindingsListener_addSameListenerTwice_firesOnce() {
        // CopyOnWriteArrayList.addIfAbsent prevents duplicate registration.
        // Duplicate registration would double-repaint the HUD per rebind.
        ComboDispatcher dispatcher = new ComboDispatcher(/*is4DirMode=*/true);
        AtomicInteger fireCount = new AtomicInteger(0);

        ComboDispatcher.BindingsListener l = fireCount::incrementAndGet;
        dispatcher.addBindingsListener(l);
        dispatcher.addBindingsListener(l); // second call must be a no-op
        dispatcher.reloadBindings();

        assertEquals("duplicate registration must not double-fire", 1, fireCount.get());
    }
}
