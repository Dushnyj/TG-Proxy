package com.dushnyj.tgproxy;

final class SettingsTransferException extends Exception {
    SettingsTransferException(String message) {
        super(message == null ? "" : message);
    }

    SettingsTransferException(String message, Throwable cause) {
        super(message == null ? "" : message, cause);
    }
}
