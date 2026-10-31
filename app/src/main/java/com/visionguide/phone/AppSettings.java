package com.visionguide.phone;

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {
    private static final String PREFS = "via_lux_settings";

    private final SharedPreferences prefs;

    // ── Existing speech / detection settings ───────────────────────────────
    int speechDelayMs;
    int repeatCooldownMs;
    float motionThreshold;
    int motionStreakFrames;
    float notifyAreaRatio;
    float edgeIgnoreRatio;
    boolean priorityOverride;

    // ── Online mode (Roboflow) ──────────────────────────────────────────────
    boolean onlineModeEnabled;   // send frames to Roboflow Workflow
    String roboflowApiKey;       // loaded from EncryptedSharedPreferences or empty

    // ── Navigation (Google Maps) ────────────────────────────────────────────
    boolean navigationEnabled;
    String mapsApiKey;           // loaded from local config or empty

    // ── NFC medicine ────────────────────────────────────────────────────────
    boolean nfcEnabled;

    // ── OCR ─────────────────────────────────────────────────────────────────
    boolean ocrEnabled;
    int ocrIntervalMs;           // minimum ms between OCR runs

    AppSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    void load() {
        speechDelayMs      = prefs.getInt("speechDelayMs",      2000);
        repeatCooldownMs   = prefs.getInt("repeatCooldownMs",   10000);
        motionThreshold    = prefs.getFloat("motionThreshold",  0.040f);
        motionStreakFrames  = prefs.getInt("motionStreakFrames", 3);
        notifyAreaRatio    = prefs.getFloat("notifyAreaRatio",  0.035f);
        edgeIgnoreRatio    = prefs.getFloat("edgeIgnoreRatio",  0.18f);
        priorityOverride   = prefs.getBoolean("priorityOverride", true);

        onlineModeEnabled  = prefs.getBoolean("onlineModeEnabled", false);
        roboflowApiKey     = prefs.getString("roboflowApiKey",   "");

        navigationEnabled  = prefs.getBoolean("navigationEnabled", true);
        mapsApiKey         = prefs.getString("mapsApiKey",        "");

        nfcEnabled         = prefs.getBoolean("nfcEnabled",  true);

        ocrEnabled         = prefs.getBoolean("ocrEnabled",   true);
        ocrIntervalMs      = prefs.getInt("ocrIntervalMs",    2500);
    }

    void save() {
        prefs.edit()
                .putInt("speechDelayMs",     speechDelayMs)
                .putInt("repeatCooldownMs",  repeatCooldownMs)
                .putFloat("motionThreshold", motionThreshold)
                .putInt("motionStreakFrames", motionStreakFrames)
                .putFloat("notifyAreaRatio", notifyAreaRatio)
                .putFloat("edgeIgnoreRatio", edgeIgnoreRatio)
                .putBoolean("priorityOverride", priorityOverride)
                .putBoolean("onlineModeEnabled", onlineModeEnabled)
                .putString("roboflowApiKey",  roboflowApiKey)
                .putBoolean("navigationEnabled", navigationEnabled)
                .putString("mapsApiKey",       mapsApiKey)
                .putBoolean("nfcEnabled",      nfcEnabled)
                .putBoolean("ocrEnabled",      ocrEnabled)
                .putInt("ocrIntervalMs",       ocrIntervalMs)
                .apply();
    }

    void resetDefaults() {
        speechDelayMs     = 2000;
        repeatCooldownMs  = 10000;
        motionThreshold   = 0.040f;
        motionStreakFrames = 3;
        notifyAreaRatio   = 0.035f;
        edgeIgnoreRatio   = 0.18f;
        priorityOverride  = true;
        onlineModeEnabled = false;
        navigationEnabled = true;
        nfcEnabled        = true;
        ocrEnabled        = true;
        ocrIntervalMs     = 3000;
        // Note: API keys are NOT reset to avoid losing credentials
        save();
    }

    String notifyDistanceLabel() {
        if (notifyAreaRatio >= 0.25f) return "Very close only";
        if (notifyAreaRatio >= 0.10f) return "Close or nearer";
        if (notifyAreaRatio >= 0.035f) return "Medium or nearer";
        return "Far or nearer";
    }

    String motionLabel() {
        if (motionThreshold >= 0.055f) return "Strong motion only";
        if (motionThreshold <= 0.025f) return "Sensitive";
        return "Balanced";
    }
}
