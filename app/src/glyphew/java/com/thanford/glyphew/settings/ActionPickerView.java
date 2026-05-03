/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.settings;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.input.ComboDispatcher;
import com.igalia.wolvic.ui.widgets.ComboHUDWidget;
import com.igalia.wolvic.ui.widgets.combo.ComboTipBuilder;
import com.igalia.wolvic.ui.widgets.dialogs.PromptDialogWidget;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Phase 7 — Action Picker for the FROM_CAPTURE flow.
 *
 * <p>Opens directly (no Settings stack) when the user draws an unbound combo
 * and presses A/X. Shows the drawn path as arrow glyphs, then a tappable
 * radio-button list of currently unassigned actions. User taps an action to
 * select it, then taps Bind to commit.
 *
 * <p>Suspends the combo recognizer for its lifetime and dims the HUD, matching
 * BindComboView behaviour.
 */
public class ActionPickerView extends PromptDialogWidget {

    private static final String LOGTAG = "ActionPickerView";

    private final ComboDispatcher mDispatcher;
    private final int[] mCapturedPath;
    private int mSelectedActionId = -1;

    public ActionPickerView(@NonNull Context ctx,
                            @NonNull ComboDispatcher dispatcher,
                            @NonNull int[] capturedPath) {
        super(ctx);
        mDispatcher = dispatcher;
        mCapturedPath = capturedPath.clone();
        initialize(ctx);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (mCapturedPath == null) return;

        setButtons(new int[]{R.string.cancel_button, R.string.combos_bind_button_bind});
        setButtonsDelegate((index, isChecked) -> {
            if (index == PromptDialogWidget.POSITIVE) onBindClicked();
            else onDismiss();
        });
        setCheckboxVisible(false);
        setDescriptionVisible(false);

        setTitle(getContext().getString(R.string.combos_assign_combo_title));
        renderList();
        setBindEnabled(false);
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

    private void renderList() {
        Context ctx = getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int iconSizePx = (int) (22f * density);
        int accentColor = ContextCompat.getColor(ctx, R.color.gw_accent);
        int textColor = ContextCompat.getColor(ctx, R.color.gw_text);

        SpannableStringBuilder sb = new SpannableStringBuilder();

        // Arrow glyphs for the captured path — visual anchor at the top.
        sb.append(ComboTipBuilder.renderArrowsOnly(ctx, mCapturedPath, iconSizePx, accentColor));
        sb.append("\n\n");

        // Collect action IDs that already have a binding in the active table.
        Map<String, Binding> active = mDispatcher.getAllBindings();
        Set<Integer> assigned = new HashSet<>();
        for (Binding b : active.values()) {
            if (b.action > 0) assigned.add(b.action);
        }

        // Radio rows — unassigned actions only.
        boolean first = true;
        for (int actionId : ComboActionRegistry.knownActions()) {
            if (assigned.contains(actionId)) continue;
            if (!first) sb.append("\n");
            first = false;

            int start = sb.length();
            boolean sel = (actionId == mSelectedActionId);
            sb.append(sel ? "\u25CF  " : "\u25CB  "); // ● or ○
            int labelRes = ComboActionRegistry.labelFor(actionId);
            sb.append(labelRes != 0 ? ctx.getString(labelRes) : "");
            int end = sb.length();

            final int aid = actionId;
            final boolean isSelected = sel;
            sb.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View v) {
                    mSelectedActionId = aid;
                    renderList();
                    setBindEnabled(true);
                }

                @Override
                public void updateDrawState(@NonNull TextPaint ds) {
                    ds.setColor(isSelected ? accentColor : textColor);
                    ds.setUnderlineText(false);
                    if (isSelected) ds.setFakeBoldText(true);
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        setBody(sb);

        // Enable tap handling and style the body TextView for VR readability.
        if (mBinding != null && mBinding.body != null) {
            mBinding.body.setMovementMethod(LinkMovementMethod.getInstance());
            mBinding.body.setHighlightColor(Color.TRANSPARENT);
            mBinding.body.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getContext().getResources().getDimension(R.dimen.gw_text_action_picker_body));
        }
    }

    private void onBindClicked() {
        if (mSelectedActionId <= 0) return;
        mDispatcher.setBinding(mCapturedPath, Binding.of(mSelectedActionId));
        onDismiss();
    }

    private void setBindEnabled(boolean enabled) {
        if (mBinding != null && mBinding.rightButton != null) {
            mBinding.rightButton.setEnabled(enabled);
            mBinding.rightButton.setAlpha(enabled ? 1.0f : 0.4f);
        }
    }

    private void setHudDimmed(boolean dimmed) {
        Context ctx = getContext();
        if (!(ctx instanceof VRBrowserActivity)) return;
        ComboHUDWidget hud = ((VRBrowserActivity) ctx).getComboHUDWidget();
        if (hud != null) hud.setHudDimmed(dimmed);
    }
}
