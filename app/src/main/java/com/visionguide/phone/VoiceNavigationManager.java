package com.visionguide.phone;

import android.app.Activity;
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
 * VoiceNavigationManager — Continuous, Hands-Free Voice Controller.
 *
 * Designed for low-vision and blind users:
 *   - Continuous listening loop: automatically restarts listening without needing physical screen touches.
 *   - Extended silence sensitivity: does not abruptly cut off speech after pauses.
 *   - Live partial results: immediate visual feedback while the user is speaking.
 *   - Anti-feedback coordination: pauses speech recognition during TextToSpeech output with safety watchdog.
 *   - Zero-touch proximity wave: waving hand near the phone re-awakens listening immediately.
 *
 * Supported Voice Commands:
 *   - "Start" / "Start camera" / "Open camera"
 *   - "Stop" / "Stop camera" / "Pause"
 *   - "Read" / "Read text" / "Yes" / "Read it" (confirms reading detected text)
 *   - "Take me to [place]" / "Navigate to [place]" (e.g. "Take me to Shaheed Sukhdev College")
 *   - "Open Google Maps" / "Show map"
 *   - "Stop navigation" / "Cancel route"
 *   - "Check NFC" / "Scan medicine" / "Read tag"
 *   - "Repeat" / "Say again"
 *   - "Options" / "Help" / "What can I say"
 *   - "Settings"
 */
final class VoiceNavigationManager implements SensorEventListener {
    private static final String TAG = "VoiceNavManager";

    interface CommandListener {
        void onStartCommand();
        void onStopCommand();
        void onReadTextCommand();
        void onTextConfirmedCommand();
        void onRepeatCommand();
        void onNavigateCommand(String destination);
        void onOpenMapsCommand();
        void onStopNavCommand();
        void onCheckNfcCommand();
        void onSettingsCommand();
        void onHelpCommand();
        void onStatusUpdate(String status);
        void onListeningStateChanged(boolean isListening);
        void onPartialSpeechHeard(String partial);
        void onSceneAssistantQuery(String query);
    }

    private final Activity activity;
    private final CommandListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;

    private boolean continuousEnabled = false;
    private boolean isListening = false;
    private boolean isTtsSpeaking = false;
    private boolean destroyed = false;

    // Zero-contact Proximity Wave sensor
    private SensorManager sensorManager;
    private Sensor proximitySensor;
    private long lastWaveAt = 0;

    // Watchdog to prevent mic from getting permanently muted if TTS fails to report done
    private final Runnable ttsWatchdog = () -> {
        if (isTtsSpeaking) {
            Log.w(TAG, "TTS watchdog fired: resetting isTtsSpeaking to false");
            isTtsSpeaking = false;
            restartListeningLoop();
        }
    };

    // Watchdog to ensure listening doesn't get stuck indefinitely if recognizer hangs
    private final Runnable listeningTimeoutWatchdog;

    VoiceNavigationManager(Activity activity, CommandListener listener) {
        this.activity = activity;
        this.listener = listener;
        this.listeningTimeoutWatchdog = () -> {
            if (isListening) {
                Log.w(TAG, "Listening timeout watchdog fired: stopping listening");
                stopListening();
                if (this.listener != null) {
                    this.listener.onStatusUpdate("Listening timed out. Tap 'Press to Speak' when ready.");
                }
            }
        };
        initRecognizer();
        initProximitySensor();
    }

    private void initRecognizer() {
        try {
            if (speechRecognizer != null) {
                speechRecognizer.destroy();
                speechRecognizer = null;
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity);
        } catch (Exception e) {
            Log.w(TAG, "Speech recognition initialization failed: " + e.getMessage());
            return;
        }

        if (speechRecognizer == null) {
            Log.w(TAG, "Speech recognition is not available on this device.");
            return;
        }

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                mainHandler.removeCallbacks(listeningTimeoutWatchdog);
                mainHandler.postDelayed(listeningTimeoutWatchdog, 10000);
                isListening = true;
                listener.onListeningStateChanged(true);
                listener.onStatusUpdate("🎙️ Listening... Speak now! (e.g. 'Is there any car?', 'Take me to...', 'Read text')");
            }

