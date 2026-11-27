// SPDX-License-Identifier: MIT
package dev.glassworks.stockprobe;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * The probe's own log, also kept in memory so {@code GET /log} can show it without adb (stock 1.24.0 lets no adb key
 * in until the in-place write). Nothing logged here may carry the stage secret, the serial, the adapter's name or a
 * Bluetooth address: the page is served on the Mirror's open setup network.
 */
final class ProbeLog {
    private static final int MAX = 300;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    private ProbeLog() {}

    static void i(String tag, String msg) { Log.i(tag, msg); keep("I", msg, null); }
    static void w(String tag, String msg) { Log.w(tag, msg); keep("W", msg, null); }
    static void w(String tag, String msg, Throwable t) { Log.w(tag, msg, t); keep("W", msg, t); }
    static void e(String tag, String msg, Throwable t) { Log.e(tag, msg, t); keep("E", msg, t); }

    private static void keep(String level, String msg, Throwable t) {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = time + " " + level + " " + msg + (t != null ? " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")" : "");
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX) LINES.removeFirst();
        }
    }

    /** The kept lines, oldest first. */
    static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) { for (String l : LINES) sb.append(l).append('\n'); }
        return sb.toString();
    }
}
