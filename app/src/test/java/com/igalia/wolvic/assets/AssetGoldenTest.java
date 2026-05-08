/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.assets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * Pins the SHA-256 of Glyphew's signature assets and enforces brand-token invariants.
 *
 * <p>Regression pins:
 * <ul>
 *   <li>Splash / logo carried the Wolvic glyph for 5+ days after the Glyphew rename
 *       (PROGRESS.md:476–482). This test would have caught it at commit time.
 *   <li>FingerDance tokens lingering in homepage.html after the FingerDance→Glyphew rename.
 * </ul>
 *
 * <p>When to update this file: any intentional change to a listed asset requires updating the
 * expected hash here AND in ../glyphew/tools/asset-hashes.json in the SAME PR. The diff makes
 * the asset change reviewable. Changing logo or splash = new Meta Store submission (2-4 weeks).
 *
 * <p>File paths are relative to the Gradle module dir (app/), which is the JVM test working dir.
 */
@RunWith(JUnit4.class)
public class AssetGoldenTest {

    // ── Expected SHA-256 hashes (keep in sync with tools/asset-hashes.json) ──

    private static final String LOGO_SHA256 =
            "1c418bc38f3ff97fb02cd216a93f38e470ae8f68dfe7dd38b2665c4e587ccafc";

    private static final String HOMEPAGE_SHA256 =
            "f542a3790b2cd912de884e2122f48772e553753ece0f13268ec78fc042c5fdae";

    // ── Logo ─────────────────────────────────────────────────────────────────

    @Test
    public void logo_sha256_matches() throws Exception {
        File logo = new File("src/main/assets/logo.png");
        assertTrue("logo.png not found at " + logo.getAbsolutePath(), logo.exists());

        String actual = sha256Hex(Files.readAllBytes(logo.toPath()));
        assertEquals(
                "logo.png hash mismatch — was it accidentally replaced? " +
                "Update LOGO_SHA256 and tools/asset-hashes.json if the change is intentional.",
                LOGO_SHA256, actual);
    }

    // ── Homepage brand invariants ─────────────────────────────────────────────

    @Test
    public void homepage_sha256_matches() throws Exception {
        File hp = new File("src/main/assets-gw-signature/glyphew/homepage.html");
        assertTrue("homepage.html not found at " + hp.getAbsolutePath(), hp.exists());

        String actual = sha256Hex(Files.readAllBytes(hp.toPath()));
        assertEquals(
                "homepage.html hash mismatch — content drifted. " +
                "Update HOMEPAGE_SHA256 and tools/asset-hashes.json if the change is intentional.",
                HOMEPAGE_SHA256, actual);
    }

    @Test
    public void homepage_containsGlyphewBrand() throws Exception {
        String text = readText("src/main/assets-gw-signature/glyphew/homepage.html");
        assertTrue(
                "homepage.html must contain 'Glyphew' — brand rename not applied",
                text.contains("Glyphew"));
    }

    @Test
    public void homepage_noFingerDanceTokens() throws Exception {
        String text = readText("src/main/assets-gw-signature/glyphew/homepage.html");
        assertFalse(
                "homepage.html must not contain 'FingerDance' — stale pre-rename token",
                text.contains("FingerDance"));
        assertFalse(
                "homepage.html must not contain 'fingerdance' — stale pre-rename token",
                text.contains("fingerdance"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static String readText(String relPath) throws Exception {
        File f = new File(relPath);
        assertTrue("File not found: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
