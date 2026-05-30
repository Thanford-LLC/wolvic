/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.igalia.wolvic.browser.api;

import static org.junit.Assert.assertEquals;

import com.igalia.wolvic.ui.widgets.menus.VideoProjectionMenuWidget;

import org.junit.Test;

/**
 * Pure-logic tests for mapping detected spherical-video container metadata
 * (projection type + stereo mode, parsed from MP4 st3d/sv3d boxes in Chromium)
 * to Wolvic's native VR-video projection constants.
 *
 * No Android dependencies: VideoProjectionMenuWidget's VIDEO_PROJECTION_* values
 * are compile-time int constants and inline at the call site, so this runs on the
 * plain JVM without loading the (Android UI) widget class.
 */
public class SphericalVideoProjectionTest {

    @Test
    public void equirectangularMonoMapsTo360() {
        assertEquals(
                VideoProjectionMenuWidget.VIDEO_PROJECTION_360,
                SphericalVideoProjection.toVideoProjection(
                        SphericalVideoProjection.TYPE_EQUIRECTANGULAR,
                        SphericalVideoProjection.STEREO_MONO));
    }

    @Test
    public void equirectangularTopBottomMapsTo360Stereo() {
        assertEquals(
                VideoProjectionMenuWidget.VIDEO_PROJECTION_360_STEREO,
                SphericalVideoProjection.toVideoProjection(
                        SphericalVideoProjection.TYPE_EQUIRECTANGULAR,
                        SphericalVideoProjection.STEREO_TOP_BOTTOM));
    }

    @Test
    public void cubemapMapsToVideoCubemap() {
        assertEquals(
                VideoProjectionMenuWidget.VIDEO_PROJECTION_CUBEMAP,
                SphericalVideoProjection.toVideoProjection(
                        SphericalVideoProjection.TYPE_CUBEMAP,
                        SphericalVideoProjection.STEREO_MONO));
    }

    @Test
    public void meshMapsToVideoMesh() {
        assertEquals(
                VideoProjectionMenuWidget.VIDEO_PROJECTION_MESH,
                SphericalVideoProjection.toVideoProjection(
                        SphericalVideoProjection.TYPE_MESH,
                        SphericalVideoProjection.STEREO_MONO));
    }

    @Test
    public void cubemapNoLongerReportedAsUnsupported() {
        assertEquals(false,
                SphericalVideoProjection.isSphericalButUnsupported(
                        SphericalVideoProjection.TYPE_CUBEMAP));
    }

    @Test
    public void meshNoLongerReportedAsUnsupported() {
        assertEquals(false,
                SphericalVideoProjection.isSphericalButUnsupported(
                        SphericalVideoProjection.TYPE_MESH));
    }

    // --- Aspect-ratio fallback (used only when no spherical metadata is present) ---

    @Test
    public void aspectRatio2to1ClassifiesAs360() {
        // 4096x2048 = 2:1, the canonical equirectangular mono layout.
        assertEquals(
                VideoProjectionMenuWidget.VIDEO_PROJECTION_360,
                SphericalVideoProjection.classifyByAspectRatio(4096, 2048));
    }

    @Test
    public void aspectRatio4to1ClassifiesAs360Stereo() {
        // 8192x2048 = 4:1, side-by-side stereo equirectangular (two 2:1 eyes).
        assertEquals(
                VideoProjectionMenuWidget.VIDEO_PROJECTION_360_STEREO,
                SphericalVideoProjection.classifyByAspectRatio(8192, 2048));
    }

    @Test
    public void aspectRatio16to9ClassifiesAsNone() {
        // Ordinary flat video must NOT be auto-projected.
        assertEquals(
                VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE,
                SphericalVideoProjection.classifyByAspectRatio(1920, 1080));
    }

    @Test
    public void aspectRatio1to1ClassifiesAs360Stereo() {
        // 4096x4096 = 1:1, top-bottom stereo equirectangular (two 2:1 eyes stacked).
        assertEquals(
                VideoProjectionMenuWidget.VIDEO_PROJECTION_360_STEREO,
                SphericalVideoProjection.classifyByAspectRatio(4096, 4096));
    }

    // --- Filename hints (DeoVR/HereSphere conventions) for sideloaded files ---

