/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.igalia.wolvic.TestApplication;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TDD — CatalogUpdateManager lock + callback routing.
 *
 * Network I/O is injected via {@link CatalogUpdateManager.FetchStrategy} so
 * tests stay fully in-process. Only the observable outcomes are asserted:
 * whether a fetch was attempted and which callbacks fired.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class CatalogUpdateManagerTest {

    private static final long MILLIS_23H = 23 * 60 * 60 * 1000L;
    private static final long MILLIS_25H = 25 * 60 * 60 * 1000L;

    private Context mContext;
    private SharedPreferences mPrefs;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        mPrefs = mContext.getSharedPreferences("catalog_update", Context.MODE_PRIVATE);
        mPrefs.edit().clear().commit();
    }

    // ── Cycle 1: no prior check → fetch is attempted ──────────────────────────

    @Test
    public void noPriorCheck_fetchAttempted() {
        AtomicBoolean fetched = new AtomicBoolean(false);
        CatalogUpdateManager mgr = managerWithFetch(fetched, /* succeed */ false);
        mgr.checkAsync();
        waitForBackground();
        assertTrue(fetched.get());
    }

    // ── Cycle 2: recent check (< 24h) → fetch is skipped ─────────────────────

    @Test
    public void recentCheck_fetchSkipped() {
        long recentCheckAt = System.currentTimeMillis() - MILLIS_23H;
        mPrefs.edit().putLong(CatalogUpdateManager.PREF_LAST_CHECK_AT, recentCheckAt).commit();

        AtomicBoolean fetched = new AtomicBoolean(false);
        CatalogUpdateManager mgr = managerWithFetch(fetched, false);
        mgr.checkAsync();
        waitForBackground();
        assertFalse(fetched.get());
    }

    // ── Cycle 3: stale check (> 24h) → fetch is attempted ────────────────────

    @Test
    public void staleCheck_fetchAttempted() {
        long staleCheckAt = System.currentTimeMillis() - MILLIS_25H;
        mPrefs.edit().putLong(CatalogUpdateManager.PREF_LAST_CHECK_AT, staleCheckAt).commit();

        AtomicBoolean fetched = new AtomicBoolean(false);
        CatalogUpdateManager mgr = managerWithFetch(fetched, false);
        mgr.checkAsync();
        waitForBackground();
        assertTrue(fetched.get());
    }

    // ── Cycle 4: successful fetch → last_check_at is updated ─────────────────

    @Test
    public void successfulFetch_updatesTimestamp() {
        long before = System.currentTimeMillis();
        CatalogUpdateManager mgr = managerWithFetch(new AtomicBoolean(), /* succeed */ true);
        mgr.checkAsync();
        waitForBackground();
        long saved = mPrefs.getLong(CatalogUpdateManager.PREF_LAST_CHECK_AT, 0);
        assertTrue("timestamp should be updated", saved >= before);
    }

    // ── Cycle 5: successful fetch → catalog callback fires once ───────────────

    @Test
    public void successfulFetch_catalogCallbackFires() {
        AtomicInteger callbackCount = new AtomicInteger(0);
        CatalogUpdateManager mgr = new CatalogUpdateManager(
                mContext,
                (catalog) -> {}, // store callback — not under test here
                () -> callbackCount.incrementAndGet(), // catalog-updated UI callback
                (v, u) -> {},   // app-update UI callback
                successfulFetchStrategy()
        );
        mgr.checkAsync();
        waitForBackground();
        assertEquals(1, callbackCount.get());
    }

    // ── Cycle 6: failed fetch → catalog callback does NOT fire ────────────────

    @Test
    public void failedFetch_catalogCallbackDoesNotFire() {
        AtomicInteger callbackCount = new AtomicInteger(0);
        CatalogUpdateManager mgr = new CatalogUpdateManager(
                mContext,
                (catalog) -> {},
                () -> callbackCount.incrementAndGet(),
                (v, u) -> {},
                failedFetchStrategy()
        );
        mgr.checkAsync();
        waitForBackground();
        assertEquals(0, callbackCount.get());
    }

    // ── Cycle 7: app update available → app callback fires ───────────────────

    @Test
    public void appUpdateAvailable_appCallbackFires() {
        AtomicInteger appCallbackCount = new AtomicInteger(0);
        CatalogUpdateManager mgr = new CatalogUpdateManager(
                mContext,
                (catalog) -> {},
                () -> {},
                (v, u) -> appCallbackCount.incrementAndGet(),
                appUpdateFetchStrategy()
        );
        mgr.checkAsync();
        waitForBackground();
        assertEquals(1, appCallbackCount.get());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private CatalogUpdateManager managerWithFetch(AtomicBoolean fetchCalled, boolean succeed) {
        return new CatalogUpdateManager(
                mContext,
                (catalog) -> {},
                () -> {},
                (v, u) -> {},
                succeed ? successfulFetchStrategy(fetchCalled) : noopFetchStrategy(fetchCalled)
        );
    }

    private static CatalogUpdateManager.FetchStrategy noopFetchStrategy(AtomicBoolean flag) {
        return () -> {
            flag.set(true);
            return null; // simulate network failure
        };
    }

    private static CatalogUpdateManager.FetchStrategy successfulFetchStrategy(AtomicBoolean flag) {
        return () -> {
            if (flag != null) flag.set(true);
            return new CatalogUpdateManager.FetchResult(
                    "{\"catalog_version\":1,\"categories\":[{\"id\":\"video\",\"label\":\"Video\",\"order\":1,\"tiles\":[{\"url\":\"https://youtube.com/\",\"label\":\"YouTube\",\"icon\":\"youtube\",\"featured\":false}]}]}",
                    null, null // no app update
            );
        };
    }

    private static CatalogUpdateManager.FetchStrategy successfulFetchStrategy() {
        return successfulFetchStrategy(null);
    }

    private static CatalogUpdateManager.FetchStrategy failedFetchStrategy() {
        return () -> null;
    }

    private static CatalogUpdateManager.FetchStrategy appUpdateFetchStrategy() {
        return () -> new CatalogUpdateManager.FetchResult(
                null, // no catalog update
                "1.0.5",
                "https://www.meta.com/experiences/glyphew/123"
        );
    }

    /** Give the background executor time to finish. */
    private static void waitForBackground() {
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
    }
}
