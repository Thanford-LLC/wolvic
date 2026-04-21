/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary FingerDance component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.fingerdance.settings;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableStringBuilder;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.igalia.wolvic.R;
import com.igalia.wolvic.input.ComboDispatcher;
import com.igalia.wolvic.ui.widgets.combo.ComboTipBuilder;
import com.igalia.wolvic.ui.widgets.dialogs.PromptDialogWidget;

/**
 * Phase 4 — confirm dialog for the chip-X unbind action.
 *
 * <p>Mirrors {@code ClearUserDataDialogWidget}'s 49-line PromptDialogWidget
 * extension: {@code setTitle} + {@code setBody} + two buttons [Cancel, Unbind].
 * The body renders the combo's arrow path via {@link ComboTipBuilder#renderArrowsOnly}
 * followed by the action label so users can confirm WHICH combo they're removing
 * before the destructive {@link ComboDispatcher#removeBinding} call.
 *
 * <p>POSITIVE → persist an {@code A_REMOVED} sentinel at this path via the
 * dispatcher; the blob-listener spine then fires {@link ComboDispatcher.BindingsListener}
 * which {@code CombosSettingsView} routes to a full list rebuild. NEGATIVE /
 * BACK → silent dismiss.
 */
public class ComboUnbindConfirmDialog extends PromptDialogWidget {

    private final ComboDispatcher mDispatcher;
    private final int[] mPath;
    private final int mActionInt;

    public ComboUnbindConfirmDialog(@NonNull Context ctx,
                                    @NonNull ComboDispatcher dispatcher,
                                    @NonNull int[] path,
                                    int actionInt) {
        super(ctx);
        mDispatcher = dispatcher;
        mPath = path;
        mActionInt = actionInt;
        initialize(ctx);
    }

    @Override
    public void updateUI() {
        super.updateUI();

        setButtons(new int[] {
                R.string.cancel_button,
                R.string.combos_delete_confirm_positive
        });
        setButtonsDelegate((index, isChecked) -> {
            if (index == PromptDialogWidget.POSITIVE) {
                mDispatcher.removeBinding(mPath);
            }
            onDismiss();
        });
        setCheckboxVisible(false);
        setDescriptionVisible(false);

        setTitle(getContext().getString(R.string.combos_delete_confirm_title));
        setBody(buildBody());
    }

    private CharSequence buildBody() {
        Context ctx = getContext();
        int iconSizePx = (int) (22f * ctx.getResources().getDisplayMetrics().density);
        int chipColor = ContextCompat.getColor(ctx, R.color.fd_accent);
        Spannable arrows = ComboTipBuilder.renderArrowsOnly(ctx, mPath, iconSizePx, chipColor);

        int labelRes = ComboActionRegistry.labelFor(mActionInt);
        String actionLabel = (labelRes != 0) ? ctx.getString(labelRes) : "";

        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(arrows);
        sb.append("  →  ");
        sb.append(actionLabel);
        sb.append("\n\n");
        sb.append(ctx.getString(R.string.combos_delete_confirm_body));
        return sb;
    }
}
