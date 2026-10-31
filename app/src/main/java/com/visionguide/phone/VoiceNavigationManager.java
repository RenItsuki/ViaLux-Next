package com.visionguide.phone;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * VoiceNavigationManager — Continuous, Hands-Free, Zero-Physical-Contact Voice Controller.
 *
 * Designed specifically for low-vision and blind users:
 *   - Continuous listening loop: automatically restarts listening without needing any screen taps.
 *   - Anti-feedback coordination: pauses listening while TextToSpeech is actively speaking
 *     so the microphone never picks up the phone's own voice output.
 *   - Proximity Wave detection: waving a hand over the phone (zero physical touch) triggers listening/repeat.
 *
 * Recognized Voice Commands:
 *   - "Start" / "Start camera" / "Open camera"
 *   - "Stop" / "Stop camera" / "Pause"
 *   - "Read" / "Read text" / "Read label" / "What does it say"
 *   - "Repeat" / "Say again"
 *   - "Navigate to [place]" (e.g. "Navigate to pharmacy", "Directions to Central Park")
 *   - "Stop navigation" / "Cancel route"
 *   - "Settings"
 *   - "Help" / "What can I say"
 */
final class VoiceNavigationManager implements SensorEventListener {
    private static final String TAG = "VoiceNavManager";

    interface CommandListener {
        void onStartCommand();
        void onStopCommand();
        void onReadTextCommand();
        void onRepeatCommand();
        void onNavigateCommand(String destination);
        void onStopNavCommand();
        void onSettingsCommand();
        void onHelpCommand();
        void onStatusUpdate(String status);
        void onListeningStateChanged(boolean isListening);
    }

    private final Context context;
    private final CommandListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;

    private boolean continuousEnabled = true;
    private boolean isListening = false;
    private boolean isTtsSpeaking = false;
    private boolean destroyed = false;

    // Zero-contact Proximity Wave sensor
    private SensorManager sensorManager;
    private Sensor proximitySensor;
    private long lastWaveAt = 0;

    VoiceNavigationManager(Context context, CommandListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        initRecognizer();
        initProximitySensor();
    }

