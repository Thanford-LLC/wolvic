/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary FingerDance component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.fingerdance.settings;

import android.content.Context;

import androidx.annotation.NonNull;

import com.igalia.wolvic.R;
import com.igalia.wolvic.ui.widgets.dialogs.PromptDialogWidget;

/**
 * Phase 5 cherry-pick — confirm dialog for the Reset-to-defaults footer.
 *
 * <p>Reset is destructive across every user-customised combo: it wipes the
 * override blob so {@link ComboBindingStore#load} returns empty on next read,
 * which the dispatcher's pref listener observes and uses to rebuild from
 * hard-coded defaults. Without this confirm, a mis-click on the footer
 * annihilates hours of rebinding work.
 *
 * <p>Mirrors {@link ComboUnbindConfirmDialog}'s pattern: two buttons
 * [Cancel, Reset all]; POSITIVE → {@link ComboBindingStore#clearAll}; NEGATIVE
 * / BACK → silent dismiss. No A_REMOVED sentinel write here — clearAll()
 * nukes the whole blob including sentinels, which is the correct semantics for
 * "restore defaults." Toggle preferences (mode, HUD, buzz) are panel-level and
 * deliberately untouched per {@link CombosSettingsView}'s footer comment.
 */
public class ComboResetConfirmDialog extends PromptDialogWidget {

    public ComboResetConfirmDialog(@NonNull Context ctx) {
        super(ctx);
        initialize(ctx);
    }

    @Override
    public void updateUI() {
        super.updateUI();

        setButtons(new int[] {
                R.string.cancel_button,
                R.string.combos_reset_confirm_positive
        });
        setButtonsDelegate((index, isChecked) -> {
            if (index == PromptDialogWidget.POSITIVE) {
                new ComboBindingStore(getContext()).clearAll();
            }
            onDismiss();
        });
        setCheckboxVisible(false);
        setDescriptionVisible(false);

        setTitle(getContext().getString(R.string.combos_reset_confirm_title));
        setBody(getContext().getString(R.string.combos_reset_confirm_body));
    }
}
