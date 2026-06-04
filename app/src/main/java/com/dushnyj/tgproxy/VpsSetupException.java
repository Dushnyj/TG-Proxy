package com.dushnyj.tgproxy;

final class VpsSetupException extends Exception {
    VpsSetupException(String message) {
        super(message == null ? "" : message);
    }

    VpsSetupException(String message, Throwable cause) {
        super(message == null ? "" : message, cause);
    }
}
