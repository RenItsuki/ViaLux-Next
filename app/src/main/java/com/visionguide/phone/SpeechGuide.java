package com.visionguide.phone;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

final class SpeechGuide implements TextToSpeech.OnInitListener {
    private TextToSpeech tts;
    private boolean ready = false;
    private String lastMessage = "";
    private long lastStartedAt = 0;

    SpeechGuide(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), this);
    }

    @Override
    public void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS;
        if (ready) {
            tts.setLanguage(Locale.US);
            tts.setSpeechRate(1.05f);
            tts.setPitch(1.0f);
        }
    }

    void speak(String message, boolean emergency, int priority) {
        if (!ready || tts == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (message.equals(lastMessage) && now - lastStartedAt < 8000) {
            return;
        }
        if (tts.isSpeaking() && !emergency && priority < Guidance.PRIORITY_URGENT) {
            return;
        }
        lastMessage = message;
        lastStartedAt = now;
        int mode = emergency || priority >= Guidance.PRIORITY_URGENT
                ? TextToSpeech.QUEUE_FLUSH
                : TextToSpeech.QUEUE_ADD;
        tts.speak(message, mode, null, "vision-guide-" + System.nanoTime());
    }

    void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }
}
