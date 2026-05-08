/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.browser.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.igalia.wolvic.utils.UrlUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Pins the three URL-leak guards that prevent raw home-page URLs (data:, chrome://home,
 * resource:, about://home) from appearing in the navigation bar.
 *
 * <p>Regression: PROGRESS.md:501 — chrome://home leaked into the URL bar after
 * Chromium started reporting it as the reported onLocationChange URL. All three guards
 * depend on the UrlUtils predicates tested here. If a predicate regresses, every guard
 * that calls it silently stops working.
 *
 * <p>Guard map (where each predicate feeds):
 * <ul>
 *   <li>Guard 1 — Session.onLocationChange:1176 normalizes raw home URL → ABOUT_HOME
 *   <li>Guard 2 — WindowViewModel.setUrl:431 empties resource: / home-uri
 *   <li>Guard 3 — WindowViewModel.mNavigationBarUrlObserver:318 clears nav bar for home URLs
 * </ul>
 *
 * <p>No Robolectric needed — UrlUtils predicates are pure Java (no Android context).
 */
@RunWith(JUnit4.class)
public class SessionHomeUrlGuardTest {

    // ── ABOUT_HOME canonical value ────────────────────────────────────────────

    @Test
    public void ABOUT_HOME_isCanonicalForm() {
        // Guard 1 normalizes every variant to this string.
        // Changing it silently breaks the symbolic URL contract.
        assertEquals("about://home", UrlUtils.ABOUT_HOME);
    }

    @Test
    public void CHROME_HOME_isChromiumVariant() {
        // Guard 3 also checks chrome://home (Chromium reports this as its home page URL).
        assertEquals("chrome://home", UrlUtils.CHROME_HOME);
    }

    // ── isHomeUrl — canonical variant ────────────────────────────────────────

    @Test
    public void isHomeUrl_aboutSlashSlashHome_isTrue() {
        assertTrue(UrlUtils.isHomeUrl("about://home"));
    }

    @Test
    public void isHomeUrl_aboutSlashSlashHome_withQuery_isTrue() {
        // starts-with check — params after the path must still match
        assertTrue(UrlUtils.isHomeUrl("about://home?param=1"));
    }

    @Test
    public void isHomeUrl_aboutSlashSlashHome_uppercase_isTrue() {
        // Guard logic lowercases before comparing — regression pin for case drift
        assertTrue(UrlUtils.isHomeUrl("ABOUT://HOME"));
    }

    // ── isHomeUrl — Chromium variant ─────────────────────────────────────────

    @Test
    public void isHomeUrl_chromeSlashSlashHome_isTrue() {
        // Key regression pin: Chromium reports chrome://home as the onLocationChange URL.
        // If this returns false, the URL leaks into the nav bar (PROGRESS.md:501).
        assertTrue(UrlUtils.isHomeUrl("chrome://home"));
    }

    @Test
    public void isHomeUrl_chromeSlashSlashHome_uppercase_isTrue() {
        assertTrue(UrlUtils.isHomeUrl("CHROME://HOME"));
    }

    // ── isHomeUrl — negative cases ────────────────────────────────────────────

    @Test
    public void isHomeUrl_regularHttps_isFalse() {
        assertFalse(UrlUtils.isHomeUrl("https://example.com"));
    }

    @Test
    public void isHomeUrl_dataUri_isFalse() {
        // data: URIs are NOT home URLs — they must NOT be caught by isHomeUrl.
        // Guard 1 handles data: separately via isDataUri.
        assertFalse(UrlUtils.isHomeUrl("data:text/html;base64,SGVsbG8="));
    }

    @Test
    public void isHomeUrl_null_isFalse() {
        assertFalse(UrlUtils.isHomeUrl(null));
    }

    @Test
    public void isHomeUrl_empty_isFalse() {
        assertFalse(UrlUtils.isHomeUrl(""));
    }

    @Test
    public void isHomeUrl_resourceScheme_isFalse() {
        // resource: URIs are caught by Guard 2's startsWith check, not isHomeUrl
        assertFalse(UrlUtils.isHomeUrl("resource:///android/res/raw/homepage"));
    }

    // ── isDataUri ─────────────────────────────────────────────────────────────

    @Test
    public void isDataUri_homePagePayload_isTrue() {
        // Gecko reports the homepage as a data: URI (base64-encoded HTML).
        // Guard 1 catches it via isDataUri when mOnHomePage is true.
        assertTrue(UrlUtils.isDataUri("data:text/html;base64,SGVsbG8gV29ybGQ="));
    }

    @Test
    public void isDataUri_bareDataScheme_isTrue() {
        assertTrue(UrlUtils.isDataUri("data:,Hello"));
    }

    @Test
    public void isDataUri_aboutHome_isFalse() {
        // about://home must NOT be treated as a data URI — it would bypass normalization
        assertFalse(UrlUtils.isDataUri("about://home"));
    }

    @Test
    public void isDataUri_chromeHome_isFalse() {
        assertFalse(UrlUtils.isDataUri("chrome://home"));
    }

    @Test
    public void isDataUri_httpsUrl_isFalse() {
        assertFalse(UrlUtils.isDataUri("https://example.com"));
    }

    @Test
    public void isDataUri_null_isFalse() {
        assertFalse(UrlUtils.isDataUri(null));
    }
}
