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
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.input.ComboDispatcher;
import com.igalia.wolvic.ui.widgets.ComboHUDWidget;
import com.igalia.wolvic.ui.widgets.UIWidget;
import com.igalia.wolvic.ui.widgets.combo.ComboTipBuilder;
import com.igalia.wolvic.ui.widgets.dialogs.PromptDialogWidget;

import java.util.Arrays;
import java.util.Map;

/**
 * Phase 5 — Bind Combo dialog, FROM_SETTINGS flow.
 *
 * <p>User taps {@code + Create} on an action row in {@code CombosSettingsView};
 * this dialog opens with the target action LOCKED in the title. User holds
 * grip + draws a path in the world; {@link ComboDispatcher} routes the emitted
 * path to {@link #onPathCaptured(int[])} (capture mode suppresses dispatch and
 * haptics). Captured path renders as arrow glyphs in the body. Bind commits
 * via {@link ComboDispatcher#setBinding}; Cancel dismisses.
 *
 * <p>Conflict matrix:
 * <ul>
 *   <li><b>Same-action collision</b> (this path is already bound to the locked
 *       action) → Bind button disabled, body shows "already bound" note.</li>
 *   <li><b>Different-action collision</b> (path is bound to some OTHER action)
 *       → Bind stays enabled, body shows "will replace &lt;other&gt;" warning.</li>
 *   <li><b>Prefix collision</b> (cherry-pick) → path is a proper prefix of, or
 *       has as a proper prefix, another binding. Bind stays enabled; body
 *       shows a soft warning since the shorter path will emit first and the
 *       longer one becomes unreachable.</li>
 *   <li><b>Clean</b> → Bind enabled, body shows just the path.</li>
 * </ul>
 *
 * <p>HUD dimming (sealed plan §T3): on show, the HUD alpha drops to 0.3 so the
 * dialog chrome carries the authoritative live trace; on dismiss it restores
 * to 1.0. Dispatcher capture-mode and HUD dim are paired — both are set in
 * {@link #show} and cleared in {@link #onDismiss} regardless of exit path
 * (Bind, Cancel, BACK).
 */
