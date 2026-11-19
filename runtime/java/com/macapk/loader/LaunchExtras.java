package com.macapk.loader;

import android.content.Intent;

/** Typed equivalents of Android's `am start --ez key true|false`. */
final class LaunchExtras {
    private LaunchExtras() {}

    /** Returns the number of malformed entries skipped. */
    static int applyBooleans(Intent intent, String encoded) {
        if (encoded == null || encoded.isEmpty()) return 0;
        int rejected = 0;
        for (String entry : encoded.split(";")) {
            int separator = entry.indexOf('=');
            if (separator < 1) {
                ++rejected;
                continue;
            }
            String key = entry.substring(0, separator).trim();
            String value = entry.substring(separator + 1).trim();
            if (key.isEmpty() || !("true".equalsIgnoreCase(value)
                                  || "false".equalsIgnoreCase(value))) {
                ++rejected;
                continue;
            }
            intent.putExtra(key, Boolean.parseBoolean(value));
        }
        return rejected;
    }
}
