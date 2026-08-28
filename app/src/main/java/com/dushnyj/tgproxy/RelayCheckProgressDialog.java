package com.dushnyj.tgproxy;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Visible, cancellable five-stage status surface shared by Relay import and manual tests. */
final class RelayCheckProgressDialog {
    static final int TOTAL_STEPS = 5;

    private final Activity activity;
    private final Runnable skipAction;
    private final AtomicBoolean abandoned = new AtomicBoolean(false);
    private final List<StepRow> rows = new ArrayList<>();
    private AlertDialog dialog;
    private TextView summary;
    private LinearProgressIndicator progress;

    RelayCheckProgressDialog(Activity activity, Runnable skipAction) {
        this.activity = activity;
        this.skipAction = skipAction;
    }

    void show() {
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_relay_check, null, false);
        summary = root.findViewById(R.id.tv_relay_check_summary);
        progress = root.findViewById(R.id.progress_relay_check);
        LinearLayout steps = root.findViewById(R.id.content_relay_check_steps);
        int[] labels = {
                R.string.relay_check_stage_connection,
                R.string.relay_check_stage_authorization,
                R.string.relay_check_stage_health,
                R.string.relay_check_stage_server_routes,
                R.string.relay_check_stage_telegram_routes
        };
        for (int label : labels) {
            View row = LayoutInflater.from(activity)
                    .inflate(R.layout.item_relay_check_step, steps, false);
            ((TextView) row.findViewById(R.id.tv_relay_check_step_title)).setText(label);
            rows.add(new StepRow(row));
            steps.addView(row);
        }
        MaterialButton cancel = root.findViewById(R.id.btn_relay_check_cancel);
        MaterialButton skip = root.findViewById(R.id.btn_relay_check_skip);
        TextView skipNote = root.findViewById(R.id.tv_relay_check_skip_note);
        if (skipAction == null) {
            skip.setVisibility(View.GONE);
            skipNote.setVisibility(View.GONE);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) cancel.getLayoutParams();
            params.setMarginEnd(0);
            cancel.setLayoutParams(params);
        }
        dialog = new MaterialAlertDialogBuilder(activity).setView(root).create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        cancel.setOnClickListener(view -> abandon(null));
        skip.setOnClickListener(view -> abandon(skipAction));
        update(0, VpsRelayClient.CheckStage.CONNECTION);
        dialog.show();
    }

    void update(int completedSteps, VpsRelayClient.CheckStage currentStage) {
        activity.runOnUiThread(() -> {
            if (abandoned.get() || dialog == null || !dialog.isShowing()) return;
            int completed = Math.max(0, Math.min(TOTAL_STEPS, completedSteps));
            int visibleStep = completed >= TOTAL_STEPS ? TOTAL_STEPS : completed + 1;
            summary.setText(activity.getString(R.string.relay_check_progress,
                    visibleStep, TOTAL_STEPS, TOTAL_STEPS - completed));
            progress.setProgressCompat(completed, true);
            int currentIndex = indexOf(currentStage);
            for (int index = 0; index < rows.size(); index++) {
                rows.get(index).render(index < completed,
                        currentIndex == index && completed < TOTAL_STEPS);
            }
        });
    }

    boolean isAbandoned() {
        return abandoned.get();
    }

    void dismissForResult() {
        activity.runOnUiThread(() -> {
            if (!abandoned.get() && dialog != null && dialog.isShowing()) dialog.dismiss();
        });
    }

    private void abandon(Runnable afterDismiss) {
        if (!abandoned.compareAndSet(false, true)) return;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (afterDismiss != null) afterDismiss.run();
    }

    private static int indexOf(VpsRelayClient.CheckStage stage) {
        if (stage == null) return -1;
        switch (stage) {
            case CONNECTION: return 0;
            case AUTHORIZATION: return 1;
            case HEALTH: return 2;
            case SERVER_ROUTES: return 3;
            case TELEGRAM_ROUTES: return 4;
            default: return -1;
        }
    }

    private final class StepRow {
        private final ImageView icon;
        private final ProgressBar spinner;
        private final TextView title;
        private final TextView state;

        StepRow(View root) {
            icon = root.findViewById(R.id.iv_relay_check_state);
            spinner = root.findViewById(R.id.progress_relay_check_state);
            title = root.findViewById(R.id.tv_relay_check_step_title);
            state = root.findViewById(R.id.tv_relay_check_step_state);
        }

        void render(boolean complete, boolean active) {
            spinner.setVisibility(active ? View.VISIBLE : View.GONE);
            icon.setVisibility(active ? View.GONE : View.VISIBLE);
            if (complete) {
                icon.setImageResource(R.drawable.ic_status_check);
                icon.setBackgroundResource(R.drawable.status_success_bg);
                icon.setImageTintList(ContextCompat.getColorStateList(activity, R.color.green));
                title.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
                title.setTypeface(title.getTypeface(), Typeface.BOLD);
                state.setText(R.string.relay_check_complete);
                state.setTextColor(ContextCompat.getColor(activity, R.color.green));
            } else if (active) {
                title.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
                title.setTypeface(title.getTypeface(), Typeface.BOLD);
                state.setText(R.string.relay_check_running);
                state.setTextColor(ContextCompat.getColor(activity, R.color.accent));
            } else {
                icon.setImageDrawable(null);
                icon.setBackgroundResource(R.drawable.status_neutral_bg);
                title.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
                title.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                state.setText(R.string.relay_check_waiting);
                state.setTextColor(ContextCompat.getColor(activity, R.color.text_hint));
            }
        }
    }
}
