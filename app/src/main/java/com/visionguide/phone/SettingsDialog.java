package com.visionguide.phone;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
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

        panel.addView(help(context, "Tune how Via Lux speaks. Higher-priority danger can still interrupt, but unchanged objects stay quiet."));

        TextView speechLabel = label(context, "Speech delay: " + (settings.speechDelayMs / 1000f) + " sec");
        SeekBar speechDelay = new SeekBar(context);
        speechDelay.setMax(5);
        speechDelay.setProgress(Math.max(0, Math.min(5, settings.speechDelayMs / 500 - 2)));
        speechDelay.setOnSeekBarChangeListener(simpleSeek((progress) ->
                speechLabel.setText("Speech delay: " + ((progress + 2) * 0.5f) + " sec")));

        TextView repeatLabel = label(context, "Repeat same object: after " + (settings.repeatCooldownMs / 1000) + " sec");
        SeekBar repeatDelay = new SeekBar(context);
        repeatDelay.setMax(6);
        repeatDelay.setProgress(Math.max(0, Math.min(6, settings.repeatCooldownMs / 5000 - 1)));
        repeatDelay.setOnSeekBarChangeListener(simpleSeek((progress) ->
                repeatLabel.setText("Repeat same object: after " + ((progress + 1) * 5) + " sec")));

        TextView motionText = label(context, "Motion priority");
        Spinner motionSpinner = spinner(context, new String[]{"Strong motion only", "Balanced", "Sensitive"});
        motionSpinner.setSelection(settings.motionThreshold >= 0.055f ? 0 : settings.motionThreshold <= 0.025f ? 2 : 1);

        TextView distanceText = label(context, "Notify distance");
        Spinner distanceSpinner = spinner(context, new String[]{"Far or nearer", "Medium or nearer", "Close or nearer", "Very close only"});
        distanceSpinner.setSelection(settings.notifyAreaRatio >= 0.25f ? 3 : settings.notifyAreaRatio >= 0.10f ? 2 : settings.notifyAreaRatio >= 0.035f ? 1 : 0);

        TextView edgeLabel = label(context, "Ignore side edges: " + Math.round(settings.edgeIgnoreRatio * 100) + "%");
        SeekBar edgeSeek = new SeekBar(context);
        edgeSeek.setMax(20);
        edgeSeek.setProgress(Math.round((settings.edgeIgnoreRatio - 0.10f) * 100));
        edgeSeek.setOnSeekBarChangeListener(simpleSeek((progress) ->
                edgeLabel.setText("Ignore side edges: " + (progress + 10) + "%")));

        CheckBox override = new CheckBox(context);
        override.setText("Allow priority override for people, vehicles, and strong motion");
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
        panel.addView(help(context, "Far objects can be shown without giving left/right movement commands."));
        panel.addView(edgeLabel);
        panel.addView(edgeSeek);
        panel.addView(help(context, "Objects on the extreme side are usually less likely to collide with you."));
        panel.addView(override);

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
                    settings.speechDelayMs = (speechDelay.getProgress() + 2) * 500;
                    settings.repeatCooldownMs = (repeatDelay.getProgress() + 1) * 5000;
                    int motion = motionSpinner.getSelectedItemPosition();
                    settings.motionThreshold = motion == 0 ? 0.060f : motion == 2 ? 0.025f : 0.040f;
                    settings.motionStreakFrames = motion == 0 ? 3 : motion == 2 ? 2 : 3;
                    int distance = distanceSpinner.getSelectedItemPosition();
                    settings.notifyAreaRatio = distance == 3 ? 0.25f : distance == 2 ? 0.10f : distance == 1 ? 0.035f : 0.015f;
                    settings.edgeIgnoreRatio = (edgeSeek.getProgress() + 10) / 100f;
                    settings.priorityOverride = override.isChecked();
                    settings.save();
                    changed.onChanged();
                })
                .show();
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

    private static Spinner spinner(Context context, String[] values) {
        Spinner spinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, values);
        spinner.setAdapter(adapter);
        spinner.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return spinner;
    }

    private interface ProgressCallback {
        void onProgress(int progress);
    }

    private static SeekBar.OnSeekBarChangeListener simpleSeek(ProgressCallback callback) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { callback.onProgress(progress); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        };
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