    @Test
    public void filename360MonoMapsTo360() {
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_360,
                SphericalVideoProjection.classifyByFilename("https://x.com/underwater_360.mp4"));
    }

    @Test
    public void filename360TopBottomMapsTo360Stereo() {
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_360_STEREO,
                SphericalVideoProjection.classifyByFilename("https://x.com/concert_360_tb.mp4"));
    }

    @Test
    public void filename180LeftRightMapsTo180StereoLR() {
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_180_STEREO_LEFT_RIGHT,
                SphericalVideoProjection.classifyByFilename("https://x.com/movie_180_lr.mp4"));
    }

    @Test
    public void filenameSbsNoFovMapsToFlat3D() {
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_3D_SIDE_BY_SIDE,
                SphericalVideoProjection.classifyByFilename("https://x.com/film_sbs.mp4"));
    }

    @Test
    public void filenameFisheyeIsUnsupportedNone() {
        // Wolvic can't render fisheye — must not mis-project it.
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE,
                SphericalVideoProjection.classifyByFilename("https://x.com/scene_fisheye190.mp4"));
    }

    @Test
    public void youtubeWatchUrlWith360InIdIsNotFilenameMatched() {
        // Not a media-file URL → filename heuristic must not fire (avoid false positives).
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE,
                SphericalVideoProjection.classifyByFilename("https://www.youtube.com/watch?v=ab360cd"));
    }

    @Test
    public void plainVideoFileIsNone() {
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE,
                SphericalVideoProjection.classifyByFilename("https://x.com/vacation.mp4"));
    }

    // --- chooseProjection: precedence (metadata > URL hint > aspect-ratio fallback) ---
    // urlProjection is the already-resolved VideoProjectionMenuWidget.getAutomaticProjection
    // result, passed in so this stays pure (no Android Uri parsing).

    @Test
    public void metadataEquirectOverridesFlatAspectRatio() {
        // Authoritative container metadata says equirectangular; the frame happens to be
        // 16:9 (e.g. cropped/letterboxed) — metadata must win over the aspect heuristic.
        int chosen = SphericalVideoProjection.chooseProjection(
                SphericalVideoProjection.TYPE_EQUIRECTANGULAR,
                SphericalVideoProjection.STEREO_MONO,
                VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE, // no URL hint
                null,                                            // no filename
                1920, 1080);                                     // flat aspect
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_360, chosen);
    }

    @Test
    public void noMetadataFallsBackToUrlHint() {
        int chosen = SphericalVideoProjection.chooseProjection(
                SphericalVideoProjection.TYPE_RECTANGULAR,  // no spherical metadata
                SphericalVideoProjection.STEREO_MONO,
                VideoProjectionMenuWidget.VIDEO_PROJECTION_180, // URL said 180
                null,
                0, 0);                                          // no dimensions
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_180, chosen);
    }

    @Test
    public void noMetadataNoUrlFallsBackToAspectRatio() {
        int chosen = SphericalVideoProjection.chooseProjection(
                SphericalVideoProjection.TYPE_RECTANGULAR,
                SphericalVideoProjection.STEREO_MONO,
                VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE, // no URL hint
                null,
                4096, 2048);                                     // 2:1 → 360
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_360, chosen);
    }

    // --- VR180 (half-equirectangular) ---

    @Test
    public void equirect180MonoMapsTo180() {
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_180,
                SphericalVideoProjection.toVideoProjection(
                        SphericalVideoProjection.TYPE_EQUIRECTANGULAR_180,
                        SphericalVideoProjection.STEREO_MONO));
    }

    @Test
    public void equirect180LeftRightMapsTo180StereoLR() {
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_180_STEREO_LEFT_RIGHT,
                SphericalVideoProjection.toVideoProjection(
                        SphericalVideoProjection.TYPE_EQUIRECTANGULAR_180,
                        SphericalVideoProjection.STEREO_LEFT_RIGHT));
    }

    @Test
    public void equirect180TopBottomMapsTo180StereoTB() {
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_180_STEREO_TOP_BOTTOM,
                SphericalVideoProjection.toVideoProjection(
                        SphericalVideoProjection.TYPE_EQUIRECTANGULAR_180,
                        SphericalVideoProjection.STEREO_TOP_BOTTOM));
    }

    // --- Flat stereoscopic 3D (rectangular projection + stereo layout) ---

    @Test
    public void flatTopBottomMapsTo3DTopBottom() {
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_3D_TOP_BOTTOM,
                SphericalVideoProjection.toVideoProjection(
                        SphericalVideoProjection.TYPE_RECTANGULAR,
                        SphericalVideoProjection.STEREO_TOP_BOTTOM));
    }

    @Test
    public void flatSideBySideMapsTo3DSideBySide() {
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_3D_SIDE_BY_SIDE,
                SphericalVideoProjection.toVideoProjection(
                        SphericalVideoProjection.TYPE_RECTANGULAR,
                        SphericalVideoProjection.STEREO_LEFT_RIGHT));
    }

    @Test
    public void flatMonoStaysNone() {
        // Ordinary 2D video (rectangular + mono) must not be projected.
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE,
                SphericalVideoProjection.toVideoProjection(
                        SphericalVideoProjection.TYPE_RECTANGULAR,
                        SphericalVideoProjection.STEREO_MONO));
    }

    @Test
    public void cubemapMetadataAutoEntersCubemapProjection() {
        // Milestone 2: EAC/cubemap metadata is now renderable — returns CUBEMAP, not NONE.
        int chosen = SphericalVideoProjection.chooseProjection(
                SphericalVideoProjection.TYPE_CUBEMAP,
                SphericalVideoProjection.STEREO_MONO,
                VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE,
                null,
                4096, 2048);
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_CUBEMAP, chosen);
    }

    @Test
    public void meshMetadataAutoEntersMeshProjection() {
        int chosen = SphericalVideoProjection.chooseProjection(
                SphericalVideoProjection.TYPE_MESH,
                SphericalVideoProjection.STEREO_MONO,
                VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE,
                null,
                0, 0);
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_MESH, chosen);
    }

    @Test
    public void filenameHintUsedWhenNoMetadataOrUrlParam() {
        int chosen = SphericalVideoProjection.chooseProjection(
                SphericalVideoProjection.TYPE_RECTANGULAR,
                SphericalVideoProjection.STEREO_MONO,
                VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE,
                "https://x.com/clip_360.mp4",                    // filename says 360
                0, 0);
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_360, chosen);
    }

    @Test
    public void metadataBeatsFilenameHint() {
        // Metadata (VR180 stereo) must win even if the filename says 360.
        int chosen = SphericalVideoProjection.chooseProjection(
                SphericalVideoProjection.TYPE_EQUIRECTANGULAR_180,
                SphericalVideoProjection.STEREO_LEFT_RIGHT,
                VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE,
                "https://x.com/clip_360.mp4",
                0, 0);
        assertEquals(VideoProjectionMenuWidget.VIDEO_PROJECTION_180_STEREO_LEFT_RIGHT, chosen);
    }
}
