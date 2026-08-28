package com.dushnyj.tgproxy;

import androidx.annotation.StringRes;

import java.util.Locale;

/** Converts internal transfer parser errors into stable, user-facing messages. */
final class SettingsTransferErrorText {
    private SettingsTransferErrorText() {
    }

    @StringRes
    static int messageRes(Throwable error) {
        String message = error == null || error.getMessage() == null
                ? ""
                : error.getMessage().trim().toLowerCase(Locale.ROOT);
        if (message.contains("password")) {
            return R.string.import_error_password;
        }
        if (message.contains("too large")) {
            return R.string.import_error_too_large;
        }
        if (message.contains("relay is not configured")
                || message.contains("invalid vps relay")
                || message.contains("not a vps relay")) {
            return R.string.import_error_relay_invalid;
        }
        if (message.contains("unsupported")
                || message.contains("damaged")
                || message.contains("decode")
                || message.contains("invalid")
                || message.contains("field is not allowed")
                || message.contains("route must remain")) {
            return R.string.import_error_invalid;
        }
        return R.string.import_error_read;
    }
}
