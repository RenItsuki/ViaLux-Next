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
        void onTextRejectedCommand();
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
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                mainHandler.removeCallbacks(listeningTimeoutWatchdog);
                mainHandler.postDelayed(listeningTimeoutWatchdog, 12000);
                isListening = true;
                playTone(android.media.ToneGenerator.TONE_PROP_BEEP, 100);
                listener.onListeningStateChanged(true);
                listener.onStatusUpdate("🎙️ LISTENING NOW — Speak clearly! (e.g. 'Where are we?', 'Is there any car?')");
            }

            @Override public void onBeginningOfSpeech() {
                mainHandler.removeCallbacks(listeningTimeoutWatchdog);
                mainHandler.postDelayed(listeningTimeoutWatchdog, 14000);
            }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                mainHandler.removeCallbacks(listeningTimeoutWatchdog);
                isListening = false;
                playTone(android.media.ToneGenerator.TONE_PROP_ACK, 80);
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
                        errorMsg = "Didn't hear you clearly. Tap anywhere or press to speak again.";
                        break;
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        errorMsg = "No speech detected. Tap anywhere to speak.";
                        break;
                    case SpeechRecognizer.ERROR_AUDIO:
                        errorMsg = "Audio recording error. Tap to retry.";
                        break;
                    case SpeechRecognizer.ERROR_CLIENT:
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        errorMsg = "Microphone ready. Tap anywhere to speak.";
                        mainHandler.postDelayed(() -> initRecognizer(), 200);
                        break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        errorMsg = "Microphone permission required.";
                        break;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                        errorMsg = "Network timeout. Tap to retry speech.";
                        break;
                    default:
                        errorMsg = "Tap anywhere to speak.";
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
                        listener.onStatusUpdate("Didn't catch that. Tap anywhere to speak.");
                    }
                } else {
                    listener.onStatusUpdate("Didn't catch that. Tap anywhere to speak.");
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

    private void playTone(int toneType, int durationMs) {
        try {
            android.media.ToneGenerator toneGen = new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80);
            toneGen.startTone(toneType, durationMs);
            mainHandler.postDelayed(() -> {
                try { toneGen.release(); } catch (Exception ignored) {}
            }, durationMs + 50);
        } catch (Throwable ignored) {}
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
     * Cancels any stale session and starts listening immediately without destroying binder.
     */
    void forceStartListening() {
        if (destroyed) return;
        isTtsSpeaking = false;
        mainHandler.removeCallbacks(ttsWatchdog);
        mainHandler.removeCallbacks(listeningTimeoutWatchdog);

        if (speechRecognizer == null) {
            initRecognizer();
        } else {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {}
        }
        isListening = false;

        if (speechRecognizer != null) {
            mainHandler.postDelayed(() -> {
                if (destroyed || isTtsSpeaking) return;
                try {
                    speechRecognizer.startListening(recognizerIntent);
                    isListening = true;
                    listener.onListeningStateChanged(true);
                    listener.onStatusUpdate("🎙️ Opening microphone... Please wait for beep.");
                    mainHandler.postDelayed(listeningTimeoutWatchdog, 12000);
                } catch (Exception e) {
                    Log.e(TAG, "forceStartListening error: " + e.getMessage());
                    isListening = false;
                    listener.onListeningStateChanged(false);
                    // Re-init on exception
                    initRecognizer();
                    listener.onStatusUpdate("Microphone reset. Tap anywhere to speak.");
                }
            }, 60);
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
        // Strip conversational prefixes ("hey neurovision", "neurovision", "hey via lux", "via lux", "please", "can you")
        String clean = spoken
                .replaceFirst("^hey\\s+neurovision\\s*", "")
                .replaceFirst("^neurovision\\s*", "")
                .replaceFirst("^hey\\s+via\\s+lux\\s*", "")
                .replaceFirst("^via\\s+lux\\s*", "")
                .replaceFirst("^please\\s*", "")
                .replaceFirst("^can\\s+you\\s*", "")
                .trim();

        // 0. Conversational Scene & Layout Q&A ("where are we", "are we inside", "is there any car", "what should i do first")
        if (SceneVoiceAssistant.isAssistantQuery(clean) || SceneLayoutAnalyzer.isLayoutQuery(clean)) {
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

        // 5. Stop Camera / Silence
        if (clean.equals("stop") || clean.contains("stop camera") || clean.contains("pause")
                || clean.contains("freeze") || clean.contains("turn off camera") || clean.contains("quit detection")
                || clean.equals("quiet") || clean.equals("be quiet") || clean.equals("shut up")
                || clean.equals("hush") || clean.equals("silence")) {
            listener.onStatusUpdate("✓ Command: Stop / Silence");
            listener.onStopCommand();
            return;
        }

        // 6. Text Rejection ("no", "don't read", "skip", "never mind")
        if (clean.equals("no") || clean.equals("nope") || clean.contains("don't read")
                || clean.contains("skip") || clean.contains("never mind") || clean.contains("no thanks")
                || clean.contains("do not read")) {
            listener.onStatusUpdate("✓ Command: Cancel Text Read");
            listener.onTextRejectedCommand();
            return;
        }

        // 7. Confirm Read Text / OCR Reading
        if (clean.equals("read") || clean.equals("read it") || clean.equals("read text")
                || clean.equals("yes") || clean.equals("yeah") || clean.equals("yep")
                || clean.equals("sure") || clean.equals("okay") || clean.equals("ok")
                || clean.equals("please") || clean.equals("go ahead") || clean.contains("what does it say")
                || clean.contains("read label") || clean.contains("read sign") || clean.contains("tell me")) {
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

        // 11. Conversational Voice AI (Qwen / Scene Assistant)
        // Any natural phrase or question is answered intelligently
        listener.onStatusUpdate("💬 " + clean);
        listener.onSceneAssistantQuery(clean);
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
