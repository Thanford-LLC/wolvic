/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.settings;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.igalia.wolvic.R;
import com.igalia.wolvic.input.ComboDispatcher;
import com.igalia.wolvic.ui.widgets.UIWidget;
import com.igalia.wolvic.ui.widgets.dialogs.PromptDialogWidget;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Section 3.4 — Combined backup import-picker sheet.
 *
 * <p>Extends {@link PromptDialogWidget} and replaces the body with a list of
 * tappable backup rows (up to 5, newest-first). Each row shows a human-readable
 * timestamp plus the combo and bookmark counts from the backup file.
 *
 * <p>On successful import the dialog title changes to "✓ Imported" and the
 * dialog auto-dismisses after 1.2 s — no Android system Toast needed (Toasts
 * are not composited into the Quest VR lens view).
 *
 * <p>Show pattern: {@code new ComboImportPickerView(ctx, dispatcher).showWithFocus()}.
 */
public class ComboImportPickerView extends PromptDialogWidget {

    private static final int MAX_FILES = 5;

    private final ComboDispatcher mDispatcher;

    public ComboImportPickerView(@NonNull Context ctx,
                                 @NonNull ComboDispatcher dispatcher) {
        super(ctx);
        mDispatcher = dispatcher;
        initialize(ctx);
    }

    @Override
    public void updateUI() {
        super.updateUI();

        Context ctx = getContext();
        setTitle(ctx.getString(R.string.gw_backup_import_title));
        setCheckboxVisible(false);
        setDescriptionVisible(false);

        setButtons(new int[]{ R.string.cancel_button });
        setButtonsDelegate((index, isChecked) -> onDismiss());

        List<ComboExportImport.ExportEntry> entries =
                ComboExportImport.listExportEntries(ctx, MAX_FILES);
        JSONObject clipEnvelope = ComboExportImport.tryReadClipboardEnvelope(ctx);

        mBinding.bodyContainer.removeAllViews();
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);

