package com.dushnyj.tgproxy;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Responsive, single-entry sharing surface used by every VPS Relay screen. */
final class RelayShareSheet {
    private RelayShareSheet() {}

    static void show(Activity activity, VpsRelayConfig relay) {
        if (activity == null || relay == null || !relay.isUsable()) return;
        try {
            String payload = SettingsTransfer.exportVpsRelay(relay);
            String link = SettingsTransfer.toRelayShareLink(relay, payload);
            View root = LayoutInflater.from(activity).inflate(R.layout.sheet_relay_share, null, false);
            BottomSheetDialog sheet = new BottomSheetDialog(activity);
            sheet.setContentView(root);
            TextView linkView = root.findViewById(R.id.tv_share_link);
            linkView.setText(link);
            linkView.setOnClickListener(view -> copy(activity, link));

            configureAction(activity, root.findViewById(R.id.action_share_copy),
                    R.drawable.ic_copy, R.string.relay_share_copy_link,
                    R.string.relay_share_copy_link_note, () -> copy(activity, link));
            configureAction(activity, root.findViewById(R.id.action_share_qr),
                    R.drawable.ic_qr, R.string.relay_share_qr_action,
                    R.string.relay_share_qr_note, () -> showQr(activity, link));
            configureAction(activity, root.findViewById(R.id.action_share_system),
                    R.drawable.ic_share, R.string.relay_share_system_action,
                    R.string.relay_share_system_note, () -> shareText(activity, link));
            configureAction(activity, root.findViewById(R.id.action_share_file),
                    R.drawable.ic_file, R.string.relay_share_file_action,
                    R.string.relay_share_file_note, () -> shareFile(activity, payload));

            sheet.setOnShowListener(ignored -> {
                View bottom = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
                if (bottom != null) {
                    BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottom);
                    behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                    behavior.setSkipCollapsed(true);
                }
            });
            sheet.show();
        } catch (Exception error) {
            Toast.makeText(activity, activity.getString(R.string.export_failed,
                    firstLine(error.getMessage())), Toast.LENGTH_LONG).show();
        }
    }

    private static void configureAction(Context context, LinearLayout row, int iconRes,
                                        int titleRes, int noteRes, Runnable action) {
        row.removeAllViews();
        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelsParams.setMargins(dp(context, 14), 0, dp(context, 8), 0);
        row.addView(labels, labelsParams);

        TextView title = new TextView(context);
        title.setText(titleRes);
        title.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary));
        title.setTextSize(15f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setIncludeFontPadding(false);
        labels.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(context);
        note.setText(noteRes);
        note.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary));
        note.setTextSize(12f);
        note.setIncludeFontPadding(false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(context, 3);
        labels.addView(note, noteParams);

        ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.ic_chevron_right);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)));
        row.setContentDescription(context.getString(titleRes));
        row.setOnClickListener(view -> action.run());
    }

    private static void copy(Context context, String value) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("TG Proxy Relay", value));
            Toast.makeText(context, R.string.relay_share_link_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private static void showQr(Activity activity, String link) {
        try {
            int available = activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 72);
            int size = Math.min(available, dp(activity, 320));
            Bitmap bitmap = QrCodeBitmap.create(link, size);

            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            content.setPadding(dp(activity, 16), dp(activity, 6), dp(activity, 16), 0);
            ImageView image = new ImageView(activity);
            image.setImageBitmap(bitmap);
            image.setContentDescription(activity.getString(R.string.relay_share_qr_title));
            content.addView(image, new LinearLayout.LayoutParams(size, size));

            TextView text = new TextView(activity);
            text.setText(link);
            text.setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.accent));
            text.setTextSize(12f);
            text.setGravity(Gravity.CENTER);
            text.setTextIsSelectable(true);
            text.setMaxLines(3);
            text.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textParams.topMargin = dp(activity, 10);
            content.addView(text, textParams);

            AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.relay_share_qr_title)
                    .setView(content)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(R.string.relay_share_copy_link,
                            (ignored, which) -> copy(activity, link))
                    .setNeutralButton(R.string.share_qr,
                            (ignored, which) -> shareQrImage(activity, link))
                    .create();
            dialog.setOnDismissListener(ignored -> {
                if (!bitmap.isRecycled()) bitmap.recycle();
            });
            dialog.show();
        } catch (Exception error) {
            Toast.makeText(activity, activity.getString(R.string.qr_failed,
                    firstLine(error.getMessage())), Toast.LENGTH_LONG).show();
        }
    }

    private static void shareQrImage(Activity activity, String link) {
        try {
            File dir = new File(activity.getCacheDir(), "exports");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("export directory");
            File[] old = dir.listFiles((ignored, name) ->
                    name != null && name.startsWith("tgproxy-relay-qr-") && name.endsWith(".png"));
            if (old != null) {
                long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
                for (File file : old) if (file.lastModified() < cutoff) file.delete();
            }
            File file = new File(dir, "tgproxy-relay-qr-" + System.currentTimeMillis() + ".png");
            Bitmap bitmap = QrCodeBitmap.create(link, 768);
            try {
                try (FileOutputStream output = new FileOutputStream(file, false)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw new IllegalStateException("QR encode failed");
                    }
                    output.flush();
                    output.getFD().sync();
                }
            } finally {
                bitmap.recycle();
            }
            Uri uri = FileProvider.getUriForFile(activity,
                    BuildConfig.APPLICATION_ID + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_TEXT, link);
            intent.putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.relay_share_title));
            intent.setClipData(ClipData.newUri(activity.getContentResolver(), file.getName(), uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent,
                    activity.getString(R.string.relay_share_choose_app)));
        } catch (Exception error) {
            Toast.makeText(activity, activity.getString(R.string.qr_failed,
                    firstLine(error.getMessage())), Toast.LENGTH_LONG).show();
        }
    }

    private static void shareText(Activity activity, String link) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.relay_share_title));
        intent.putExtra(Intent.EXTRA_TEXT, link);
        activity.startActivity(Intent.createChooser(intent,
                activity.getString(R.string.relay_share_choose_app)));
    }

    private static void shareFile(Activity activity, String payload) {
        try {
            File dir = new File(activity.getCacheDir(), "exports");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("export directory");
            File file = new File(dir, "tgproxy-vps-relay.tgproxy");
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(payload.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            Uri uri = FileProvider.getUriForFile(activity,
                    BuildConfig.APPLICATION_ID + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.relay_share_title));
            intent.setClipData(ClipData.newUri(activity.getContentResolver(), file.getName(), uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent,
                    activity.getString(R.string.relay_share_choose_app)));
        } catch (Exception error) {
            Toast.makeText(activity, activity.getString(R.string.export_failed,
                    firstLine(error.getMessage())), Toast.LENGTH_LONG).show();
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String firstLine(String value) {
        String text = value == null ? "" : value.trim();
        int newline = text.indexOf('\n');
        if (newline >= 0) text = text.substring(0, newline).trim();
        return text.isEmpty() ? "unknown" : text;
    }
}
