package com.dushnyj.tgproxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ProxyRestartReceiver extends BroadcastReceiver {
    static final String ACTION_RECOVER = "com.dushnyj.tgproxy.action.RECOVER";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_RECOVER.equals(intent.getAction())) return;
        if (ProxyServiceLauncher.restoreIfDesired(context, "watchdog")) {
            ProxyServiceLauncher.scheduleRegularRecovery(context);
        }
    }
}
