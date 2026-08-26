package com.dushnyj.tgproxy;

import android.content.SharedPreferences;

/** Durable user intent.  Process/service lifetime must never be treated as the desired state. */
final class ProxyRunStateStore {
    static final String KEY_DESIRED_RUNNING = "proxy_desired_running.v1";

    interface Store {
        boolean contains(String key);
        boolean getBoolean(String key, boolean fallback);
        boolean putBooleanSynchronously(String key, boolean value);
    }

    private final Store store;

    private ProxyRunStateStore(Store store) {
        this.store = store;
    }

    static ProxyRunStateStore fromPreferences(SharedPreferences preferences) {
        return new ProxyRunStateStore(new Store() {
            @Override public boolean contains(String key) {
                return preferences.contains(key);
            }

            @Override public boolean getBoolean(String key, boolean fallback) {
                return preferences.getBoolean(key, fallback);
            }

            @Override public boolean putBooleanSynchronously(String key, boolean value) {
                return preferences.edit().putBoolean(key, value).commit();
            }
        });
    }

    static ProxyRunStateStore inMemory() {
        return new ProxyRunStateStore(new Store() {
            private boolean value;
            private boolean initialized;

            @Override public boolean contains(String key) {
                return initialized;
            }

            @Override public boolean getBoolean(String key, boolean fallback) {
                return value;
            }

            @Override public boolean putBooleanSynchronously(String key, boolean next) {
                value = next;
                initialized = true;
                return true;
            }
        });
    }

    boolean desiredRunning() {
        return store.getBoolean(KEY_DESIRED_RUNNING, false);
    }

    boolean hasDesiredState() {
        return store.contains(KEY_DESIRED_RUNNING);
    }

    boolean setDesiredRunning(boolean running) {
        return store.putBooleanSynchronously(KEY_DESIRED_RUNNING, running);
    }
}