    private void initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition is not available on this device.");
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                isListening = true;
                listener.onListeningStateChanged(true);
                listener.onStatusUpdate("🎙️ Hands-Free Listening... Say 'Start', 'Read text', or 'Navigate to...'");
            }

            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                isListening = false;
                listener.onListeningStateChanged(false);
            }

            @Override
            public void onError(int error) {
                isListening = false;
                listener.onListeningStateChanged(false);

                // In continuous mode, automatically loop back and resume listening
                if (continuousEnabled && !destroyed && !isTtsSpeaking) {
                    mainHandler.postDelayed(() -> restartListeningLoop(), 400);
                }
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;
                listener.onListeningStateChanged(false);
                if (results != null) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String spoken = matches.get(0).toLowerCase(Locale.US).trim();
                        Log.d(TAG, "Hands-free speech heard: " + spoken);
                        handleSpokenCommand(spoken);
                    }
                }

                // Automatically loop back to continue listening hands-free
                if (continuousEnabled && !destroyed && !isTtsSpeaking) {
                    mainHandler.postDelayed(() -> restartListeningLoop(), 600);
                }
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    // ── Anti-Feedback Coordination with TextToSpeech ───────────────────────

    /**
     * Called when TextToSpeech starts or stops speaking.
     * Prevents the phone's microphone from hearing its own spoken announcements.
     */
    void setTtsSpeaking(boolean speaking) {
        this.isTtsSpeaking = speaking;
        if (speaking) {
            // Silence speech recognizer while phone speaks
            if (isListening && speechRecognizer != null) {
                try {
                    speechRecognizer.cancel();
                } catch (Exception ignored) {}
                isListening = false;
                listener.onListeningStateChanged(false);
            }
        } else {
            // TTS finished speaking — resume hands-free listening loop
            if (continuousEnabled && !destroyed) {
                mainHandler.postDelayed(() -> restartListeningLoop(), 350);
            }
        }
    }

    /** Trigger or resume listening. */
    void startListening() {
        if (destroyed || isTtsSpeaking) return;
        if (speechRecognizer == null) {
            initRecognizer();
        }
        if (speechRecognizer != null && !isListening) {
            try {
                speechRecognizer.cancel();
                speechRecognizer.startListening(recognizerIntent);
            } catch (Exception e) {
                Log.e(TAG, "Error starting voice recognition: " + e.getMessage());
                // Retry after brief debounce
                mainHandler.postDelayed(() -> restartListeningLoop(), 1000);
            }
        }
    }

    private void restartListeningLoop() {
        if (!continuousEnabled || destroyed || isTtsSpeaking) return;
        startListening();
    }

    void stopListening() {
        if (speechRecognizer != null && isListening) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {}
        }
        isListening = false;
        listener.onListeningStateChanged(false);
    }

    void setContinuousEnabled(boolean enabled) {
        this.continuousEnabled = enabled;
        if (enabled) {
            restartListeningLoop();
        } else {
            stopListening();
        }
    }

    boolean isListening() {
        return isListening;
    }

    // ── Command Parser ─────────────────────────────────────────────────────

    private void handleSpokenCommand(String spoken) {
        // Remove conversational filler ("hey via lux", "please", "can you", etc.)
        String clean = spoken
                .replaceFirst("^hey\\s+via\\s+lux\\s*", "")
                .replaceFirst("^via\\s+lux\\s*", "")
                .replaceFirst("^please\\s*", "")
                .replaceFirst("^can\\s+you\\s*", "")
                .trim();

        // 1. Start Camera / Detection
        if (clean.equals("start") || clean.contains("start camera") || clean.contains("start detection")
                || clean.contains("open camera") || clean.contains("begin") || clean.contains("turn on camera")) {
            listener.onStatusUpdate("✓ Command: Start Camera");
            listener.onStartCommand();
            return;
        }

        // 2. Stop Camera / Detection
        if (clean.equals("stop") || clean.contains("stop camera") || clean.contains("pause")
                || clean.contains("freeze") || clean.contains("turn off camera") || clean.contains("quit detection")) {
            listener.onStatusUpdate("✓ Command: Stop Camera");
            listener.onStopCommand();
            return;
        }

        // 3. Read Text / OCR
        if (clean.contains("read") || clean.contains("text") || clean.contains("ocr")
                || clean.contains("label") || clean.contains("sign") || clean.contains("what does it say")) {
            listener.onStatusUpdate("✓ Command: Read Text");
            listener.onReadTextCommand();
            return;
        }

        // 4. Repeat
        if (clean.contains("repeat") || clean.contains("say again") || clean.contains("what was that")
                || clean.equals("again")) {
            listener.onStatusUpdate("✓ Command: Repeat");
            listener.onRepeatCommand();
            return;
        }

        // 5. Stop Navigation
        if (clean.contains("stop nav") || clean.contains("cancel nav") || clean.contains("end nav")
                || clean.contains("stop navigation") || clean.contains("cancel navigation")) {
            listener.onStatusUpdate("✓ Command: Stop Navigation");
            listener.onStopNavCommand();
            return;
        }

        // 6. Walking Navigation to Destination
        if (clean.startsWith("navigate to") || clean.startsWith("directions to")
                || clean.startsWith("take me to") || clean.startsWith("walk to")
                || clean.startsWith("go to") || clean.startsWith("navigate")) {
            String dest = clean
                    .replaceFirst("^navigate to\\s*", "")
                    .replaceFirst("^directions to\\s*", "")
                    .replaceFirst("^take me to\\s*", "")
                    .replaceFirst("^walk to\\s*", "")
                    .replaceFirst("^go to\\s*", "")
                    .replaceFirst("^navigate\\s*", "")
                    .trim();

            if (!dest.isEmpty()) {
                listener.onStatusUpdate("✓ Navigating to: " + dest);
                listener.onNavigateCommand(dest);
                return;
            }
        }

        // 7. Settings
        if (clean.contains("settings") || clean.contains("preferences") || clean.contains("options")) {
            listener.onStatusUpdate("✓ Command: Settings");
            listener.onSettingsCommand();
            return;
        }

        // 8. Help
        if (clean.contains("help") || clean.contains("command") || clean.contains("what can i say")) {
            listener.onHelpCommand();
            return;
        }

        listener.onStatusUpdate("Heard: \"" + spoken + "\" (Say 'Help' for commands)");
    }

    // ── Zero-Physical-Contact Proximity Wave Sensor ─────────────────────────

    private void initProximitySensor() {
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
                if (proximitySensor != null) {
                    sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Proximity sensor unavailable: " + e.getMessage());
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            float distance = event.values[0];
            float maxRange = proximitySensor != null ? proximitySensor.getMaximumRange() : 5f;
            long now = System.currentTimeMillis();

            // Hand waved near sensor (< maxRange)
            if (distance < maxRange && now - lastWaveAt > 1800) {
                lastWaveAt = now;
                Log.d(TAG, "Zero-touch proximity wave detected.");
                // Immediately awakens listening loop or repeats last guidance
                startListening();
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ── Lifecycle ──────────────────────────────────────────────────────────

    void shutdown() {
        destroyed = true;
        if (sensorManager != null) {
            try {
                sensorManager.unregisterListener(this);
            } catch (Exception ignored) {}
        }
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
            speechRecognizer = null;
        }
    }
}
