/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.igalia.wolvic.ui.widgets.ComboHUDWidget;
import com.igalia.wolvic.ui.widgets.WidgetManagerDelegate;

import java.lang.ref.WeakReference;

/**
 * Plan §C5 / §D8 — combo-resolution haptic feedback.
 *
 * <ul>
 *   <li>Legal fire (dispatch matched) → 1 short buzz.</li>
 *   <li>Illegal release (no binding matched) → 2 short buzzes with a 60ms gap.</li>
 * </ul>
 *
 * Effective state = Wolvic global haptics pref (handled inside
 * {@link WidgetManagerDelegate#triggerHapticFeedback(int)}) AND the Glyphew
 * per-combo haptics pref ({@link ComboHUDWidget#PREF_COMBO_HAPTICS}). Either
 * off → skip entirely.
 *
 * <p>Handedness: {@link com.igalia.wolvic.input.ComboDispatcher#dispatch(int[], int)}
 * has no hand parameter in Phase 2. We fire on both controllers as a safe
 * fallback; the user felt the grip they just released either way, and buzzing
 * an idle controller is cheap. Threading real hand identity through dispatch
 * is deferred to Phase 5 (FROM_CAPTURE flow already surfaces handedness via
 * {@link com.igalia.wolvic.VRBrowserActivity#handleGripStateChanged}).
 *
 * <p>Leak safety: the delayed second buzz holds a {@link WeakReference} to
 * the {@link WidgetManagerDelegate} so an Activity finish mid-gap does not
 * leak through the main-looper Handler queue.
 */
public final class ComboHapticController {

    private static final int CONTROLLER_COUNT = 2;
    private static final float BUZZ_DURATION_MS = 30.0f;  // 30ms pulse — felt clearly on Quest controllers
    private static final float BUZZ_INTENSITY   = 1.0f;
    private static final long  ILLEGAL_GAP_MS   = 100L;   // 100ms gap — tight double-tap, clearly distinct from single buzz

    private final Context mAppContext;
    private final WeakReference<WidgetManagerDelegate> mDelegateRef;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public ComboHapticController(@NonNull Context context,
                                 @NonNull WidgetManagerDelegate delegate) {
        mAppContext = context.getApplicationContext();
        mDelegateRef = new WeakReference<>(delegate);
    }

    /** 1 short buzz on both controllers. Called on every legal combo dispatch. */
    public void fireLegalCombo() {
        if (!isCombosHapticsEnabled()) return;
        fireOnce();
    }

    /**
     * 2 short buzzes with a 60ms gap. Called on every grip-release whose
     * path failed to match a binding (unrecognised combo).
     */
    public void fireIllegalCombo() {
        if (!isCombosHapticsEnabled()) return;
        fireOnce();
        // Second buzz via main-looper Handler so we don't block the caller
        // (dispatch runs on the OpenXR input thread). WeakReference guards
        // against Activity finish during the 60ms gap.
        mMainHandler.postDelayed(mSecondBuzz, ILLEGAL_GAP_MS);
    }

    private final Runnable mSecondBuzz = new Runnable() {
        @Override
        public void run() {
            if (!isCombosHapticsEnabled()) return;
            fireOnce();
        }
    };

    private void fireOnce() {
        WidgetManagerDelegate delegate = mDelegateRef.get();
        if (delegate == null) return;
        for (int controllerId = 0; controllerId < CONTROLLER_COUNT; controllerId++) {
            delegate.triggerHapticFeedbackUnconditional(controllerId, BUZZ_DURATION_MS, BUZZ_INTENSITY);
        }
    }

    /**
     * Per-combo haptics pref. The global Wolvic haptics gate is applied
     * inside {@link WidgetManagerDelegate#triggerHapticFeedback(int)} so we
     * only check the Glyphew-specific key here.
     */
    private boolean isCombosHapticsEnabled() {
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(mAppContext);
        return prefs.getBoolean(ComboHUDWidget.PREF_COMBO_HAPTICS, true);
    }

    /**
     * Call on Activity teardown or when the dispatcher is being detached,
     * so any queued second-buzz runnable does not fire against a dead
     * delegate. Optional — the WeakReference guard already makes the
     * callback a no-op in that case, but clearing the queue is tidier.
     */
    public void shutdown() {
        mMainHandler.removeCallbacks(mSecondBuzz);
    }
}
