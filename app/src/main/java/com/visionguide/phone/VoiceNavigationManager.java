package com.visionguide.phone;

import android.content.Context;
import android.content.Intent;
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
 * VoiceNavigationManager — Hands-free voice recognition and voice-command controller.
 *
 * Allows users to control Via Lux entirely by voice:
 *   - "Start" / "Open camera"
 *   - "Stop" / "Pause"
 *   - "Read" / "Read text" / "Read label"
 *   - "Repeat" / "Say again"
 *   - "Navigate to [place]" (e.g. "Navigate to pharmacy", "Directions to Central Park")
 *   - "Stop navigation" / "Cancel route"
 *   - "Status" / "Where am I"
 *   - "Help" / "Commands"
 */
final class VoiceNavigationManager {
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
    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private boolean isListening = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    VoiceNavigationManager(Context context, CommandListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        initRecognizer();
    }

    private void initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition is not available on this device.");
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                isListening = true;
                listener.onListeningStateChanged(true);
                listener.onStatusUpdate("Listening... Say a command (e.g. 'Read text', 'Navigate to...')");
            }

            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                isListening = false;
                listener.onListeningStateChanged(false);
            }

            @Override public void onError(int error) {
                isListening = false;
                listener.onListeningStateChanged(false);
                String msg;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                        msg = "Didn't catch that. Tap the mic to try again.";
                        break;
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        msg = "No speech detected.";
                        break;
                    case SpeechRecognizer.ERROR_AUDIO:
                    case SpeechRecognizer.ERROR_CLIENT:
                        msg = "Microphone error. Tap to retry.";
                        break;
                    default:
                        msg = "Voice listener ready.";
                        break;
                }
                listener.onStatusUpdate(msg);
            }

            @Override public void onResults(Bundle results) {
                isListening = false;
                listener.onListeningStateChanged(false);
                if (results == null) return;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String spoken = matches.get(0).toLowerCase(Locale.US).trim();
                    Log.d(TAG, "Voice command received: " + spoken);
                    handleSpokenCommand(spoken);
                }
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    /** Trigger listening for a voice command. */
    void startListening() {
        if (speechRecognizer == null) {
            initRecognizer();
        }
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
                speechRecognizer.startListening(recognizerIntent);
            } catch (Exception e) {
                Log.e(TAG, "Error starting voice recognition: " + e.getMessage());
                listener.onStatusUpdate("Speech error: " + e.getMessage());
            }
        } else {
            listener.onStatusUpdate("Speech recognition not available on device.");
        }
    }

    void stopListening() {
        if (speechRecognizer != null && isListening) {
            try {
                speechRecognizer.stopListening();
            } catch (Exception ignored) {}
        }
        isListening = false;
        listener.onListeningStateChanged(false);
    }

    boolean isListening() {
        return isListening;
    }

    private void handleSpokenCommand(String spoken) {
        // ── 1. Start / Stop Camera ──────────────────────────────────────
        if (spoken.equals("start") || spoken.contains("start camera") || spoken.contains("start detection")
                || spoken.contains("open camera") || spoken.contains("begin")) {
            listener.onStatusUpdate("Command: Start Camera");
            listener.onStartCommand();
            return;
        }

        if (spoken.equals("stop") || spoken.contains("stop camera") || spoken.contains("pause")
                || spoken.contains("freeze") || spoken.contains("quit detection")) {
            listener.onStatusUpdate("Command: Stop Camera");
            listener.onStopCommand();
            return;
        }

        // ── 2. Read Text / OCR ──────────────────────────────────────────
        if (spoken.contains("read") || spoken.contains("text") || spoken.contains("ocr")
                || spoken.contains("label") || spoken.contains("sign") || spoken.contains("what does it say")) {
            listener.onStatusUpdate("Command: Read Text");
            listener.onReadTextCommand();
            return;
        }

        // ── 3. Repeat Guidance ──────────────────────────────────────────
        if (spoken.contains("repeat") || spoken.contains("say again") || spoken.contains("what was that")
                || spoken.equals("again")) {
            listener.onStatusUpdate("Command: Repeat");
            listener.onRepeatCommand();
            return;
        }

        // ── 4. Stop Navigation ──────────────────────────────────────────
        if (spoken.contains("stop nav") || spoken.contains("cancel nav") || spoken.contains("end nav")
                || spoken.contains("stop navigation") || spoken.contains("cancel navigation")) {
            listener.onStatusUpdate("Command: Stop Navigation");
            listener.onStopNavCommand();
            return;
        }

        // ── 5. Walking Navigation to Destination ────────────────────────
        if (spoken.startsWith("navigate to") || spoken.startsWith("directions to")
                || spoken.startsWith("take me to") || spoken.startsWith("walk to")
                || spoken.startsWith("go to") || spoken.startsWith("navigate")) {
            String dest = spoken
                    .replaceFirst("^navigate to\\s*", "")
                    .replaceFirst("^directions to\\s*", "")
                    .replaceFirst("^take me to\\s*", "")
                    .replaceFirst("^walk to\\s*", "")
                    .replaceFirst("^go to\\s*", "")
                    .replaceFirst("^navigate\\s*", "")
                    .trim();

            if (!dest.isEmpty()) {
                listener.onStatusUpdate("Navigating to: " + dest);
                listener.onNavigateCommand(dest);
                return;
            }
        }

        // ── 6. Settings ─────────────────────────────────────────────────
        if (spoken.contains("settings") || spoken.contains("preferences") || spoken.contains("options")) {
            listener.onStatusUpdate("Command: Settings");
            listener.onSettingsCommand();
            return;
        }

        // ── 7. Help & Commands ──────────────────────────────────────────
        if (spoken.contains("help") || spoken.contains("command") || spoken.contains("what can i say")) {
            listener.onHelpCommand();
            return;
        }

        // Fallback: If it's a location query or destination name directly
        listener.onStatusUpdate("Heard: \"" + spoken + "\". Say 'Help' for voice commands.");
    }

    void shutdown() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
            speechRecognizer = null;
        }
    }
}
