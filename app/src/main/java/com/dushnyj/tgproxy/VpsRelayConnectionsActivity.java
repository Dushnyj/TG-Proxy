package com.dushnyj.tgproxy;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.util.List;

/** Selects the primary Relay credential and enabled automatic failover credentials. */
public final class VpsRelayConnectionsActivity extends AppCompatActivity {
    private static final String EXTRA_PROFILE_KEY = "profile_key";

    private LinearLayout content;
    private String profileKey = "";

    static Intent intent(Context context, String profileKey) {
        return new Intent(context, VpsRelayConnectionsActivity.class)
                .putExtra(EXTRA_PROFILE_KEY, profileKey == null ? "" : profileKey);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_vps_relay_connections);
        content = findViewById(R.id.content_relay_connections);
        profileKey = clean(getIntent().getStringExtra(EXTRA_PROFILE_KEY));
        findViewById(R.id.btn_relay_connections_back).setOnClickListener(view -> finish());
        render();
    }

    private void render() {
        VpsRelayStore store = VpsRelayStore.fromContext(this);
        List<VpsRelayStore.Record> records = store.relays();
        String primaryId = clean(store.selectedRelayId(profileKey));
        content.removeAllViews();

        LinearLayout note = card();
        note.addView(text(getString(R.string.vps_connections_how_title), 16,
                R.color.text_primary, true));
        TextView noteBody = text(getString(R.string.vps_connections_how_note), 13,
                R.color.text_secondary, false);
        noteBody.setLineSpacing(0f, 1.08f);
        note.addView(noteBody, topMargin(7));
        content.addView(note);

        if (records.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text(getString(R.string.vps_connections_empty_title), 16,
                    R.color.text_primary, true));
            empty.addView(text(getString(R.string.vps_connections_empty_note), 13,
                    R.color.text_secondary, false), topMargin(7));
            content.addView(empty, topMargin(14));
            return;
        }

        for (VpsRelayStore.Record record : records) addConnection(store, record, primaryId);
    }

    private void addConnection(VpsRelayStore store, VpsRelayStore.Record record,
                               String primaryId) {
        VpsRelayConfig relay = record.config();
        boolean primary = record.id().equals(primaryId);
        LinearLayout card = card();

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text(relay.name(), 16, R.color.text_primary, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView badge = text(getString(primary ? R.string.vps_connections_primary
                        : relay.isEnabled() ? R.string.vps_connections_backup
                        : R.string.vps_connections_disabled),
                11, primary ? R.color.green
                        : relay.isEnabled() ? R.color.accent : R.color.text_secondary, true);
        badge.setBackgroundResource(primary ? R.drawable.status_success_bg
                : relay.isEnabled() ? R.drawable.status_info_bg : R.drawable.status_neutral_bg);
        heading.addView(badge);
        card.addView(heading);

        TextView endpoint = text(relay.host() + ":" + relay.port() + relay.path(),
                13, R.color.text_secondary, false);
        endpoint.setSingleLine(true);
        endpoint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(endpoint, topMargin(7));
        card.addView(text(getString(R.string.vps_connections_token, relay.maskedToken()),
                12, R.color.text_hint, false), topMargin(4));

        CheckBox enabled = new CheckBox(this);
        enabled.setText(primary ? R.string.vps_connections_enabled_primary
                : R.string.vps_connections_enabled_backup);
        enabled.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        enabled.setTextSize(13f);
        enabled.setButtonTintList(ContextCompat.getColorStateList(this, R.color.accent));
        enabled.setChecked(relay.isEnabled());
        enabled.setOnCheckedChangeListener((button, checked) -> {
            boolean saved = store.setRelayEnabled(record.id(), checked);
            if (saved && checked && clean(store.selectedRelayId(profileKey)).isEmpty()) {
                saved = store.makePrimary(profileKey, record.id());
            }
            if (!saved) Toast.makeText(this, R.string.settings_save_failed,
                    Toast.LENGTH_LONG).show();
            render();
        });
        card.addView(enabled, topMargin(8));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if (!primary) {
            MaterialButton makePrimary = outlinedButton(R.string.vps_connections_make_primary);
            makePrimary.setOnClickListener(view -> {
                if (!store.makePrimary(profileKey, record.id())) {
                    Toast.makeText(this, R.string.settings_save_failed,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, R.string.vps_connections_primary_changed,
                        Toast.LENGTH_SHORT).show();
                render();
            });
            actions.addView(makePrimary, new LinearLayout.LayoutParams(0, dp(48), 1f));
        }
        MaterialButton share = outlinedButton(R.string.share_export);
        share.setOnClickListener(view -> RelayShareSheet.show(this, relay.withEnabled(true)));
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
                0, dp(48), primary ? 1f : 0.72f);
        if (!primary) shareParams.setMarginStart(dp(8));
        actions.addView(share, shareParams);
        card.addView(actions, topMargin(8));
        content.addView(card, topMargin(14));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.owner_card_bg);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        return card;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(ContextCompat.getColor(this, color));
        text.setIncludeFontPadding(false);
        if (bold) text.setTypeface(text.getTypeface(), Typeface.BOLD);
        return text;
    }

    private MaterialButton outlinedButton(int textRes) {
        MaterialButton button = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(textRes);
        button.setAllCaps(false);
        button.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        button.setTextSize(12f);
        button.setCornerRadius(dp(12));
        button.setStrokeColor(ContextCompat.getColorStateList(this, R.color.input_border));
        return button;
    }

    private LinearLayout.LayoutParams topMargin(int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(marginDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
