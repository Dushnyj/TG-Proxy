package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VpsRelayTestPresentationTest {
    @Test public void productionSuccessWithTestDcWarningIsStillSuccess() {
        VpsRelayCheckResult result = VpsRelayCheckResult.ok(
                "production routes ok", "1.0.5", "test DC3 main=deadline exceeded",
                VpsRelayCapabilities.unknown());
        VpsRelayTestPresentation view = VpsRelayTestPresentation.from(result);
        assertEquals(VpsRelayTestPresentation.Kind.SUCCESS_WITH_TEST_DC_WARNING, view.kind());
        assertTrue(view.successful());
    }

    @Test public void productionTelegramFailureHasSpecificExplanation() {
        VpsRelayCheckResult result = VpsRelayCheckResult.of(
                VpsRelayCheckResult.Status.UNAVAILABLE,
                "production DC4 media telegram probe deadline exceeded");
        assertEquals(VpsRelayTestPresentation.Kind.TELEGRAM_DC,
                VpsRelayTestPresentation.from(result).kind());
    }

    @Test public void rejectedTokenIsNotReportedAsNetworkFailure() {
        VpsRelayCheckResult result = VpsRelayCheckResult.of(
                VpsRelayCheckResult.Status.WRONG_TOKEN, "HTTP 401");
        assertEquals(VpsRelayTestPresentation.Kind.WRONG_TOKEN,
                VpsRelayTestPresentation.from(result).kind());
    }
}
