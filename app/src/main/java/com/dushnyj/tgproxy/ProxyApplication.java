package com.dushnyj.tgproxy;

import android.app.Application;

public final class ProxyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        RelayClientMetadata.initialize(this);
        ProcessExitTracker.collect(this);
    }
}
