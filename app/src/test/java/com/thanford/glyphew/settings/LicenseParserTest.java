/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.thanford.glyphew.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.igalia.wolvic.TestApplication;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class LicenseParserTest {

    private static final String WOLVIC_JSON =
            "{\"schema_version\":1,\"entries\":[" +
            "{\"name\":\"Wolvic\"," +
            "\"copyright\":\"Copyright 2022 Igalia S.L.\"," +
            "\"license\":\"Mozilla Public License 2.0\"," +
            "\"license_url\":\"https://www.mozilla.org/en-US/MPL/2.0/\"," +
            "\"source_url\":\"https://github.com/thanfordhq/wolvic\"}" +
            "]}";

    @Test
    public void parse_validJson_returnsList() {
        List<LicenseEntry> entries = LicenseParser.parse(WOLVIC_JSON);
        assertEquals(1, entries.size());
    }

    @Test
    public void parse_wolvicEntry_hasCorrectName() {
        List<LicenseEntry> entries = LicenseParser.parse(WOLVIC_JSON);
        assertEquals("Wolvic", entries.get(0).name);
    }

    @Test
    public void parse_wolvicEntry_hasCorrectLicense() {
        List<LicenseEntry> entries = LicenseParser.parse(WOLVIC_JSON);
        assertEquals("Mozilla Public License 2.0", entries.get(0).license);
    }

    @Test
    public void parse_wolvicEntry_hasLicenseUrl() {
        List<LicenseEntry> entries = LicenseParser.parse(WOLVIC_JSON);
        assertEquals("https://www.mozilla.org/en-US/MPL/2.0/", entries.get(0).licenseUrl);
    }

    @Test
    public void parse_emptyEntries_returnsEmptyList() {
        List<LicenseEntry> entries = LicenseParser.parse(
                "{\"schema_version\":1,\"entries\":[]}");
        assertTrue(entries.isEmpty());
    }

    @Test
    public void parse_invalidJson_returnsEmptyList() {
        List<LicenseEntry> entries = LicenseParser.parse("not-json");
        assertTrue(entries.isEmpty());
    }

    @Test
    public void parse_nullInput_returnsEmptyList() {
        List<LicenseEntry> entries = LicenseParser.parse(null);
        assertTrue(entries.isEmpty());
    }
}
