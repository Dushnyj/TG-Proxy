package com.dushnyj.tgproxy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SettingsTransferErrorTextTest {
    @Test
    public void parserDetailsAreMappedToFriendlyMessages() {
        assertEquals(R.string.import_error_invalid,
                SettingsTransferErrorText.messageRes(
                        new SettingsTransferException("unsupported transfer format")));
        assertEquals(R.string.import_error_password,
                SettingsTransferErrorText.messageRes(
                        new SettingsTransferException("wrong password or damaged profile")));
        assertEquals(R.string.import_error_too_large,
                SettingsTransferErrorText.messageRes(
                        new SettingsTransferException("transfer payload is too large")));
        assertEquals(R.string.import_error_relay_invalid,
                SettingsTransferErrorText.messageRes(
                        new SettingsTransferException("invalid VPS Relay profile")));
        assertEquals(R.string.import_error_read,
                SettingsTransferErrorText.messageRes(new IllegalStateException("boom")));
    }
}
