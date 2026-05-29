/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates the 24h catalog-update check.
 *
 * Call {@link #checkAsync()} from {@code VRBrowserActivity.onCreate()} and
 * {@code onStop()} — both share a single 24h lock so duplicate triggers are
 * free.
 *
 * Network I/O is isolated behind {@link FetchStrategy} so tests can inject
 * a fake implementation without touching the network.
 */
public final class CatalogUpdateManager {

    private static final String TAG   = "CatalogUpdateManager";
    private static final String PREFS = "catalog_update";
    static final String PREF_LAST_CHECK_AT = "last_check_at";
    private static final long CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L; // 24 h

    // ── Injectable interfaces (allow test overrides) ──────────────────────────

    /** Performs the manifest + catalog HTTP fetch. Returns null on failure. */
    public interface FetchStrategy {
        @Nullable FetchResult fetch();
    }

    /** Carries the result of a successful manifest parse. Either field may be null. */
    public static final class FetchResult {
        /** Validated catalog JSON, or null if no catalog update in this manifest. */
        @Nullable public final String catalogJson;
        /** New app version name, or null if no app update announced. */
        @Nullable public final String newAppVersionName;
        /** Horizon Store URL for the new version, or null. */
        @Nullable public final String storeUrl;

        public FetchResult(@Nullable String catalogJson,
                           @Nullable String newAppVersionName,
                           @Nullable String storeUrl) {
            this.catalogJson       = catalogJson;
            this.newAppVersionName = newAppVersionName;
            this.storeUrl          = storeUrl;
        }
    }

    // ── Callbacks (wired to VRBrowserActivity UI helpers) ────────────────────

    public interface StoreCatalogCallback {
        void onCatalogReady(@NonNull String catalogJson);
    }

    public interface CatalogUpdatedCallback {
        void onCatalogUpdated();
    }

    public interface AppUpdateCallback {
        void onAppUpdateAvailable(@NonNull String versionName, @NonNull String storeUrl);
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final SharedPreferences          mPrefs;
    private final CatalogStore               mStore;
    private final StoreCatalogCallback       mStoreCallback;
    private final CatalogUpdatedCallback     mCatalogUpdatedCallback;
    private final AppUpdateCallback          mAppUpdateCallback;
    private final FetchStrategy              mFetchStrategy;
    private final ExecutorService            mExecutor;

    /** Production constructor — uses real OkHttp fetcher. */
    public CatalogUpdateManager(@NonNull Context context,
                                @NonNull StoreCatalogCallback storeCallback,
                                @NonNull CatalogUpdatedCallback catalogUpdatedCallback,
                                @NonNull AppUpdateCallback appUpdateCallback) {
        this(context, storeCallback, catalogUpdatedCallback, appUpdateCallback,
                new RealFetchStrategy(context));
    }

    /** Test constructor — inject a fake FetchStrategy. */
    public CatalogUpdateManager(@NonNull Context context,
                                @NonNull StoreCatalogCallback storeCallback,
                                @NonNull CatalogUpdatedCallback catalogUpdatedCallback,
                                @NonNull AppUpdateCallback appUpdateCallback,
                                @NonNull FetchStrategy fetchStrategy) {
        mPrefs                  = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mStore                  = new CatalogStore(context);
        mStoreCallback          = storeCallback;
        mCatalogUpdatedCallback = catalogUpdatedCallback;
        mAppUpdateCallback      = appUpdateCallback;
        mFetchStrategy          = fetchStrategy;
        mExecutor               = Executors.newSingleThreadExecutor();
    }

    /**
     * Triggers a background check. Returns immediately; result arrives via
     * callbacks on the calling thread (Robolectric) or the background thread
     * (production — callbacks must post to UI thread via Handler).
     */
    public void checkAsync() {
        mExecutor.execute(this::check);
    }

    private void check() {
        long lastCheck = mPrefs.getLong(PREF_LAST_CHECK_AT, 0);
        long now = System.currentTimeMillis();
        if (now - lastCheck < CHECK_INTERVAL_MS) {
            Log.d(TAG, "check: skipped (within 24h window)");
            return;
        }

        FetchResult result = mFetchStrategy.fetch();
        // Always update timestamp after attempting a fetch (success or failure),
        // so a broken server doesn't hammer every cold-start.
        mPrefs.edit().putLong(PREF_LAST_CHECK_AT, now).apply();

        if (result == null) {
            Log.d(TAG, "check: fetch failed or returned nothing");
            return;
        }

        if (result.catalogJson != null && CatalogValidator.isValid(result.catalogJson)) {
            mStore.save(result.catalogJson);
            mStoreCallback.onCatalogReady(result.catalogJson);
            mCatalogUpdatedCallback.onCatalogUpdated();
        }

        if (result.newAppVersionName != null && result.storeUrl != null) {
            mAppUpdateCallback.onAppUpdateAvailable(result.newAppVersionName, result.storeUrl);
        }
    }

    // ── Real network fetch (production only) ─────────────────────────────────

    /**
     * Production implementation using OkHttp (already a Wolvic dependency).
     * Fetches the manifest from thanford.com, validates SHA-256, then fetches
     * the catalog. Assembled here rather than in CatalogUpdateManager to keep
     * the test surface clean.
     */
    private static final class RealFetchStrategy implements FetchStrategy {

        private static final int APP_MAJOR_VERSION = 1;
        private static final String MANIFEST_URL =
                "https://thanford.com/glyphew/update/v" + APP_MAJOR_VERSION + ".json";
        private static final long MAX_CATALOG_BYTES = 64 * 1024; // 64 KB hard cap

        private final Context mContext;

        RealFetchStrategy(@NonNull Context context) {
            mContext = context;
        }

        @Override
        @Nullable
        public FetchResult fetch() {
            try {
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                okhttp3.Request manifestReq = new okhttp3.Request.Builder()
                        .url(MANIFEST_URL)
                        .header("User-Agent", "Glyphew/1.0")
                        .build();

                String manifestJson;
                try (okhttp3.Response resp = client.newCall(manifestReq).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) return null;
                    manifestJson = resp.body().string();
                }

                org.json.JSONObject manifest = new org.json.JSONObject(manifestJson);
                if (manifest.optInt("manifest_version", 0) != 1) return null;

                // Version gate
                int minVersion = manifest.optInt("min_client_version", 0);
                int maxVersion = manifest.optInt("max_client_version", Integer.MAX_VALUE);
                int myVersion  = com.igalia.wolvic.BuildConfig.VERSION_CODE;
                if (myVersion < minVersion || myVersion > maxVersion) return null;

                String catalogJson = null;
                String catalogUrl  = manifest.optString("catalog_url", "");
                String sha256      = manifest.optString("catalog_sha256", "");

                if (!catalogUrl.isEmpty()) {
                    okhttp3.Request catalogReq = new okhttp3.Request.Builder()
                            .url(catalogUrl)
                            .header("User-Agent", "Glyphew/1.0")
                            .build();
                    try (okhttp3.Response resp = client.newCall(catalogReq).execute()) {
                        if (resp.isSuccessful() && resp.body() != null) {
                            long contentLength = resp.body().contentLength();
                            if (contentLength > MAX_CATALOG_BYTES) return null;
                            byte[] bytes = resp.body().bytes();
                            if (bytes.length > MAX_CATALOG_BYTES) return null;
                            // Verify SHA-256
                            java.security.MessageDigest md =
                                    java.security.MessageDigest.getInstance("SHA-256");
                            byte[] digest = md.digest(bytes);
                            StringBuilder sb = new StringBuilder();
                            for (byte b : digest) sb.append(String.format("%02x", b));
                            if (!sb.toString().equals(sha256)) {
                                Log.e(TAG, "SHA-256 mismatch — discarding catalog");
                                return null;
                            }
                            catalogJson = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                            if (!CatalogValidator.isValid(catalogJson)) {
                                Log.e(TAG, "catalog failed validation — discarding");
                                catalogJson = null;
                            }
                        }
                    }
                }

                // App update check
                int latestVersionCode = manifest.optInt("latest_app_version_code", 0);
                String latestVersionName = manifest.optString("latest_app_version_name", "");
                String storeUrl = manifest.optString("store_url", "");
                String newVersionName = null;
                String newStoreUrl = null;
                if (latestVersionCode > myVersion
                        && !latestVersionName.isEmpty()
                        && !storeUrl.isEmpty()) {
                    newVersionName = latestVersionName;
                    newStoreUrl    = storeUrl;
                }

                if (catalogJson == null && newVersionName == null) return null;
                return new FetchResult(catalogJson, newVersionName, newStoreUrl);

            } catch (Exception e) {
                Log.e(TAG, "fetch failed", e);
                return null;
            }
        }
    }
}
