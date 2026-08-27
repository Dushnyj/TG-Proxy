package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class TgConstantsTest {
    @Test
    public void mediaWsDomainsUseFlowsealOrder() {
        assertArrayEquals(new String[]{
                "kws4-1.web.telegram.org",
                "kws4.web.telegram.org"
        }, TgConstants.wsDomains(4, true));
    }

    @Test
    public void nonMediaWsDomainsUseFlowsealOrder() {
        assertArrayEquals(new String[]{
                "kws4.web.telegram.org",
                "kws4-1.web.telegram.org"
        }, TgConstants.wsDomains(4, false));
    }

    @Test
    public void unknownAndCdnDcNeverMasqueradeAsDc2() {
        assertArrayEquals(new String[0], TgConstants.wsDomains(203, true));
        assertArrayEquals(new String[0], TgConstants.wsDomains(204, false));
    }
}
