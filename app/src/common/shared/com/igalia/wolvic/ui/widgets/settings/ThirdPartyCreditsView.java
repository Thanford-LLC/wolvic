/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.widgets.settings;

import android.content.Context;
import android.graphics.Point;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.databinding.DataBindingUtil;

import com.igalia.wolvic.R;
import com.igalia.wolvic.databinding.OptionsThirdPartyCreditsBinding;
import com.igalia.wolvic.ui.widgets.WidgetManagerDelegate;
import com.igalia.wolvic.ui.widgets.WidgetPlacement;
import com.igalia.wolvic.utils.Environment;
import com.igalia.wolvic.utils.EnvironmentUtils;

public class ThirdPartyCreditsView extends SettingsView {

    private static final String CC_BY_4_0_URL = "https://creativecommons.org/licenses/by/4.0/";

    private OptionsThirdPartyCreditsBinding mBinding;

    public ThirdPartyCreditsView(Context aContext, WidgetManagerDelegate aWidgetManager) {
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
        mBinding = DataBindingUtil.inflate(inflater, R.layout.options_third_party_credits, this, true);
        mScrollbar = mBinding.scrollbar;

        mBinding.headerLayout.setBackClickListener(view ->
                mDelegate.showView(SettingViewType.ENVIRONMENT));

        populateEntries(inflater);
    }

    private void populateEntries(LayoutInflater inflater) {
        Environment[] envs = EnvironmentUtils.getExternalEnvironments(getContext());
        LinearLayout container = mBinding.entriesContainer;
        container.removeAllViews();

        if (envs == null || envs.length == 0) {
            mBinding.creditsEmptyState.setVisibility(View.VISIBLE);
            return;
        }

        mBinding.creditsEmptyState.setVisibility(View.GONE);

        String sourcePrefix = getContext().getString(R.string.third_party_credits_source_prefix);

        for (Environment env : envs) {
            LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(16);
            card.setLayoutParams(cardParams);

            // Work title + author, e.g. "Rural Evening Road — Alexander Scholten"
            TextView titleLine = new TextView(getContext());
            String workTitle = env.getWorkTitle();
            String author = env.getAuthor();
            String titleText = author != null && !author.isEmpty()
                    ? workTitle + " \u2014 " + author
                    : workTitle;
            titleLine.setText(titleText);
            titleLine.setTextAppearance(R.style.settingsText);
            card.addView(titleLine);

            // License line, with hyperlink to CC-BY 4.0 text for CC-BY entries
            String license = env.getLicense();
            if (license != null && !license.isEmpty()) {
                TextView licenseLine = new TextView(getContext());
                String licenseHtml = isCcByLicense(license)
                        ? "Licensed under <a href=\"" + CC_BY_4_0_URL + "\">" + escape(license) + "</a>"
                        : "License: " + escape(license);
                licenseLine.setText(Html.fromHtml(licenseHtml, Html.FROM_HTML_MODE_LEGACY));
                licenseLine.setMovementMethod(LinkMovementMethod.getInstance());
                licenseLine.setTextAppearance(R.style.settingsText);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                p.topMargin = dp(4);
                licenseLine.setLayoutParams(p);
                card.addView(licenseLine);
            }

            // Source link (§3.a.1.v — URI/hyperlink to Licensed Material)
            String source = env.getSource();
            if (source != null && !source.isEmpty()) {
                TextView sourceLine = new TextView(getContext());
                String sourceHtml = sourcePrefix + "<a href=\"" + escape(source) + "\">"
                        + escape(source) + "</a>";
                sourceLine.setText(Html.fromHtml(sourceHtml, Html.FROM_HTML_MODE_LEGACY));
                sourceLine.setMovementMethod(LinkMovementMethod.getInstance());
                sourceLine.setTextAppearance(R.style.settingsText);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                p.topMargin = dp(4);
                sourceLine.setLayoutParams(p);
                card.addView(sourceLine);
            }

            container.addView(card);
        }
    }

    private static boolean isCcByLicense(String license) {
        String n = license.toUpperCase().replace(" ", "").replace("-", "");
        return n.contains("CCBY") && !n.contains("NC") && !n.contains("ND");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public Point getDimensions() {
        return new Point(WidgetPlacement.dpDimension(getContext(), R.dimen.settings_dialog_width),
                WidgetPlacement.dpDimension(getContext(), R.dimen.privacy_options_height));
    }

    @Override
    protected SettingViewType getType() {
        return SettingViewType.THIRD_PARTY_CREDITS;
    }
}
