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
    public void dc203DirectWsMapsToDc2LikeFlowseal() {
        assertArrayEquals(new String[]{
                "kws2-1.web.telegram.org",
                "kws2.web.telegram.org"
        }, TgConstants.wsDomains(203, true));
    }
}
