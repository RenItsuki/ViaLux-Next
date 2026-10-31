package com.visionguide.phone;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

final class SettingsDialog {
    interface OnSettingsChanged {
        void onChanged();
    }

    private SettingsDialog() {
    }

    static void show(Context context, AppSettings settings, OnSettingsChanged changed) {
        int pad = dp(context, 18);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, pad, pad, 4);
        panel.setBackgroundColor(0xFF111C28);

        // ── Detection / speech ──────────────────────────────────────────────
        panel.addView(section(context, "Detection & Speech"));
        panel.addView(help(context, "Tune how Via Lux speaks. Higher-priority danger can still interrupt, but unchanged objects stay quiet."));

        TextView speechLabel = label(context, "Speech delay: " + (settings.speechDelayMs / 1000f) + " sec");
        SeekBar speechDelay = new SeekBar(context);
        speechDelay.setMax(8);
        speechDelay.setProgress(Math.max(0, Math.min(8, Math.round(settings.speechDelayMs / 500f - 2))));
        speechDelay.setOnSeekBarChangeListener(simpleSeek(p -> speechLabel.setText("Speech delay: " + ((p + 2) * 0.5f) + " sec")));

        TextView repeatLabel = label(context, "Repeat same object: after " + (settings.repeatCooldownMs / 1000) + " sec");
        SeekBar repeatDelay = new SeekBar(context);
        repeatDelay.setMax(6);
        repeatDelay.setProgress(Math.max(0, Math.min(6, settings.repeatCooldownMs / 5000 - 1)));
        repeatDelay.setOnSeekBarChangeListener(simpleSeek(p -> repeatLabel.setText("Repeat same object: after " + ((p + 1) * 5) + " sec")));

        TextView motionText = label(context, "Motion sensitivity");
        Spinner motionSpinner = spinner(context, new String[]{"Strong motion only", "Balanced", "Sensitive"});
        motionSpinner.setSelection(settings.motionThreshold >= 0.070f ? 0 : settings.motionThreshold <= 0.040f ? 2 : 1);

        TextView distanceText = label(context, "Notify distance");
        Spinner distanceSpinner = spinner(context, new String[]{"Far or nearer", "Medium or nearer", "Close or nearer", "Very close only"});
        distanceSpinner.setSelection(settings.notifyAreaRatio >= 0.25f ? 3 : settings.notifyAreaRatio >= 0.10f ? 2 : settings.notifyAreaRatio >= 0.035f ? 1 : 0);

        TextView edgeLabel = label(context, "Ignore side edges: " + Math.round(settings.edgeIgnoreRatio * 100) + "%");
        SeekBar edgeSeek = new SeekBar(context);
        edgeSeek.setMax(20);
        edgeSeek.setProgress(Math.round((settings.edgeIgnoreRatio - 0.10f) * 100));
        edgeSeek.setOnSeekBarChangeListener(simpleSeek(p -> edgeLabel.setText("Ignore side edges: " + (p + 10) + "%")));

        CheckBox override = new CheckBox(context);
        override.setText("Priority override for people, vehicles, and strong motion");
        override.setTextColor(Color.WHITE);
        override.setChecked(settings.priorityOverride);

        panel.addView(speechLabel);
        panel.addView(speechDelay);
        panel.addView(help(context, "Minimum time between different normal voice messages."));
        panel.addView(repeatLabel);
        panel.addView(repeatDelay);
        panel.addView(help(context, "Prevents the same object/state from being spoken again and again."));
        panel.addView(motionText);
        panel.addView(motionSpinner);
        panel.addView(help(context, "Strong motion ignores small shake from walking."));
        panel.addView(distanceText);
        panel.addView(distanceSpinner);
        panel.addView(edgeLabel);
        panel.addView(edgeSeek);
        panel.addView(help(context, "Objects on the extreme side are less likely to collide with you."));
        panel.addView(override);

        // ── Online mode ─────────────────────────────────────────────────────
        panel.addView(section(context, "Online Mode (Roboflow)"));
        panel.addView(help(context, "Sends frames to Roboflow BlindAid Workflow. Requires internet. Do not enter API keys that should stay secret into shared devices."));
        CheckBox onlineCheck = new CheckBox(context);
        onlineCheck.setText("Enable online detection (Roboflow)");
        onlineCheck.setTextColor(Color.WHITE);
        onlineCheck.setChecked(settings.onlineModeEnabled);
        EditText apiKeyEdit = editText(context, settings.roboflowApiKey, "Roboflow API key (stored locally)");
        panel.addView(onlineCheck);
        panel.addView(apiKeyEdit);
        panel.addView(help(context, "Key is stored on this device only and never sent to any server other than Roboflow."));

