/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.thanford.glyphew.settings;

import androidx.annotation.NonNull;

public final class LicenseEntry {
    @NonNull public final String name;
    @NonNull public final String copyright;
    @NonNull public final String license;
    @NonNull public final String licenseUrl;
    @NonNull public final String sourceUrl;

    public LicenseEntry(@NonNull String name, @NonNull String copyright,
                        @NonNull String license, @NonNull String licenseUrl,
                        @NonNull String sourceUrl) {
        this.name       = name;
        this.copyright  = copyright;
        this.license    = license;
        this.licenseUrl = licenseUrl;
        this.sourceUrl  = sourceUrl;
    }
}
