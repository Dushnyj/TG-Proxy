package com.dushnyj.tgproxy;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Visible, cancellable five-stage status surface shared by Relay import and manual tests. */
final class RelayCheckProgressDialog implements VpsRelayClient.ProgressListener {
    static final int TOTAL_STEPS = 5;

    private final Activity activity;
    private final Runnable skipAction;
    private final AtomicBoolean abandoned = new AtomicBoolean(false);
    private final List<StepRow> rows = new ArrayList<>();
    private AlertDialog dialog;
    private TextView summary;
    private LinearProgressIndicator progress;
    private ScrollView bodyScroll;
    private View dcSection;
    private TextView dcSummary;
    private LinearLayout dcSteps;
    private final Map<String, RouteRow> routeRows = new LinkedHashMap<>();

    RelayCheckProgressDialog(Activity activity, Runnable skipAction) {
        this.activity = activity;
        this.skipAction = skipAction;
    }

    void show() {
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_relay_check, null, false);
        summary = root.findViewById(R.id.tv_relay_check_summary);
        progress = root.findViewById(R.id.progress_relay_check);
        bodyScroll = root.findViewById(R.id.scroll_relay_check_body);
        LinearLayout steps = root.findViewById(R.id.content_relay_check_steps);
        dcSection = root.findViewById(R.id.content_relay_dc_section);
        dcSummary = root.findViewById(R.id.tv_relay_dc_summary);
        dcSteps = root.findViewById(R.id.content_relay_dc_checks);
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

