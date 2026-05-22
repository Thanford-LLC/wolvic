/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.thanford.glyphew.settings;

import android.content.Context;
import android.graphics.Point;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;

import com.igalia.wolvic.R;
import com.igalia.wolvic.databinding.OptionsOpenSourceLicensesBinding;
import com.igalia.wolvic.ui.widgets.WidgetManagerDelegate;
import com.igalia.wolvic.ui.widgets.WidgetPlacement;
import com.igalia.wolvic.ui.widgets.settings.SettingsView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class OpenSourceLicensesView extends SettingsView {

    private static final String LOGTAG     = "OpenSourceLicensesView";
    private static final String ASSET_PATH = "glyphew/open_source_licenses.json";

    private OptionsOpenSourceLicensesBinding mBinding;

    public OpenSourceLicensesView(@NonNull Context aContext,
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
        mBinding = DataBindingUtil.inflate(
                inflater, R.layout.options_open_source_licenses, this, true);
        mScrollbar = mBinding.scrollbar;

        mBinding.headerLayout.setBackClickListener(view ->
                mDelegate.showView(SettingViewType.ABOUT));

        populateEntries();
    }

    private void populateEntries() {
        List<LicenseEntry> entries = LicenseParser.parse(readAsset());
        LinearLayout container = mBinding.entriesContainer;
        container.removeAllViews();
        for (LicenseEntry e : entries) {
            addEntryCard(container, e);
        }
    }

    private void addEntryCard(@NonNull LinearLayout container, @NonNull LicenseEntry entry) {
        int accentColor = ContextCompat.getColor(getContext(), R.color.gw_accent);

        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(16);
        card.setLayoutParams(cardParams);

        TextView name = new TextView(getContext());
        name.setText(entry.name);
        name.setTextAppearance(R.style.settingsText);
        name.setTextColor(accentColor);
        card.addView(name);

        if (!entry.copyright.isEmpty()) {
            TextView copyright = new TextView(getContext());
            copyright.setText(entry.copyright);
            copyright.setTextAppearance(R.style.settingsText);
            copyright.setLayoutParams(topMargin(4));
            card.addView(copyright);
        }

        if (!entry.license.isEmpty()) {
            TextView license = new TextView(getContext());
            String licenseHtml = !entry.licenseUrl.isEmpty()
                    ? "<a href=\"" + escape(entry.licenseUrl) + "\">" + escape(entry.license) + "</a>"
                    : escape(entry.license);
            license.setText(Html.fromHtml(licenseHtml, Html.FROM_HTML_MODE_LEGACY));
            license.setMovementMethod(LinkMovementMethod.getInstance());
            license.setTextAppearance(R.style.settingsText);
            license.setLayoutParams(topMargin(4));
            card.addView(license);
        }

        if (!entry.sourceUrl.isEmpty()) {
            TextView source = new TextView(getContext());
            String sourceHtml = "<a href=\"" + escape(entry.sourceUrl) + "\">"
                    + escape(entry.sourceUrl) + "</a>";
            source.setText(Html.fromHtml(sourceHtml, Html.FROM_HTML_MODE_LEGACY));
            source.setMovementMethod(LinkMovementMethod.getInstance());
            source.setTextAppearance(R.style.settingsText);
            source.setLayoutParams(topMargin(4));
            card.addView(source);
        }

        container.addView(card);
    }

    private LinearLayout.LayoutParams topMargin(int dpValue) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(dpValue);
        return p;
    }

    private String readAsset() {
        try (InputStream is = getContext().getAssets().open(ASSET_PATH)) {
            byte[] buf = new byte[is.available()];
            int read = is.read(buf);
            return new String(buf, 0, read, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(LOGTAG, "Failed to read " + ASSET_PATH, e);
            return null;
        }
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
        return SettingViewType.OPEN_SOURCE_LICENSES;
    }
}
