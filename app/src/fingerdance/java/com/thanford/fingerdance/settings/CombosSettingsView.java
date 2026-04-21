/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary FingerDance component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.fingerdance.settings;

import android.content.Context;
import android.graphics.Point;
import android.view.LayoutInflater;

import androidx.databinding.DataBindingUtil;

import com.igalia.wolvic.R;
import com.igalia.wolvic.databinding.OptionsCombosBinding;
import com.igalia.wolvic.ui.widgets.WidgetManagerDelegate;
import com.igalia.wolvic.ui.widgets.WidgetPlacement;
import com.igalia.wolvic.ui.widgets.settings.SettingsView;

/**
 * Phase 1 stub — header + back button only. Later phases layer in the mode /
 * HUD / haptics switches, categorized binding list, and Bind Combo flow.
 */
public class CombosSettingsView extends SettingsView {

    private OptionsCombosBinding mBinding;

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