    @Override
    public void onProgress(int completedSteps, VpsRelayClient.CheckStage currentStage) {
        update(completedSteps, currentStage);
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

    @Override
    public void onRoutePlan(List<VpsRelayClient.RouteTarget> targets) {
        activity.runOnUiThread(() -> {
            if (abandoned.get() || dialog == null || !dialog.isShowing()) return;
            routeRows.clear();
            dcSteps.removeAllViews();
            List<VpsRelayClient.RouteTarget> safe = targets == null
                    ? new ArrayList<>() : targets;
            for (VpsRelayClient.RouteTarget target : safe) {
                if (target == null || routeRows.containsKey(target.key())) continue;
                View rowView = LayoutInflater.from(activity)
                        .inflate(R.layout.item_relay_dc_check, dcSteps, false);
                RouteRow row = new RouteRow(rowView, target);
                routeRows.put(target.key(), row);
                dcSteps.addView(rowView);
            }
            dcSummary.setText(activity.getString(R.string.relay_check_dc_progress,
                    0, routeRows.size(), routeRows.size()));
            dcSection.setVisibility(routeRows.isEmpty() ? View.GONE : View.VISIBLE);
            if (!routeRows.isEmpty()) {
                dcSection.post(() -> bodyScroll.smoothScrollTo(
                        0, Math.max(0, dcSection.getTop() - dp(6))));
            }
        });
    }

    @Override
    public void onRouteProgress(VpsRelayClient.RouteProgress update) {
        activity.runOnUiThread(() -> {
            if (abandoned.get() || dialog == null || !dialog.isShowing()
                    || update == null || update.target() == null) return;
            RouteRow row = routeRows.get(update.target().key());
            if (row == null) return;
            row.render(update);
            int total = update.total() > 0 ? update.total() : routeRows.size();
            int completed = Math.max(0, Math.min(total, update.completed()));
            dcSummary.setText(activity.getString(R.string.relay_check_dc_progress,
                    completed, total, Math.max(0, total - completed)));
            summary.setText(activity.getString(R.string.relay_check_route_progress,
                    completed, total, Math.max(0, total - completed)));
            updateActiveRoutesLabel();
            if (update.state() == VpsRelayClient.RouteCheckState.RUNNING) ensureVisible(row.root);
        });
    }

    private void updateActiveRoutesLabel() {
        ArrayList<String> active = new ArrayList<>();
        for (RouteRow row : routeRows.values()) {
            if (row.state == VpsRelayClient.RouteCheckState.RUNNING) active.add(row.shortLabel());
        }
        if (rows.size() < TOTAL_STEPS) return;
        if (active.isEmpty()) {
            rows.get(TOTAL_STEPS - 1).setActiveState(
                    activity.getString(R.string.relay_check_dc_switching));
            return;
        }
        StringBuilder labels = new StringBuilder();
        for (String label : active) {
            if (labels.length() > 0) labels.append(", ");
            labels.append(label);
        }
        rows.get(TOTAL_STEPS - 1).setActiveState(
                activity.getString(R.string.relay_check_dc_now, labels.toString()));
    }

    private void ensureVisible(View row) {
        if (bodyScroll == null || row == null) return;
        bodyScroll.post(() -> {
            Rect bounds = new Rect();
            row.getDrawingRect(bounds);
            bodyScroll.offsetDescendantRectToMyCoords(row, bounds);
            int visibleTop = bodyScroll.getScrollY();
            int visibleBottom = visibleTop + bodyScroll.getHeight();
            if (bounds.top < visibleTop || bounds.bottom > visibleBottom) {
                bodyScroll.smoothScrollTo(0, Math.max(0, bounds.top - dp(12)));
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

        void setActiveState(CharSequence value) {
            state.setText(value);
            state.setTextColor(ContextCompat.getColor(activity, R.color.accent));
        }
    }

    private final class RouteRow {
        final View root;
        final VpsRelayClient.RouteTarget target;
        final ImageView icon;
        final ProgressBar spinner;
        final TextView stateView;
        VpsRelayClient.RouteCheckState state;

        RouteRow(View root, VpsRelayClient.RouteTarget target) {
            this.root = root;
            this.target = target;
            icon = root.findViewById(R.id.iv_relay_dc_state);
            spinner = root.findViewById(R.id.progress_relay_dc_state);
            TextView title = root.findViewById(R.id.tv_relay_dc_title);
            stateView = root.findViewById(R.id.tv_relay_dc_state);
            title.setText(target.test()
                    ? activity.getString(R.string.relay_check_test_dc_title,
                            target.dc(), scopeLabel(target))
                    : activity.getString(R.string.relay_check_dc_title,
                            target.dc(), scopeLabel(target)));
        }

        void render(VpsRelayClient.RouteProgress update) {
            state = update.state();
            boolean running = state == VpsRelayClient.RouteCheckState.RUNNING;
            spinner.setVisibility(running ? View.VISIBLE : View.GONE);
            icon.setVisibility(running ? View.GONE : View.VISIBLE);
            if (running) {
                stateView.setText(update.attempts() > 1
                        ? activity.getString(R.string.relay_check_dc_attempt,
                                update.attempt(), update.attempts())
                        : activity.getString(R.string.relay_check_running));
                stateView.setTextColor(ContextCompat.getColor(activity, R.color.accent));
            } else if (state == VpsRelayClient.RouteCheckState.PASSED) {
                icon.setImageResource(R.drawable.ic_status_check);
                icon.setBackgroundResource(R.drawable.status_success_bg);
                icon.setImageTintList(ContextCompat.getColorStateList(activity, R.color.green));
                stateView.setText(R.string.relay_check_dc_passed);
                stateView.setTextColor(ContextCompat.getColor(activity, R.color.green));
            } else if (state == VpsRelayClient.RouteCheckState.WARNING) {
                icon.setImageResource(R.drawable.ic_status_error);
                icon.setBackgroundResource(R.drawable.status_warning_bg);
                icon.setImageTintList(ContextCompat.getColorStateList(activity, R.color.warning));
                stateView.setText(R.string.relay_check_dc_warning);
                stateView.setTextColor(ContextCompat.getColor(activity, R.color.warning));
            } else {
                icon.setImageResource(R.drawable.ic_status_error);
                icon.setBackgroundResource(R.drawable.status_error_bg);
                icon.setImageTintList(ContextCompat.getColorStateList(activity, R.color.red));
                stateView.setText(state == VpsRelayClient.RouteCheckState.TIMEOUT
                        ? R.string.relay_check_dc_timeout : R.string.relay_check_dc_failed);
                stateView.setTextColor(ContextCompat.getColor(activity, R.color.red));
            }
        }

        String shortLabel() {
            String scope = scopeLabel(target);
            return target.test()
                    ? activity.getString(R.string.relay_check_test_dc_title,
                            target.dc(), scope)
                    : activity.getString(R.string.relay_check_dc_title,
                            target.dc(), scope);
        }

        private String scopeLabel(VpsRelayClient.RouteTarget value) {
            return activity.getString(value.media()
                    ? R.string.relay_check_dc_media : R.string.relay_check_dc_main);
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
