package com.igalia.wolvic.browser.api;

import com.igalia.wolvic.ui.widgets.menus.VideoProjectionMenuWidget;

/**
 * Maps spherical-video container metadata — the projection type and stereo mode
 * parsed from MP4 {@code st3d}/{@code sv3d} boxes in Chromium's demuxer — to
 * Wolvic's native VR-video projection constants.
 *
 * <p>The integer codes mirror the native {@code VideoProjectionType} and
 * {@code VideoStereoMode} enums surfaced over the {@code mediaProjectionChanged}
 * JNI callback. Pure logic, no Android dependencies.
 */
public final class SphericalVideoProjection {

    // Mirrors native VideoProjectionType ordinals.
    public static final int TYPE_RECTANGULAR = 0;
    public static final int TYPE_EQUIRECTANGULAR = 1;
    public static final int TYPE_CUBEMAP = 2;
    public static final int TYPE_MESH = 3;
    public static final int TYPE_EQUIRECTANGULAR_180 = 4;  // VR180 half-equirect

    // Mirrors native VideoStereoMode ordinals.
    public static final int STEREO_MONO = 0;
    public static final int STEREO_TOP_BOTTOM = 1;
    public static final int STEREO_LEFT_RIGHT = 2;

    private SphericalVideoProjection() {}

    /**
     * @return the {@code VideoProjectionMenuWidget.VIDEO_PROJECTION_*} constant to
     *         auto-enter for this metadata signal, or {@code VIDEO_PROJECTION_NONE}
     *         if the content is not a natively-renderable spherical video.
     */
    public static int toVideoProjection(int projectionType, int stereoMode) {
        boolean stereo = (stereoMode == STEREO_TOP_BOTTOM || stereoMode == STEREO_LEFT_RIGHT);
        switch (projectionType) {
            case TYPE_EQUIRECTANGULAR:
                return stereo ? VideoProjectionMenuWidget.VIDEO_PROJECTION_360_STEREO
                              : VideoProjectionMenuWidget.VIDEO_PROJECTION_360;
            case TYPE_EQUIRECTANGULAR_180:
                if (stereoMode == STEREO_TOP_BOTTOM)
                    return VideoProjectionMenuWidget.VIDEO_PROJECTION_180_STEREO_TOP_BOTTOM;
                if (stereoMode == STEREO_LEFT_RIGHT)
                    return VideoProjectionMenuWidget.VIDEO_PROJECTION_180_STEREO_LEFT_RIGHT;
                return VideoProjectionMenuWidget.VIDEO_PROJECTION_180;
            case TYPE_RECTANGULAR:
                // Flat (non-spherical) stereoscopic 3D video.
                if (stereoMode == STEREO_TOP_BOTTOM)
                    return VideoProjectionMenuWidget.VIDEO_PROJECTION_3D_TOP_BOTTOM;
                if (stereoMode == STEREO_LEFT_RIGHT)
                    return VideoProjectionMenuWidget.VIDEO_PROJECTION_3D_SIDE_BY_SIDE;
                return VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE;
            default:  // cubemap / mesh — not natively renderable
                return VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE;
        }
    }

    /**
     * @return true if the content declares a spherical projection that Wolvic
     *         cannot render natively yet (cubemap / mesh, e.g. YouTube's EAC).
     *         The caller should log this for the EAC follow-up rather than
     *         garble-rendering it as equirectangular.
     */
    public static boolean isSphericalButUnsupported(int projectionType) {
        return projectionType == TYPE_CUBEMAP || projectionType == TYPE_MESH;
    }

    /**
     * Fallback heuristic for content that carries NO spherical metadata: infer a
     * projection from the video's pixel aspect ratio. Equirectangular mono is 2:1.
     *
     * @return a {@code VIDEO_PROJECTION_*} constant, or {@code VIDEO_PROJECTION_NONE}
     *         if the dimensions don't match a known spherical layout (treat as flat).
     */
    public static int classifyByAspectRatio(long width, long height) {
        if (width <= 0 || height <= 0) {
            return VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE;
        }
        double ratio = (double) width / (double) height;
        if (ratio >= 1.8 && ratio <= 2.2) {
            return VideoProjectionMenuWidget.VIDEO_PROJECTION_360;
        }
        if (ratio >= 3.6 && ratio <= 4.4) {
            return VideoProjectionMenuWidget.VIDEO_PROJECTION_360_STEREO;
        }
        if (ratio >= 0.85 && ratio <= 1.15) {
            return VideoProjectionMenuWidget.VIDEO_PROJECTION_360_STEREO;
        }
        return VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE;
    }

