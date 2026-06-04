package com.dushnyj.tgproxy;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class DiagnosticsLog {
    private static final int MAX_EVENTS = 80;
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();

    private DiagnosticsLog() {
    }

    static void record(String event) {
        String normalized = singleLine(event);
        if (normalized.isEmpty()) return;
        synchronized (EVENTS) {
            while (EVENTS.size() >= MAX_EVENTS) {
                EVENTS.removeFirst();
            }
            EVENTS.addLast(formatTimestamp(System.currentTimeMillis()) + " " + normalized);
        }
    }

    static List<String> snapshot() {
        synchronized (EVENTS) {
            return new ArrayList<>(EVENTS);
        }
    }

    static void clear() {
        synchronized (EVENTS) {
            EVENTS.clear();
        }
    }

    static void clearForTests() {
        clear();
    }

    private static String singleLine(String value) {
        if (value == null) return "";
        return value.trim().replace('\r', ' ').replace('\n', ' ');
    }

    private static String formatTimestamp(long timeMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(timeMs));
    }
}
