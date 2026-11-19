package com.macapk.loader;

import android.content.Intent;

public final class LaunchExtrasTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.putExtra("existing", "keep");
        LaunchExtras.applyBooleans(intent, null);
        require(!intent.hasExtra("enable-impeller"), "unset must preserve the engine default");
        LaunchExtras.applyBooleans(intent, "enable-impeller=false;trace-startup=true; spaced = FALSE");
        require(intent.hasExtra("enable-impeller"), "false must still be present");
        require(!intent.getBooleanExtra("enable-impeller", true), "false must be a Boolean");
        require(intent.getBooleanExtra("trace-startup", false), "true must be a Boolean");
        require(!intent.getBooleanExtra("spaced", true), "whitespace/case normalization");
        require("keep".equals(intent.getStringExtra("existing")), "unrelated extras survive");
        require(LaunchExtras.applyBooleans(intent, "broken;=true;empty=;invalid=maybe") == 4,
                "malformed entries are counted for the boot log");
        require(!intent.hasExtra("empty") && !intent.hasExtra("invalid"), "invalid values are not false");
        require(!intent.hasExtra(""), "empty keys are rejected");
        System.out.println("PASS: Boolean launch extras retain their types and reject malformed values");
    }
}
