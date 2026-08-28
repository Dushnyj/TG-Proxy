package com.dushnyj.tgproxy;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;

/** Pins one Relay operation to the Android network that was active when it started. */
final class RelayNetworkBinding {
    private static volatile Context application;

    private RelayNetworkBinding() {}

    static void initialize(Context context) {
        application = context == null ? null : context.getApplicationContext();
    }

    static Binding capture() {
        Context app = application;
        if (app == null) return new Binding(null);
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    app.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? manager.getActiveNetwork() : null;
            return new Binding(active);
        } catch (Exception ignored) {
            return new Binding(null);
        }
    }

    static final class Binding {
        private final Network network;

        Binding(Network network) {
            this.network = network;
        }

        URLConnection openConnection(URL url) throws IOException {
            return network == null ? url.openConnection() : network.openConnection(url);
        }

        InetAddress[] resolveAll(String host) throws IOException {
            return network == null ? InetAddress.getAllByName(host) : network.getAllByName(host);
        }

        Socket newSocket() throws IOException {
            Socket socket = new Socket();
            if (network != null) network.bindSocket(socket);
            return socket;
        }

        boolean isBound() {
            return network != null;
        }
    }
}