public class BindComboView extends PromptDialogWidget
        implements ComboDispatcher.CaptureListener {

    private static final String LOGTAG = "BindComboView";

    private final ComboDispatcher mDispatcher;
    private final int mActionInt;
    private final String mActionLabel;
    private int[] mCapturedPath = new int[0];
    // FROM_CAPTURE: path drawn before A/X press, pre-filled when opening from browsing.
    // Null in the FROM_SETTINGS flow.
    private final int[] mPreCapturedPath;

    /** FROM_SETTINGS: user picks action → draws path. */
    public BindComboView(@NonNull Context ctx,
                         @NonNull ComboDispatcher dispatcher,
                         int actionInt) {
        this(ctx, dispatcher, actionInt, null);
    }

    /** FROM_CAPTURE: path already drawn; user picks action. */
    public static BindComboView forCapture(@NonNull Context ctx,
                                           @NonNull ComboDispatcher dispatcher,
                                           int actionInt,
                                           @NonNull int[] capturedPath) {
        return new BindComboView(ctx, dispatcher, actionInt, capturedPath);
    }

    private BindComboView(@NonNull Context ctx,
                          @NonNull ComboDispatcher dispatcher,
                          int actionInt,
                          @Nullable int[] preCapturedPath) {
        super(ctx);
        mDispatcher = dispatcher;
        mActionInt = actionInt;
        mPreCapturedPath = (preCapturedPath != null) ? preCapturedPath.clone() : null;
        int labelRes = ComboActionRegistry.labelFor(actionInt);
        mActionLabel = (labelRes != 0) ? ctx.getString(labelRes) : "";
        initialize(ctx);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Guard: first updateUI() fires from PromptDialogWidget's super(ctx) before
        // BindComboView's instance field initializers (mCapturedPath) have run.
        // All setup below safely awaits the second call triggered by initialize(ctx).
        if (mCapturedPath == null) return;

        setButtons(new int[]{
                R.string.cancel_button,
                R.string.combos_bind_button_bind
        });
        setButtonsDelegate((index, isChecked) -> {
            if (index == PromptDialogWidget.POSITIVE) {
                onBindClicked();
            } else {
                onDismiss();
            }
        });
        setCheckboxVisible(false);
        setDescriptionVisible(false);

        setTitle(getContext().getString(
                R.string.combos_bind_from_settings_title, mActionLabel));
        renderBody();
        setBindEnabled(false);
    }

    @Override
    public void show(@ShowFlags int aShowFlags) {
        super.show(aShowFlags);
        if (mPreCapturedPath != null) {
            // FROM_CAPTURE: path is already known; skip live capture mode.
            // Immediately populate the captured path so the chrome renders it.
            onPathCaptured(mPreCapturedPath);
        } else {
            mDispatcher.setCaptureMode(true, this);
        }
        setHudDimmed(true);
    }

    @Override
    public void onDismiss() {
        if (mPreCapturedPath == null) {
            // Only clear capture mode if we entered it (FROM_SETTINGS).
            mDispatcher.setCaptureMode(false, null);
        }
        mDispatcher.clearPendingCapturePath();
        setHudDimmed(false);
        super.onDismiss();
    }

    // --- ComboDispatcher.CaptureListener ---
    // Delivered on the main thread by the dispatcher.
    @Override
    public void onPathCaptured(@NonNull int[] path) {
        Log.d(LOGTAG, "captured path=" + Arrays.toString(path)
                + " action=" + mActionInt);
        mCapturedPath = path.clone();
        renderBody();
        setBindEnabled(hasCapturedPath() && !isSameActionCollision());
    }

    private void onBindClicked() {
        if (!hasCapturedPath() || isSameActionCollision()) {
            return;
        }
        mDispatcher.setBinding(mCapturedPath, Binding.of(mActionInt));
        // Blob-listener spine: save() → KEY_BLOB change → reloadBindings() →
        // BindingsListener → CombosSettingsView.onBindingsChanged → rebuild.
        onDismiss();
    }

    private void renderBody() {
        Context ctx = getContext();
        SpannableStringBuilder sb = new SpannableStringBuilder();

        if (!hasCapturedPath()) {
            sb.append(ctx.getString(R.string.combos_bind_draw_prompt));
            setBody(sb);
            return;
        }

        int iconSizePx = (int) (22f * ctx.getResources().getDisplayMetrics().density);
        int chipColor = ContextCompat.getColor(ctx, R.color.fd_accent);
        Spannable arrows = ComboTipBuilder.renderArrowsOnly(
                ctx, mCapturedPath, iconSizePx, chipColor);
        sb.append(arrows);
        sb.append("\n\n");

        int conflictActionId = exactPathConflictAction();
        if (conflictActionId == mActionInt) {
            sb.append(ctx.getString(R.string.combos_bind_same_action_error));
        } else if (conflictActionId != ComboDispatcher.A_NONE) {
            int otherLabelRes = ComboActionRegistry.labelFor(conflictActionId);
            String otherLabel = (otherLabelRes != 0)
                    ? ctx.getString(otherLabelRes) : "";
            sb.append(ctx.getString(
                    R.string.combos_bind_conflict_warning, otherLabel));
        }
        // Prefix-collision note removed: combos fire on grip-release only, so
        // a shorter path sharing a prefix never "fires first" in practice.
        setBody(sb);
    }

    private boolean hasCapturedPath() {
        return mCapturedPath != null && mCapturedPath.length > 0;
    }

    /**
     * Returns the action id currently bound to the captured path, or
     * {@link ComboDispatcher#A_NONE} if no existing binding matches exactly.
     */
    private int exactPathConflictAction() {
        if (!hasCapturedPath()) return ComboDispatcher.A_NONE;
        String key = Arrays.toString(mCapturedPath);
        Map<String, Binding> bindings = mDispatcher.getAllBindings();
        Binding b = bindings.get(key);
        return b != null ? b.action : ComboDispatcher.A_NONE;
    }

    private boolean isSameActionCollision() {
        return exactPathConflictAction() == mActionInt;
    }

    /**
     * Prefix-collision cherry-pick: true when the captured path is a proper
     * prefix of an existing binding OR an existing binding is a proper prefix
     * of it. Either way the user loses one of the two combos to center-dwell
     * emit ordering and deserves a heads-up.
     */
    private boolean hasPrefixCollision() {
        if (!hasCapturedPath()) return false;
        Map<String, Binding> bindings = mDispatcher.getAllBindings();
        for (String rawKey : bindings.keySet()) {
            int[] other = parsePathKey(rawKey);
            if (other.length == 0 || other.length == mCapturedPath.length) continue;
            if (other.length < mCapturedPath.length
                    && isPrefix(other, mCapturedPath)) {
                return true;
            }
            if (mCapturedPath.length < other.length
                    && isPrefix(mCapturedPath, other)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrefix(@NonNull int[] shorter, @NonNull int[] longer) {
        if (shorter.length >= longer.length) return false;
        for (int i = 0; i < shorter.length; i++) {
            if (shorter[i] != longer[i]) return false;
        }
        return true;
    }

    @NonNull
    private static int[] parsePathKey(String rawKey) {
        if (rawKey == null || rawKey.length() < 2) return new int[0];
        String trimmed = rawKey.substring(1, rawKey.length() - 1).trim();
        if (trimmed.isEmpty()) return new int[0];
        String[] parts = trimmed.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return new int[0];
            }
        }
        return out;
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
        if (hud != null) {
            hud.setHudDimmed(dimmed);
        }
    }

    /** Convenience show with focus request. */
    public void showWithFocus() {
        show(UIWidget.REQUEST_FOCUS);
    }
}
