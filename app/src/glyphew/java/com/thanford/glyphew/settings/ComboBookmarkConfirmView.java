/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.settings;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableStringBuilder;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.browser.BookmarksStore;
import com.igalia.wolvic.browser.engine.SessionStore;
import com.igalia.wolvic.input.ComboDispatcher;
import com.igalia.wolvic.ui.widgets.ComboHUDWidget;
import com.igalia.wolvic.ui.widgets.combo.ComboTipBuilder;
import com.igalia.wolvic.ui.widgets.dialogs.PromptDialogWidget;

/**
 * Phase 8b — confirm screen for FROM_CAPTURE on a non-Homepage URL.
 *
 * <p>Shown when the user draws an unbound path on a real website and presses A/X.
 * Displays the captured path arrows + site title/host and offers two buttons:
 * Cancel (NEGATIVE) | Save (POSITIVE).
 *
 * <p>On POSITIVE: atomically creates a Combo Bookmarks folder entry and writes
 * the A_GOTO_BOOKMARK binding to the combo store.
 *
 * <p>Mirrors {@link ActionPickerView}: calls {@link ComboDispatcher#setCaptureMode}
 * and dims the HUD for its lifetime so the recognizer stays suspended.
 */
public class ComboBookmarkConfirmView extends PromptDialogWidget {

    private final ComboDispatcher mDispatcher;
    private final int[] mCapturedPath;
    private final String mCurrentUrl;
    private final String mCurrentTitle;

    public ComboBookmarkConfirmView(@NonNull Context ctx,
                                    @NonNull ComboDispatcher dispatcher,
                                    @NonNull int[] capturedPath,
                                    @NonNull String currentUrl,
                                    @NonNull String currentTitle) {
        super(ctx);
        mDispatcher = dispatcher;
        mCapturedPath = capturedPath.clone();
        mCurrentUrl = currentUrl;
        mCurrentTitle = currentTitle;
        // Same two-phase init as ClearUserDataDialogWidget / ComboUnbindConfirmDialog.
        initialize(ctx);
    }

    @Override
    public void updateUI() {
        super.updateUI();

        setButtons(new int[]{
                R.string.cancel_button,
                R.string.gw_combo_bookmark_confirm_save
        });
        setButtonsDelegate((index, isChecked) -> {
            if (index == PromptDialogWidget.POSITIVE && mDispatcher != null && mCapturedPath != null) {
                saveComboBookmark();
            }
            onDismiss();
        });
        setCheckboxVisible(false);
        setDescriptionVisible(false);

        setTitle(getContext().getString(R.string.gw_combo_bookmark_confirm_title));
        if (mCapturedPath != null) {
            setBody(buildBody());
        }
    }

    @Override
    public void show(@ShowFlags int aShowFlags) {
        super.show(aShowFlags);
        if (mDispatcher != null) mDispatcher.setCaptureMode(true, null);
        setHudDimmed(true);
    }

    @Override
    public void onDismiss() {
        if (mDispatcher != null) mDispatcher.setCaptureMode(false, null);
        setHudDimmed(false);
        super.onDismiss();
    }

    private void saveComboBookmark() {
        SessionStore ss = SessionStore.get();
        if (ss == null) return;
        BookmarksStore store = ss.getBookmarkStore();
        if (store == null) return;

        store.ensureComboBookmarksFolder()
                .thenCompose(folderGuid ->
                        store.addBookmarkReturningGuid(folderGuid, mCurrentUrl, mCurrentTitle))
                .thenAccept(bookmarkGuid -> {
                    Binding binding = Binding.of(ComboDispatcher.A_GOTO_BOOKMARK, bookmarkGuid);
                    mDispatcher.setBinding(mCapturedPath, binding);
                });
    }

    private CharSequence buildBody() {
        Context ctx = getContext();
        int iconSizePx = (int) (22f * ctx.getResources().getDisplayMetrics().density);
        int chipColor = ContextCompat.getColor(ctx, R.color.gw_accent);
        Spannable arrows = ComboTipBuilder.renderArrowsOnly(ctx, mCapturedPath, iconSizePx, chipColor);

        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(arrows);
        sb.append("\n\n");
        if (mCurrentTitle != null) sb.append(mCurrentTitle);
        if (mCurrentUrl != null) {
            android.net.Uri parsed = android.net.Uri.parse(mCurrentUrl);
            String host = parsed.getHost();
            if (host != null) {
                sb.append("\n");
                sb.append(host);
            }
        }
        return sb;
    }

    private void setHudDimmed(boolean dimmed) {
        if (!(getContext() instanceof VRBrowserActivity)) return;
        ComboHUDWidget hud = ((VRBrowserActivity) getContext()).getComboHUDWidget();
        if (hud != null) hud.setHudDimmed(dimmed);
    }
}