        // ── Navigation ──────────────────────────────────────────────────────
        panel.addView(section(context, "Navigation (Google Maps)"));
        panel.addView(help(context, "Adds walking directions. Requires Google Maps API key with Directions API enabled and a billing account."));
        CheckBox navCheck = new CheckBox(context);
        navCheck.setText("Enable navigation mode");
        navCheck.setTextColor(Color.WHITE);
        navCheck.setChecked(settings.navigationEnabled);
        EditText mapsKeyEdit = editText(context, settings.mapsApiKey, "Maps API key");
        panel.addView(navCheck);
        panel.addView(mapsKeyEdit);
        panel.addView(help(context, "Walking routes may not include all pedestrian paths. Navigation complements camera awareness."));

        // ── NFC ─────────────────────────────────────────────────────────────
        panel.addView(section(context, "NFC Medicine Tags"));
        panel.addView(help(context, "Tap a registered NFC tag to hear the saved medicine name and strength. This is a label reminder only — it does not verify medicine safety."));
        CheckBox nfcCheck = new CheckBox(context);
        nfcCheck.setText("Enable NFC medicine reading");
        nfcCheck.setTextColor(Color.WHITE);
        nfcCheck.setChecked(settings.nfcEnabled);
        panel.addView(nfcCheck);

        // ── OCR ─────────────────────────────────────────────────────────────
        panel.addView(section(context, "OCR Text Reading"));
        panel.addView(help(context, "Point camera at signs or labels and press Read Text. OCR can misread text — always verify important information visually."));
        CheckBox ocrCheck = new CheckBox(context);
        ocrCheck.setText("Enable OCR text reading");
        ocrCheck.setTextColor(Color.WHITE);
        ocrCheck.setChecked(settings.ocrEnabled);
        panel.addView(ocrCheck);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(panel);

        new AlertDialog.Builder(context)
                .setTitle("Via Lux Settings")
                .setView(scroll)
                .setNegativeButton("Reset", (dialog, which) -> {
                    settings.resetDefaults();
                    changed.onChanged();
                })
                .setNeutralButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    // Detection / speech
                    settings.speechDelayMs    = (speechDelay.getProgress() + 2) * 500;
                    settings.repeatCooldownMs = (repeatDelay.getProgress() + 1) * 5000;
                    int motion = motionSpinner.getSelectedItemPosition();
                    settings.motionThreshold    = motion == 0 ? 0.080f : motion == 2 ? 0.035f : 0.060f;
                    settings.motionStreakFrames  = motion == 0 ? 5 : motion == 2 ? 3 : 4;
                    int distance = distanceSpinner.getSelectedItemPosition();
                    settings.notifyAreaRatio  = distance == 3 ? 0.25f : distance == 2 ? 0.10f : distance == 1 ? 0.035f : 0.015f;
                    settings.edgeIgnoreRatio  = (edgeSeek.getProgress() + 10) / 100f;
                    settings.priorityOverride = override.isChecked();
                    // Online
                    settings.onlineModeEnabled = onlineCheck.isChecked();
                    settings.roboflowApiKey    = apiKeyEdit.getText().toString().trim();
                    // Navigation
                    settings.navigationEnabled = navCheck.isChecked();
                    settings.mapsApiKey        = mapsKeyEdit.getText().toString().trim();
                    // NFC
                    settings.nfcEnabled = nfcCheck.isChecked();
                    // OCR
                    settings.ocrEnabled = ocrCheck.isChecked();
                    settings.save();
                    changed.onChanged();
                })
                .show();
    }

    private static TextView section(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(0xFF37D4A7);
        view.setTextSize(14f);
        view.setPadding(0, dp(context, 16), 0, dp(context, 4));
        return view;
    }

    private static TextView label(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(15f);
        view.setPadding(0, dp(context, 10), 0, dp(context, 6));
        return view;
    }

    private static TextView help(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(0xFF9FB0C3);
        view.setTextSize(12f);
        view.setPadding(0, 0, 0, dp(context, 8));
        return view;
    }

    private static EditText editText(Context context, String value, String hint) {
        EditText et = new EditText(context);
        et.setText(value);
        et.setHint(hint);
        et.setHintTextColor(0xFF607080);
        et.setTextColor(Color.WHITE);
        et.setTextSize(14f);
        et.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private static Spinner spinner(Context context, String[] values) {
        Spinner s = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, values);
        s.setAdapter(adapter);
        s.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private interface ProgressCallback {
        void onProgress(int progress);
    }

    private static SeekBar.OnSeekBarChangeListener simpleSeek(ProgressCallback cb) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) { cb.onProgress(p); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        };
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
