/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary FingerDance component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.databinding.DataBindingUtil;
import androidx.preference.PreferenceManager;

import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.databinding.OptionsCombosBinding;
import com.igalia.wolvic.input.ComboDispatcher;
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
 *   <li>Mode switch → {@link ComboDispatcher#COMBO_MODE_4DIR_KEY} (ON = 8-dir)</li>
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
    private SwitchSetting.OnCheckedChangeListener mModeListener;
    private SwitchSetting.OnCheckedChangeListener mHudListener;
    private SwitchSetting.OnCheckedChangeListener mBuzzListener;
    private SwitchSetting.OnCheckedChangeListener mGhostListener;
    private ComboDispatcher mDispatcher;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Phase 7: when opened via long-press joystick (direct open), Back skips the
    // settings grid and returns straight to browsing. Set before construction via
    // flagNextAsDirectOpen(); consumed once in the constructor.
    private static volatile boolean sNextDirectOpen = false;
    private final boolean mDirectOpen;

    /** Call before showSettingsDialog(COMBOS) to make Back exit the whole panel. */
    public static void flagNextAsDirectOpen() {
        sNextDirectOpen = true;
    }

    public CombosSettingsView(Context aContext, WidgetManagerDelegate aWidgetManager) {
        super(aContext, aWidgetManager);
        mDirectOpen = sNextDirectOpen;
        sNextDirectOpen = false;
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

        // --- Advanced section: collapsible header with chevron ---
        // Starts collapsed (content visibility=GONE in XML, chevron=▶).
        // Click toggles content + rotates chevron ▶↔▼.
        TextView chevron = mBinding.combosSectionAdvancedChevron;
        mBinding.combosSectionAdvancedHeader.setOnClickListener(v -> {
            boolean expanded = mBinding.combosAdvancedContent.getVisibility() == View.VISIBLE;
            mBinding.combosAdvancedContent.setVisibility(expanded ? View.GONE : View.VISIBLE);
            // Rotate same ▼ glyph: −90° = right (collapsed), 0° = down (expanded).
            chevron.animate().rotation(expanded ? -90f : 0f).setDuration(150).start();
        });

        // --- 8-dir mode switch ---
        // Switch ON = 8-dir mode (is4Dir=false), OFF = 4-dir mode (is4Dir=true).
        boolean is4Dir = prefs.getBoolean(ComboDispatcher.COMBO_MODE_4DIR_KEY, true);
        mModeListener = (button, checked, apply) -> {
            // checked=true means 8-dir ON → is4Dir=false
            prefs.edit().putBoolean(ComboDispatcher.COMBO_MODE_4DIR_KEY, !checked).apply();
            // Rebuild the combo list to reflect the new mode's bindings
            if (mDispatcher != null && mBinding != null) {
                mDispatcher.reloadBindings();
            }
        };
        mBinding.combosModeSwitch.setOnCheckedChangeListener(null);
        mBinding.combosModeSwitch.setValue(!is4Dir, false); // ON when 8-dir
        mBinding.combosModeSwitch.setOnCheckedChangeListener(mModeListener);

        // --- HUD visibility switch ---
        boolean hudVisible = prefs.getBoolean(ComboHUDWidget.PREF_HUD_VISIBLE, true);
        mHudListener = (button, checked, apply) -> {
            prefs.edit().putBoolean(ComboHUDWidget.PREF_HUD_VISIBLE, checked).apply();
        };
        mBinding.combosHudSwitch.setOnCheckedChangeListener(null);
        mBinding.combosHudSwitch.setValue(hudVisible, false);
        mBinding.combosHudSwitch.setOnCheckedChangeListener(mHudListener);

        // --- Ghost routes switch ---
        boolean ghostVisible = prefs.getBoolean(ComboHUDWidget.PREF_GHOST_VISIBLE, true);
        mGhostListener = (button, checked, apply) -> {
            prefs.edit().putBoolean(ComboHUDWidget.PREF_GHOST_VISIBLE, checked).apply();
        };
        mBinding.combosGhostSwitch.setOnCheckedChangeListener(null);
        mBinding.combosGhostSwitch.setValue(ghostVisible, false);
        mBinding.combosGhostSwitch.setOnCheckedChangeListener(mGhostListener);

        // --- Buzz-on-combo switch (Phase 2 §D8 / §T2) ---
        boolean buzzOn = prefs.getBoolean(ComboHUDWidget.PREF_COMBO_HAPTICS, true);
        mBuzzListener = (button, checked, apply) -> {
            prefs.edit().putBoolean(ComboHUDWidget.PREF_COMBO_HAPTICS, checked).apply();
        };
        mBinding.combosBuzzSwitch.setOnCheckedChangeListener(null);
        mBinding.combosBuzzSwitch.setValue(buzzOn, false);
        mBinding.combosBuzzSwitch.setOnCheckedChangeListener(mBuzzListener);

        // --- Reset footer (Phase 5 cherry-pick) ---
        mBinding.footerLayout.setFooterButtonClickListener(view -> {
            new ComboResetConfirmDialog(getContext()).show(UIWidget.REQUEST_FOCUS);
        });

        // --- Phase 3 — read-only categorised combo list ---
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
        if (mDirectOpen && mDelegate != null) {
            // Long-press direct open: Back goes straight to browsing, not the settings grid.
            mDelegate.exitWholeSettings();
        } else {
            super.onDismiss();
        }
    }

    // --- ComboDispatcher.BindingsListener ---
    @Override
    public void onBindingsChanged() {
        mMainHandler.post(() -> {
            if (mBinding != null && mDispatcher != null) {
                CombosListBuilder.populate(getContext(),
                        mBinding.combosListContainer, mDispatcher);
                // Keep mode switch in sync after reloadBindings()
                SharedPreferences prefs =
                        PreferenceManager.getDefaultSharedPreferences(getContext());
                boolean is4Dir = prefs.getBoolean(ComboDispatcher.COMBO_MODE_4DIR_KEY, true);
                mBinding.combosModeSwitch.setOnCheckedChangeListener(null);
                mBinding.combosModeSwitch.setValue(!is4Dir, false);
                mBinding.combosModeSwitch.setOnCheckedChangeListener(mModeListener);
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
