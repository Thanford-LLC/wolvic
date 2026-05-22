/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.thanford.glyphew.settings;

import android.content.Context;
import android.graphics.Point;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;

import com.igalia.wolvic.BuildConfig;
import com.igalia.wolvic.R;
import com.igalia.wolvic.databinding.OptionsAboutBinding;
import com.igalia.wolvic.ui.widgets.WidgetManagerDelegate;
import com.igalia.wolvic.ui.widgets.WidgetPlacement;
import com.igalia.wolvic.ui.widgets.settings.SettingsView;

public class AboutView extends SettingsView {

    private OptionsAboutBinding mBinding;

    public AboutView(@NonNull Context aContext,
                     @NonNull WidgetManagerDelegate aWidgetManager) {
        super(aContext, aWidgetManager);
        initialize(aContext);
    }

    private void initialize(Context aContext) {
        updateUI();
    }

    @Override
    protected void updateUI() {
        super.updateUI();

        LayoutInflater inflater = LayoutInflater.from(getContext());
        mBinding = DataBindingUtil.inflate(inflater, R.layout.options_about, this, true);
        mScrollbar = mBinding.scrollbar;

        mBinding.headerLayout.setBackClickListener(view ->
                mDelegate.showView(SettingViewType.MAIN));

        mBinding.versionText.setText(BuildConfig.VERSION_NAME);

        mBinding.openSourceLicensesRow.setOnClickListener(v ->
                mDelegate.showView(SettingViewType.OPEN_SOURCE_LICENSES));
        mBinding.termsOfServiceRow.setOnClickListener(v ->
                mWidgetManager.openNewTabForeground(getContext().getString(R.string.settings_about_terms_url)));
        mBinding.privacyPolicyRow.setOnClickListener(v ->
                mWidgetManager.openNewTabForeground(getContext().getString(R.string.settings_about_privacy_url)));
        mBinding.supportRow.setOnClickListener(v ->
                mWidgetManager.openNewTabForeground(getContext().getString(R.string.settings_about_support_url)));
    }

    @Override
    public Point getDimensions() {
        return new Point(WidgetPlacement.dpDimension(getContext(), R.dimen.settings_dialog_width),
                WidgetPlacement.dpDimension(getContext(), R.dimen.privacy_options_height));
    }

    @Override
    protected SettingViewType getType() {
        return SettingViewType.ABOUT;
    }
}
