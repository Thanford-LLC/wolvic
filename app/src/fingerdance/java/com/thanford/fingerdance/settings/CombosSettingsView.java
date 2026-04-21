/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary FingerDance component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.fingerdance.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;

import androidx.databinding.DataBindingUtil;
import androidx.preference.PreferenceManager;

import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.databinding.OptionsCombosBinding;
import com.igalia.wolvic.input.ComboDispatcher;
import com.igalia.wolvic.ui.views.settings.RadioGroupSetting;
import com.igalia.wolvic.ui.views.settings.SwitchSetting;
import com.igalia.wolvic.ui.widgets.ComboHUDWidget;
import com.igalia.wolvic.ui.widgets.UIWidget;
import com.igalia.wolvic.ui.widgets.WidgetManagerDelegate;
import com.igalia.wolvic.ui.widgets.WidgetPlacement;
import com.igalia.wolvic.ui.widgets.settings.SettingsView;

/**
 * Phase 2 — Combos Settings panel.
 *
 * <p>Three toggles persisted to the default SharedPreferences file:
 * <ul>
 *   <li>Mode radio → {@link ComboDispatcher#COMBO_MODE_4DIR_KEY}</li>
 *   <li>HUD switch → {@link ComboHUDWidget#PREF_HUD_VISIBLE}</li>
 *   <li>Buzz switch → {@link ComboHUDWidget#PREF_COMBO_HAPTICS}</li>
 * </ul>
 *
 * <p>Plus a Reset-to-defaults footer that wipes the user override blob in
 * {@link ComboBindingStore}; the dispatcher's pref listener observes the blob
 * key change and rebuilds its tables automatically — no direct handle needed.
 *
 * <p>Phase 3 and later layer the categorised binding list, bind flow, and
 * bookmark integration on top of this chrome.
 */
public class CombosSettingsView extends SettingsView
        implements ComboDispatcher.BindingsListener {

    private OptionsCombosBinding mBinding;
    private RadioGroupSetting.OnCheckedChangeListener mModeListener;
    private SwitchSetting.OnCheckedChangeListener mHudListener;
    private SwitchSetting.OnCheckedChangeListener mBuzzListener;
    private ComboDispatcher mDispatcher;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public CombosSettingsView(Context aContext, WidgetManagerDelegate aWidgetManager) {
        super(aContext, aWidgetManager);
        updateUI();
    }

    @Override
    protected void updateUI() {
        super.updateUI();

        LayoutInflater inflater = LayoutInflater.from(getContext());
        mBinding = DataBindingUtil.inflate(inflater, R.layout.options_combos, this, true);
        mScrollbar = mBinding.scrollbar;

        mBinding.headerLayout.setBackClickListener(view -> onDismiss());

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        // --- Mode radio (4-dir / 8-dir) ---
        boolean is4Dir = prefs.getBoolean(ComboDispatcher.COMBO_MODE_4DIR_KEY, true);
        int modeIndex = mBinding.combosModeRadio.getIdForValue(Boolean.toString(is4Dir));
        mModeListener = (radioGroup, checkedId, apply) -> {
            Object value = mBinding.combosModeRadio.getValueForId(checkedId);
            boolean new4Dir = Boolean.parseBoolean(value.toString());
            prefs.edit().putBoolean(ComboDispatcher.COMBO_MODE_4DIR_KEY, new4Dir).apply();
        };
        mBinding.combosModeRadio.setOnCheckedChangeListener(null);
        mBinding.combosModeRadio.setChecked(modeIndex, false);
        mBinding.combosModeRadio.setOnCheckedChangeListener(mModeListener);

        // --- HUD visibility switch ---
        boolean hudVisible = prefs.getBoolean(ComboHUDWidget.PREF_HUD_VISIBLE, true);
        mHudListener = (button, checked, apply) -> {
            prefs.edit().putBoolean(ComboHUDWidget.PREF_HUD_VISIBLE, checked).apply();
        };
        mBinding.combosHudSwitch.setOnCheckedChangeListener(null);
        mBinding.combosHudSwitch.setValue(hudVisible, false);
        mBinding.combosHudSwitch.setOnCheckedChangeListener(mHudListener);

        // --- Buzz-on-combo switch (Phase 2 §D8 / §T2) ---
        boolean buzzOn = prefs.getBoolean(ComboHUDWidget.PREF_COMBO_HAPTICS, true);
        mBuzzListener = (button, checked, apply) -> {
            prefs.edit().putBoolean(ComboHUDWidget.PREF_COMBO_HAPTICS, checked).apply();
        };
        mBinding.combosBuzzSwitch.setOnCheckedChangeListener(null);
        mBinding.combosBuzzSwitch.setValue(buzzOn, false);
        mBinding.combosBuzzSwitch.setOnCheckedChangeListener(mBuzzListener);

        // --- Reset footer (Phase 5 cherry-pick) ---
        // Phase 2 wired this footer to call clearAll() directly; Phase 5 adds
        // a confirm dialog because reset annihilates every user-customised
        // binding. POSITIVE in ComboResetConfirmDialog calls clearAll() which
        // wipes the override blob — the dispatcher's pref listener on
        // ComboBindingStore.KEY_BLOB fires reloadBindings() automatically and
        // the BindingsListener spine refreshes this list. Toggles above are
        // deliberately untouched (panel prefs, not combo bindings).
        mBinding.footerLayout.setFooterButtonClickListener(view -> {
            new ComboResetConfirmDialog(getContext()).show(UIWidget.REQUEST_FOCUS);
        });

        // --- Phase 3 — read-only categorised combo list ---
        // Fetch the live dispatcher via the activity accessor, populate the
        // list container, and subscribe for refresh on mode toggle / reset.
        if (mDispatcher != null) {
            mDispatcher.removeBindingsListener(this);
        }
        Context ctx = getContext();
        if (ctx instanceof VRBrowserActivity) {
            mDispatcher = ((VRBrowserActivity) ctx).getComboDispatcher();
        }
        if (mDispatcher != null) {
            CombosListBuilder.populate(ctx, mBinding.combosListContainer, mDispatcher);
            mDispatcher.addBindingsListener(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mDispatcher != null) {
            mDispatcher.removeBindingsListener(this);
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDismiss() {
        if (mDispatcher != null) {
            mDispatcher.removeBindingsListener(this);
        }
        super.onDismiss();
    }

    // --- ComboDispatcher.BindingsListener ---
    // stampBindingChange fires on the caller's thread; post to main so the
    // list rebuild runs on the UI thread even when future phases trigger
    // binding changes from the input thread.
    @Override
    public void onBindingsChanged() {
        mMainHandler.post(() -> {
            if (mBinding != null && mDispatcher != null) {
                CombosListBuilder.populate(getContext(),
                        mBinding.combosListContainer, mDispatcher);
            }
        });
    }

    @Override
    public Point getDimensions() {
        return new Point(WidgetPlacement.dpDimension(getContext(), R.dimen.settings_dialog_width),
                WidgetPlacement.dpDimension(getContext(), R.dimen.controller_options_height));
    }

    @Override
    protected SettingViewType getType() {
        return SettingViewType.COMBOS;
    }
}
