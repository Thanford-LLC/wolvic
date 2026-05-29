/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.home;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Persists the fetched remote catalog to internal storage.
 *
 * Write contract: content is written to a .tmp file first, then renamed
 * atomically to current.json — a partial write can never be observed by
 * {@link #read()}.
 */
public final class CatalogStore {

    private static final String TAG         = "CatalogStore";
    private static final String DIR         = "glyphew/catalog";
    private static final String FILE_NAME   = "current.json";
    private static final String TMP_NAME    = "current.json.tmp";

    private final File mCatalogFile;
    private final File mTmpFile;

    public CatalogStore(@NonNull Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        dir.mkdirs();
        mCatalogFile = new File(dir, FILE_NAME);
        mTmpFile     = new File(dir, TMP_NAME);
    }

    /** Returns the cached catalog JSON string, or null if none is stored. */
    @Nullable
    public String read() {
        if (!mCatalogFile.exists()) return null;
        try (FileInputStream fis = new FileInputStream(mCatalogFile)) {
            byte[] bytes = new byte[(int) mCatalogFile.length()];
            fis.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.e(TAG, "read failed", e);
            return null;
        }
    }

    /**
     * Atomically persists {@code json} to disk.
     * Writes to a .tmp file first, then renames — the rename is the commit point.
     */
    public void save(@NonNull String json) {
        try (FileOutputStream fos = new FileOutputStream(mTmpFile, false)) {
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        } catch (IOException e) {
            Log.e(TAG, "save: write failed", e);
            return;
        }
        if (!mTmpFile.renameTo(mCatalogFile)) {
            Log.e(TAG, "save: rename failed");
        }
    }

    /** Removes the cached catalog. */
    public void clear() {
        mCatalogFile.delete();
        mTmpFile.delete();
    }

    /** Returns true if a previously saved catalog exists on disk. */
    public boolean hasCachedCatalog() {
        return mCatalogFile.exists();
    }
}
