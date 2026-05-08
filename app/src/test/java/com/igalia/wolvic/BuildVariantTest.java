/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Pins the package identity so applicationId drift is caught at test time rather than after
 * a Meta Store submission is rejected (or worse, silently accepted under the wrong package).
 *
 * <p>Regression context: the fork applicationId is com.thanford.glyphew, distinct from upstream
 * com.igalia.wolvic. If build.gradle is merged from upstream and the applicationId line is
 * reverted, this test fails loudly instead of silently installing over a different package slot.
 */
@RunWith(JUnit4.class)
public class BuildVariantTest {

    @Test
    public void applicationId_isGlyphew() {
        // Changing this requires a new Meta Store submission (2-4 week review).
        // If this fails: check applicationId in app/build.gradle — it must be
        // "com.thanford.glyphew", not "com.igalia.wolvic".
        assertEquals(
                "applicationId must be com.thanford.glyphew — upstream merge likely reverted it",
                "com.thanford.glyphew",
                BuildConfig.APPLICATION_ID);
    }

    @Test
    public void buildFlavor_platformIsOculusvr() {
        // FLAVOR_platform is the per-dimension field set by the platform product flavor.
        // If this fails, the test was compiled against an unrecognized platform variant.
        assertEquals(
                "Expected FLAVOR_platform == 'oculusvr' — wrong flavor compiled",
                "oculusvr",
                BuildConfig.FLAVOR_platform);
    }

    @Test
    public void buildFlavor_backendIsChromiumOrGecko() {
        // FLAVOR_backend is "chromium" or "gecko". Anything else means the Gradle variant
        // dimensions drifted from what Glyphew expects.
        String backend = BuildConfig.FLAVOR_backend;
        assertTrue(
                "Expected FLAVOR_backend to be 'chromium' or 'gecko', got: " + backend,
                "chromium".equals(backend) || "gecko".equals(backend));
    }

}