        if (clipEnvelope != null) {
            String clipLabel = ctx.getString(R.string.gw_combos_import_clipboard_row)
                    + "\n" + countsLine(ctx, clipEnvelope);
            list.addView(makeRow(ctx, clipLabel, clipEnvelope));
        }
        for (ComboExportImport.ExportEntry entry : entries) {
            String label = formatDisplayName(entry.displayName)
                    + "\n" + countsLineFromEntry(ctx, entry);
            list.addView(makeRow(ctx, label, null, entry));
        }
        if (clipEnvelope == null && entries.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText(R.string.gw_backup_import_no_files);
            empty.setTextColor(ContextCompat.getColor(ctx, R.color.rhino));
            empty.setPadding(0, dpToPx(ctx, 12), 0, dpToPx(ctx, 12));
            list.addView(empty);
        }
        mBinding.bodyContainer.addView(list);
    }

    // ── Row factories ─────────────────────────────────────────────────────────

    private View makeRow(@NonNull Context ctx, @NonNull String label,
                         @NonNull JSONObject envelope) {
        return makeRowImpl(ctx, label, envelope, null);
    }

    private View makeRow(@NonNull Context ctx, @NonNull String label,
                         @Nullable JSONObject ignored,
                         @NonNull ComboExportImport.ExportEntry entry) {
        return makeRowImpl(ctx, label, null, entry);
    }

    private View makeRowImpl(@NonNull Context ctx, @NonNull String label,
                              @Nullable JSONObject preParsed,
                              @Nullable ComboExportImport.ExportEntry entry) {
        float density = ctx.getResources().getDisplayMetrics().density;

        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(0xFF0c1140);
        normalBg.setStroke((int) density, 0x20FDDE0A);
        normalBg.setCornerRadius(4f * density);

        GradientDrawable focusedBg = new GradientDrawable();
        focusedBg.setColor(0xFF161B60);
        focusedBg.setStroke((int) (2f * density), 0xAAFDDE0A);
        focusedBg.setCornerRadius(4f * density);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{ android.R.attr.state_focused }, focusedBg);
        sld.addState(new int[]{ android.R.attr.state_hovered }, focusedBg);
        sld.addState(new int[]{}, normalBg);

        TextView row = new TextView(ctx);
        row.setBackground(sld);
        row.setText(label);
        row.setTextColor(ContextCompat.getColor(ctx, R.color.fog));
        row.setTextSize(13f);
        row.setLineSpacing(0, 1.2f);
        row.setPadding(dpToPx(ctx, 12), dpToPx(ctx, 10), dpToPx(ctx, 12), dpToPx(ctx, 10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> onRowTapped(preParsed, entry));

        // Clipboard rows have no on-disk file to remove — keep them full-width, no delete.
        if (entry == null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dpToPx(ctx, 6));
            row.setLayoutParams(lp);
            return row;
        }

        // File/URI backups: import label (flex) + a destructive delete button.
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, dpToPx(ctx, 6));
        container.setLayoutParams(clp);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rowLp.setMargins(0, 0, dpToPx(ctx, 6), 0);
        row.setLayoutParams(rowLp);

        container.addView(row);
        container.addView(makeDeleteButton(ctx, entry));
        return container;
    }

    /**
     * Destructive delete affordance for one backup row. Styled with the DESIGN.md
     * {@code gw_error} token (the only sanctioned destructive colour). Two-tap confirm:
     * the first tap arms it ("Delete?"), the second removes the file and rebuilds the list.
     */
    private View makeDeleteButton(@NonNull Context ctx,
                                  @NonNull ComboExportImport.ExportEntry entry) {
        float density = ctx.getResources().getDisplayMetrics().density;

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(android.graphics.Color.TRANSPARENT);
        bg.setStroke((int) density, ContextCompat.getColor(ctx, R.color.gw_error));
        bg.setCornerRadius(4f * density);

        TextView del = new TextView(ctx);
        del.setBackground(bg);
        del.setText(R.string.gw_backup_delete);
        del.setTextColor(ContextCompat.getColor(ctx, R.color.gw_error));
        del.setTextSize(13f);
        del.setPadding(dpToPx(ctx, 12), dpToPx(ctx, 10), dpToPx(ctx, 12), dpToPx(ctx, 10));
        del.setContentDescription(ctx.getString(R.string.gw_backup_delete_content_desc));
        del.setClickable(true);
        del.setFocusable(true);

        final boolean[] armed = { false };
        del.setOnClickListener(v -> {
            if (!armed[0]) {
                armed[0] = true;
                del.setText(R.string.gw_backup_delete_confirm);
            } else {
                ComboExportImport.deleteEntry(ctx, entry);
                updateUI();
            }
        });
        return del;
    }

    // ── Pick handler ──────────────────────────────────────────────────────────

    private void onRowTapped(@Nullable JSONObject preParsed,
                             @Nullable ComboExportImport.ExportEntry entry) {
        Context ctx = getContext();
        JSONObject envelope = preParsed;
        if (envelope == null && entry != null) {
            envelope = ComboExportImport.readEnvelopeFromEntry(ctx, entry);
        }
        if (envelope == null) {
            showError(ctx.getString(R.string.gw_combos_import_error_parse));
            return;
        }
        String error = ComboExportImport.validateEnvelope(ctx, envelope);
        if (error != null) {
            showError(formatError(ctx, error));
            return;
        }
        boolean ok = ComboExportImport.applyEnvelope(ctx, envelope);
        if (!ok) {
            showError(ctx.getString(R.string.gw_combos_import_error_parse));
            return;
        }
        mDispatcher.reloadBindings();

        // Show in-dialog success (no Toast — VR compositor doesn't surface Android
        // system overlays into the headset lens). Auto-dismiss after 1.2 s.
        setTitle(ctx.getString(R.string.gw_backup_import_success));
        mBinding.bodyContainer.removeAllViews();

        // Combo bindings (incl. A_GOTO_BOOKMARK, which now carries a portable URL) were
        // already applied by applyEnvelope above. Import plain bookmarks for Library
        // visibility, then recreate the Combo Bookmarks folder entries so imported combo
        // bookmarks also show in the Bookmark Manager. Both steps skip URLs already present.
        final JSONObject finalEnvelope = envelope;
        ComboExportImport.importBookmarksAsync(ctx, finalEnvelope, plainErr ->
                ComboExportImport.importComboBookmarksAsync(ctx, finalEnvelope,
                        comboErr -> mBinding.bodyContainer.postDelayed(this::onDismiss, 1200)));
    }

    private void showError(@NonNull String message) {
        setTitle(getContext().getString(R.string.gw_backup_import_title));
        setBody(message);
    }

    @NonNull
    private static String formatError(@NonNull Context ctx, @NonNull String error) {
        if ("unsupported_version".equals(error)) {
            return ctx.getString(R.string.gw_combos_import_error_version);
        }
        return ctx.getString(R.string.gw_combos_import_error_parse);
    }

    // ── Counts helpers ────────────────────────────────────────────────────────

    @NonNull
    private static String countsLine(@NonNull Context ctx, @NonNull JSONObject envelope) {
        ComboExportImport.EnvelopeCounts c = ComboExportImport.countEnvelope(envelope);
        return c.combos + " combos · " + c.bookmarks + " bookmarks";
    }

    @NonNull
    private static String countsLineFromEntry(@NonNull Context ctx,
                                              @NonNull ComboExportImport.ExportEntry entry) {
        JSONObject env = ComboExportImport.readEnvelopeFromEntry(ctx, entry);
        if (env == null) return "—";
        return countsLine(ctx, env);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @NonNull
    private static String formatDisplayName(@NonNull String name) {
        String prefix = "glyphew-backup-";
        if (name.startsWith(prefix) && name.endsWith(".json")) {
            String ts = name.substring(prefix.length(), name.length() - 5);
            try {
                Date d = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(ts);
                if (d != null) {
                    return new SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US).format(d);
                }
            } catch (Exception ignored) {}
            return ts;
        }
        return name;
    }

    private static int dpToPx(@NonNull Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density);
    }

    public void showWithFocus() {
        show(UIWidget.REQUEST_FOCUS);
    }
}
