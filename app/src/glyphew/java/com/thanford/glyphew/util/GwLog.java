/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.util;

import android.util.Log;

import com.igalia.wolvic.BuildConfig;

/**
 * Dev-only diagnostic logging for the Glyphew VR pipeline.
 *
 * <p>Gated on {@link BuildConfig#DEBUG}: emitted in the debug ("dev") APK and
 * compiled out of the official (release) APK, so nothing reaches logcat in
 * production. The native side has the same switch via {@code VRB_DEBUG}
 * (gated on {@code NDEBUG}).
 *
 * <p>Filter on-device with: {@code adb logcat -s GW_VR}.
 */
public final class GwLog {
    public static final String TAG = "GW_VR";

    private GwLog() {}

    /** True only in the dev (debug) build. */
    public static boolean enabled() {
        return BuildConfig.DEBUG;
    }

    public static void d(String msg) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, msg);
        }
    }

    public static void d(String subtag, String msg) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[" + subtag + "] " + msg);
        }
    }
}