            @Override public void onBeginningOfSpeech() {
                mainHandler.removeCallbacks(listeningTimeoutWatchdog);
                mainHandler.postDelayed(listeningTimeoutWatchdog, 12000);
            }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                mainHandler.removeCallbacks(listeningTimeoutWatchdog);
                isListening = false;
                listener.onListeningStateChanged(false);
                listener.onStatusUpdate("⏳ Processing what you said...");
            }

            @Override
            public void onError(int error) {
                mainHandler.removeCallbacks(listeningTimeoutWatchdog);
                isListening = false;
                listener.onListeningStateChanged(false);

                String errorMsg;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                        errorMsg = "Didn't catch that. Tap 'Press to Speak' and try again.";
                        break;
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        errorMsg = "No speech heard. Tap 'Press to Speak' when ready.";
                        break;
                    case SpeechRecognizer.ERROR_AUDIO:
                        errorMsg = "Audio recording error. Tap to retry.";
                        break;
                    case SpeechRecognizer.ERROR_CLIENT:
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        errorMsg = "Microphone reset. Tap 'Press to Speak' to speak.";
                        initRecognizer();
                        break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        errorMsg = "Microphone permission required.";
                        break;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                        errorMsg = "Network error. Tap 'Press to Speak' to retry.";
                        break;
                    default:
                        errorMsg = "Tap 'Press to Speak' to speak.";
                        break;
                }
                listener.onStatusUpdate(errorMsg);

                // In continuous mode only, loop back and resume listening
                if (continuousEnabled && !destroyed && !isTtsSpeaking) {
                    long delay = (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) ? 1500 : 800;
                    mainHandler.postDelayed(() -> restartListeningLoop(), delay);
                }
            }

            @Override
            public void onResults(Bundle results) {
                mainHandler.removeCallbacks(listeningTimeoutWatchdog);
                isListening = false;
                listener.onListeningStateChanged(false);
                if (results != null) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String spoken = matches.get(0).toLowerCase(Locale.US).trim();
                        Log.d(TAG, "Spoken voice command heard: " + spoken);
                        listener.onStatusUpdate("✓ Heard: \"" + spoken + "\"");
                        handleSpokenCommand(spoken);
                    } else {
                        listener.onStatusUpdate("Didn't catch that. Tap 'Press to Speak' to retry.");
                    }
                } else {
                    listener.onStatusUpdate("Didn't catch that. Tap 'Press to Speak' to retry.");
                }

                // Automatically loop back to continue listening ONLY if continuousEnabled
                if (continuousEnabled && !destroyed && !isTtsSpeaking) {
                    mainHandler.postDelayed(() -> restartListeningLoop(), 650);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (partialResults != null) {
                    ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String partial = matches.get(0);
                        listener.onPartialSpeechHeard(partial);
                    }
                }
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    // ── Anti-Feedback Coordination with TextToSpeech ───────────────────────

    void setTtsSpeaking(boolean speaking) {
        this.isTtsSpeaking = speaking;
        if (speaking) {
            stopListening();
            mainHandler.removeCallbacks(ttsWatchdog);
            mainHandler.postDelayed(ttsWatchdog, 9000);
        } else {
            mainHandler.removeCallbacks(ttsWatchdog);
            if (continuousEnabled && !destroyed) {
                mainHandler.postDelayed(this::restartListeningLoop, 400);
            }
        }
    }

    /**
     * Explicit Push-to-Talk / Press-to-Speak trigger.
     * Guaranteed to stop TTS, cancel any stale state, re-init recognizer, and listen immediately.
     */
    void forceStartListening() {
        if (destroyed) return;
        isTtsSpeaking = false;
        mainHandler.removeCallbacks(ttsWatchdog);
        mainHandler.removeCallbacks(listeningTimeoutWatchdog);

        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {}
        }
        isListening = false;

        // Re-create to ensure a clean Google Speech session
        initRecognizer();

        if (speechRecognizer != null) {
            try {
                speechRecognizer.startListening(recognizerIntent);
                isListening = true;
                listener.onListeningStateChanged(true);
                listener.onStatusUpdate("🎙️ Listening... Speak now!");
                mainHandler.postDelayed(listeningTimeoutWatchdog, 10000);
            } catch (Exception e) {
                Log.e(TAG, "forceStartListening error: " + e.getMessage());
                isListening = false;
                listener.onListeningStateChanged(false);
                listener.onStatusUpdate("Could not access microphone. Tap 'Press to Speak' to retry.");
            }
        } else {
            listener.onStatusUpdate("⚠️ Speech recognizer unavailable. Tap to retry.");
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
                speechRecognizer.startListening(recognizerIntent);
                isListening = true;
                mainHandler.removeCallbacks(listeningTimeoutWatchdog);
                mainHandler.postDelayed(listeningTimeoutWatchdog, 10000);
            } catch (Exception e) {
                Log.e(TAG, "Error starting voice recognition: " + e.getMessage());
                try {
                    speechRecognizer.cancel();
                } catch (Exception ignored) {}
                isListening = false;
                if (continuousEnabled) {
                    mainHandler.postDelayed(() -> restartListeningLoop(), 1200);
                }
            }
        }
    }

    private void restartListeningLoop() {
        if (!continuousEnabled || destroyed || isTtsSpeaking) return;
        startListening();
    }

    void stopListening() {
        mainHandler.removeCallbacks(listeningTimeoutWatchdog);
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

    void handleExternalSpokenCommand(String spoken) {
        if (spoken == null || spoken.trim().isEmpty()) return;
        mainHandler.post(() -> handleSpokenCommand(spoken.toLowerCase(Locale.US).trim()));
    }

    // ── Command Parser ─────────────────────────────────────────────────────

    private void handleSpokenCommand(String spoken) {
        // Strip conversational prefixes ("hey via lux", "please", "can you")
        String clean = spoken
                .replaceFirst("^hey\\s+via\\s+lux\\s*", "")
                .replaceFirst("^via\\s+lux\\s*", "")
                .replaceFirst("^please\\s*", "")
                .replaceFirst("^can\\s+you\\s*", "")
                .trim();

        // 0. Conversational Scene Q&A ("is there any car in front of me", "what is in front of me", "how far is the car")
        if (SceneVoiceAssistant.isAssistantQuery(clean)) {
            listener.onStatusUpdate("💬 Asking: \"" + clean + "\"");
            listener.onSceneAssistantQuery(clean);
            return;
        }

        // 1. Walking Navigation ("take me to...", "navigate to...", "walk to...", "directions to...")
        if (clean.startsWith("navigate to") || clean.startsWith("directions to")
                || clean.startsWith("take me to") || clean.startsWith("walk to")
                || clean.startsWith("go to") || clean.startsWith("route to")
                || clean.startsWith("how to reach") || clean.startsWith("take me")
                || clean.startsWith("navigate")) {
            String dest = clean
                    .replaceFirst("^navigate to\\s*", "")
                    .replaceFirst("^directions to\\s*", "")
                    .replaceFirst("^take me to\\s*", "")
                    .replaceFirst("^take me\\s*", "")
                    .replaceFirst("^route to\\s*", "")
                    .replaceFirst("^walk to\\s*", "")
                    .replaceFirst("^how to reach\\s*", "")
                    .replaceFirst("^go to\\s*", "")
                    .replaceFirst("^navigate\\s*", "")
                    .trim();

            if (!dest.isEmpty()) {
                listener.onStatusUpdate("✓ Navigating to: " + dest);
                listener.onNavigateCommand(dest);
                return;
            }
        }

        // 2. Open in Google Maps
        if (clean.contains("google maps") || clean.contains("open maps") || clean.contains("show map")
                || clean.contains("open map") || clean.contains("launch maps")) {
            listener.onStatusUpdate("✓ Command: Open Google Maps");
            listener.onOpenMapsCommand();
            return;
        }

        // 3. Stop Navigation
        if (clean.contains("stop nav") || clean.contains("cancel nav") || clean.contains("end nav")
                || clean.contains("stop navigation") || clean.contains("cancel navigation")) {
            listener.onStatusUpdate("✓ Command: Stop Navigation");
            listener.onStopNavCommand();
            return;
        }

        // 4. Start Camera / Obstacle Detection
        if (clean.equals("start") || clean.contains("start camera") || clean.contains("start detection")
                || clean.contains("open camera") || clean.contains("begin") || clean.contains("turn on camera")) {
            listener.onStatusUpdate("✓ Command: Start Camera");
            listener.onStartCommand();
            return;
        }

        // 5. Stop Camera / Detection
        if (clean.equals("stop") || clean.contains("stop camera") || clean.contains("pause")
                || clean.contains("freeze") || clean.contains("turn off camera") || clean.contains("quit detection")) {
            listener.onStatusUpdate("✓ Command: Stop Camera");
            listener.onStopCommand();
            return;
        }

        // 6. Confirm Read Text / OCR Reading
        if (clean.equals("read") || clean.equals("read it") || clean.equals("read text")
                || clean.equals("yes") || clean.equals("sure") || clean.equals("okay")
                || clean.contains("what does it say") || clean.contains("read label")
                || clean.contains("read sign")) {
            listener.onStatusUpdate("✓ Command: Read Text");
            listener.onTextConfirmedCommand();
            return;
        }

        // 7. Check NFC / Medicine Scan
        if (clean.contains("nfc") || clean.contains("tag") || clean.contains("medicine")
                || clean.contains("scan tag") || clean.contains("check nfc") || clean.contains("read tag")) {
            listener.onStatusUpdate("✓ Command: Check NFC Tag");
            listener.onCheckNfcCommand();
            return;
        }

        // 8. Repeat Last Speech
        if (clean.contains("repeat") || clean.contains("say again") || clean.contains("what was that")
                || clean.equals("again")) {
            listener.onStatusUpdate("✓ Command: Repeat");
            listener.onRepeatCommand();
            return;
        }

        // 9. Options / Help
        if (clean.contains("option") || clean.contains("options") || clean.contains("help")
                || clean.contains("what can i say") || clean.contains("commands") || clean.contains("menu")) {
            listener.onStatusUpdate("✓ Command: Options & Help");
            listener.onHelpCommand();
            return;
        }

        // 10. Settings
        if (clean.contains("settings") || clean.contains("preferences")) {
            listener.onStatusUpdate("✓ Command: Settings");
            listener.onSettingsCommand();
            return;
        }

        listener.onStatusUpdate("Heard: \"" + spoken + "\" (Say 'Help' for all options)");
    }

    // ── Zero-Physical-Contact Proximity Wave Sensor ─────────────────────────

    private void initProximitySensor() {
        try {
            sensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
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

            // Hand waved near proximity sensor (< maxRange)
            if (distance < maxRange && now - lastWaveAt > 1800) {
                lastWaveAt = now;
                Log.d(TAG, "Zero-touch proximity wave detected: waking listening.");
                forceStartListening();
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ── Lifecycle ──────────────────────────────────────────────────────────

    void shutdown() {
        destroyed = true;
        mainHandler.removeCallbacks(ttsWatchdog);
        mainHandler.removeCallbacks(listeningTimeoutWatchdog);
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
