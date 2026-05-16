/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.igalia.wolvic;

import com.igalia.wolvic.browser.Places;
import com.igalia.wolvic.browser.Services;

import org.mockito.Mockito;

/**
 * Test-only Application subclass that extends VRBrowserApplication so that
 * BookmarksStore can cast context.applicationContext to VRBrowserApplication.
 *
 * getPlaces() returns a real Places instance (backed by PlacesBookmarksStorage
 * via the support-test-appservices JNA megazord already in build.gradle:837).
 *
 * getServices() returns a Mockito deep stub — registerForSyncEvents becomes a
 * no-op so no FxaAccountManager is needed in the test process.
 */
public class BookmarksTestApplication extends VRBrowserApplication {

    private Places mTestPlaces;
    private Services mTestServices;

    @Override
    public void onCreate() {
        super.onCreate();
        // StrictMode logging only — safe under Robolectric.
    }

    @Override
    public Places getPlaces() {
        if (mTestPlaces == null) {
            mTestPlaces = new Places(this);
        }
        return mTestPlaces;
    }

    @Override
    public Services getServices() {
        if (mTestServices == null) {
            // RETURNS_DEEP_STUBS: accountManager.registerForSyncEvents(...) becomes no-op.
            mTestServices = Mockito.mock(Services.class, Mockito.RETURNS_DEEP_STUBS);
        }
        return mTestServices;
    }
}
