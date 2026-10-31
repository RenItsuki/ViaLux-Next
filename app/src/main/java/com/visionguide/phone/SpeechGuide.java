package com.visionguide.phone;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

final class SpeechGuide implements TextToSpeech.OnInitListener {
    interface TtsStateListener {
        void onTtsStarted();
        void onTtsFinished();
    }

    interface OnInitListener {
        void onTtsReady();
    }

    private TextToSpeech tts;
    private boolean ready = false;
    private String lastMessage = "";
    private long lastStartedAt = 0;
    private TtsStateListener ttsStateListener;
    private OnInitListener onInitListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final java.util.Map<String, Runnable> pendingCallbacks = new java.util.concurrent.ConcurrentHashMap<>();

    SpeechGuide(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), this);
    }

    void setTtsStateListener(TtsStateListener listener) {
        this.ttsStateListener = listener;
    }

    void setOnInitListener(OnInitListener listener) {
        this.onInitListener = listener;
        if (ready && listener != null) {
            mainHandler.post(listener::onTtsReady);
        }
    }

    boolean isReady() {
        return ready;
    }

    @Override
    public void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS;
        if (ready) {
            tts.setLanguage(Locale.US);
            tts.setSpeechRate(0.98f);
            tts.setPitch(1.0f);
            if (onInitListener != null) {
                mainHandler.post(onInitListener::onTtsReady);
            }

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    if (ttsStateListener != null) {
                        mainHandler.post(() -> ttsStateListener.onTtsStarted());
                    }
                }

                @Override
                public void onDone(String utteranceId) {
                    Runnable cb = utteranceId != null ? pendingCallbacks.remove(utteranceId) : null;
                    if (cb != null) {
                        mainHandler.post(cb);
                    }
                    if (ttsStateListener != null) {
                        mainHandler.post(() -> ttsStateListener.onTtsFinished());
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    Runnable cb = utteranceId != null ? pendingCallbacks.remove(utteranceId) : null;
                    if (cb != null) {
                        mainHandler.post(cb);
                    }
                    if (ttsStateListener != null) {
                        mainHandler.post(() -> ttsStateListener.onTtsFinished());
                    }
                }
            });
        }
    }

    /**
     * Cleans raw text for natural TTS reading: removes asterisks, brackets, parentheticals, and emoji.
     */
    static String cleanForSpeech(String raw) {
        if (raw == null) return "";
        return raw
                .replaceAll("<\\|[^>]*\\|>", "")
                .replaceAll("\\*\\*", "")
                .replaceAll("\\*", "")
                .replaceAll("#{1,6}\\s*", "")
                .replaceAll("`{1,3}[^`]*`{1,3}", "")
                .replaceAll("`", "")
                .replaceAll("(?m)^\\s*[-*•\\d+.]+\\s+", "")
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("\\((?:[0-9.]+\\s*(?:m|meters?|metres?)|moving|stationary)\\)", "")
                .replaceAll("&", " and ")
                .replaceAll("%", " percent ")
                .replaceAll("@", " at ")
                .replaceAll("[\\p{So}\\p{Cn}]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    void speak(String message, boolean emergency, int priority) {
        String clean = cleanForSpeech(message);
        if (!ready || tts == null || clean.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (clean.equals(lastMessage) && now - lastStartedAt < 6000 && !emergency) {
            return;
        }
        if (tts.isSpeaking() && !emergency && priority < Guidance.PRIORITY_URGENT) {
            return;
        }
        lastMessage = clean;
        lastStartedAt = now;
        int mode = emergency || priority >= Guidance.PRIORITY_URGENT
                ? TextToSpeech.QUEUE_FLUSH
                : TextToSpeech.QUEUE_ADD;
        tts.speak(clean, mode, null, "via-lux-" + System.nanoTime());
    }

    /** Speak immediately bypassing cooldown, used for voice commands, OCR reading, and repeating. */
    void forceSpeak(String message, boolean flush) {
        forceSpeak(message, flush, null);
    }

    /** Speak immediately and execute callback when speech completes. */
    void forceSpeak(String message, boolean flush, Runnable onDone) {
        String clean = cleanForSpeech(message);
        if (!ready || tts == null || clean.isEmpty()) {
            if (onDone != null) mainHandler.post(onDone);
            return;
        }
        lastMessage = clean;
        lastStartedAt = System.currentTimeMillis();
        String utteranceId = "via-lux-force-" + System.nanoTime();
        if (onDone != null) {
            pendingCallbacks.put(utteranceId, onDone);
        }
        int mode = flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
        tts.speak(clean, mode, null, utteranceId);
    }

    void stopSpeaking() {
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
            if (ttsStateListener != null) {
                ttsStateListener.onTtsFinished();
            }
        }
    }

    boolean isSpeaking() {
        return tts != null && tts.isSpeaking();
    }

    String getLastMessage() {
        return lastMessage;
    }

    void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }
}