    /**
     * Infers projection from the de-facto VR filename conventions used by sideloaded
     * players (DeoVR / HereSphere): {@code _360}/{@code _180} field-of-view tokens and
     * {@code _SBS}/{@code _LR}/{@code _TB}/{@code _OU}/{@code 3DH}/{@code 3DV} stereo
     * tokens. Only applies to direct media-file URLs (so web pages whose IDs happen to
     * contain "360"/"180" don't false-positive). Fisheye lenses are detected but return
     * NONE since Wolvic can't render them.
     */
    public static int classifyByFilename(String url) {
        if (url == null) return VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE;
        String s = url.toLowerCase();
        int q = s.indexOf('?'); if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#'); if (h >= 0) s = s.substring(0, h);
        if (!(s.endsWith(".mp4") || s.endsWith(".webm") || s.endsWith(".mkv")
                || s.endsWith(".mov") || s.endsWith(".m4v"))) {
            return VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE;
        }
        int slash = s.lastIndexOf('/');
        String name = slash >= 0 ? s.substring(slash + 1) : s;

        // Fisheye / lens-FOV codes — not natively renderable.
        if (name.contains("fisheye") || name.contains("mkx200") || name.contains("vrca220")
                || name.contains("rf52") || name.contains("_f190") || name.contains("_f180")) {
            return VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE;
        }

        boolean fov360 = name.contains("360");
        boolean fov180 = !fov360 && name.contains("180");

        int stereo = STEREO_MONO;
        if (name.contains("sbs") || name.contains("_lr") || name.contains("3dh")) {
            stereo = STEREO_LEFT_RIGHT;
        } else if (name.contains("_tb") || name.contains("_ou") || name.contains("3dv")
                || name.contains("overunder")) {
            stereo = STEREO_TOP_BOTTOM;
        }

        if (fov360) return toVideoProjection(TYPE_EQUIRECTANGULAR, stereo);
        if (fov180) return toVideoProjection(TYPE_EQUIRECTANGULAR_180, stereo);
        if (stereo != STEREO_MONO) return toVideoProjection(TYPE_RECTANGULAR, stereo);
        return VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE;
    }

    /**
     * Resolves the projection to auto-enter at fullscreen, in priority order:
     * <ol>
     *   <li>authoritative container metadata (equirectangular) — always wins;
     *   <li>spherical-but-unsupported metadata (cubemap/mesh) — return NONE so we
     *       neither garble-render nor fall through to a misleading heuristic;
     *   <li>URL hint ({@code mozVideoProjection}, already resolved by the caller);
     *   <li>aspect-ratio fallback for untagged content.
     * </ol>
     *
     * @param urlProjection the {@code VideoProjectionMenuWidget.getAutomaticProjection}
     *                      result, or {@code VIDEO_PROJECTION_NONE} if absent.
     */
    public static int chooseProjection(int metaProjectionType, int metaStereoMode,
                                       int urlProjection, String url,
                                       long width, long height) {
        // 1. Authoritative container metadata (equirect 360/180, flat-3D stereo).
        int metaResult = toVideoProjection(metaProjectionType, metaStereoMode);
        if (metaResult != VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE) {
            return metaResult;
        }
        // 2. Spherical but not natively renderable (cubemap/mesh, e.g. EAC) — never
        //    garble-render and never fall through to a misleading heuristic.
        if (isSphericalButUnsupported(metaProjectionType)) {
            return VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE;
        }
        // 3. mozVideoProjection URL parameter (already resolved by the caller).
        if (urlProjection != VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE) {
            return urlProjection;
        }
        // 4. Filename convention for sideloaded direct media files.
        int fileHint = classifyByFilename(url);
        if (fileHint != VideoProjectionMenuWidget.VIDEO_PROJECTION_NONE) {
            return fileHint;
        }
        // 5. Pixel aspect-ratio fallback for untagged content.
        return classifyByAspectRatio(width, height);
    }
}
