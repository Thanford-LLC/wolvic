/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * Glyphew proprietary. No redistribution without permission. */

package com.igalia.wolvic.utils;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class SeasonUtils {

    public enum Season { SPRING, SUMMER, AUTUMN, WINTER }

    private static final Set<String> SOUTHERN = new HashSet<>(Arrays.asList(
            "AU", "NZ", "AR", "CL", "UY", "PY", "ZA", "BW", "LS", "SZ",
            "ZW", "MZ", "MG", "FJ", "PG"));

    private SeasonUtils() {}

    public static Season currentSeason(Calendar cal, Locale locale) {
        int month = cal.get(Calendar.MONTH);
        if (SOUTHERN.contains(locale.getCountry())) {
            month = (month + 6) % 12;
        }
        if (month >= 2 && month <= 4) return Season.SPRING;
        if (month >= 5 && month <= 7) return Season.SUMMER;
        if (month >= 8 && month <= 10) return Season.AUTUMN;
        return Season.WINTER;
    }

    // Wolvic default forward is +Z (empirically verified 2026-04-18). Seasons authored
    // post-fliplr at: Spring=+Z, Summer=-X, Autumn=-Z, Winter=+X. Yaw rotates the skybox
    // so the current season's face lands at +Z.
    public static float seasonalSkyboxYawRadians(Calendar cal, Locale locale) {
        switch (currentSeason(cal, locale)) {
            case SPRING: return 0f;
            case SUMMER: return (float) (Math.PI / 2.0);
            case AUTUMN: return (float) Math.PI;
            case WINTER: return (float) (-Math.PI / 2.0);
        }
        return 0f;
    }
}
