package com.visionguide.phone;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * OcrProcessor — on-device OCR using ML Kit Text Recognition v2.
 * No API key or internet connection required.
 *
 * Usage:
 *   Call requestRead() to arm a one-shot read.
 *   Call process(bitmap, callback) every camera frame — it fires the callback
 *   only when armed and the inter-frame interval has elapsed.
 *
 * Controls:
 *   requestRead()  — arm a single OCR pass (like pressing "Read Text")
 *   repeat()       — re-speak the last recognized text without a new capture
 *   cancel()       — disarm without firing
 */
final class OcrProcessor {
    private static final String TAG = "OcrProcessor";
    private static final float MIN_CONFIDENCE = 0.65f;

    interface OcrCallback {
        void onResult(String text);
    }

    private final TextRecognizer recognizer;
    private volatile boolean armed = false;
    private String lastResult = "";
    private long lastRunAt = 0;
    private int intervalMs = 3000;

    OcrProcessor() {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    void setIntervalMs(int ms) {
        intervalMs = ms;
    }

    /** Arm a single OCR pass on the next eligible frame. */
    void requestRead() {
        armed = true;
    }

    /** Re-speak the last result without a new capture. */
    String repeat() {
        return lastResult;
    }

    void cancel() {
        armed = false;
    }

    boolean isArmed() {
        return armed;
    }

    /**
     * Call this for each camera frame. Only runs OCR when armed and the interval
     * has elapsed. Fires callback on the calling thread if ML Kit returns inline,
     * otherwise on the ML Kit executor thread — always marshal to UI thread for TTS.
     */
    void process(Bitmap bitmap, long now, OcrCallback callback) {
        if (!armed) return;
        if (now - lastRunAt < intervalMs) return;
        armed = false;
        lastRunAt = now;

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(image)
                .addOnSuccessListener(result -> {
                    StringBuilder sb = new StringBuilder();
                    for (com.google.mlkit.vision.text.Text.TextBlock block : result.getTextBlocks()) {
                        for (com.google.mlkit.vision.text.Text.Line line : block.getLines()) {
                            // Filter low-confidence elements
                            boolean allOk = true;
                            for (com.google.mlkit.vision.text.Text.Element el : line.getElements()) {
                                Float conf = el.getConfidence();
                                if (conf != null && conf < MIN_CONFIDENCE) { allOk = false; break; }
                            }
                            if (allOk && !line.getText().trim().isEmpty()) {
                                if (sb.length() > 0) sb.append(". ");
                                sb.append(line.getText().trim());
                            }
                        }
                    }
                    String text = sb.toString().trim();
                    if (text.isEmpty()) {
                        callback.onResult("No text recognized.");
                    } else {
                        lastResult = text;
                        callback.onResult(text);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "OCR failed: " + e.getMessage());
                    callback.onResult("Text reading failed.");
                });
    }

    void shutdown() {
        recognizer.close();
    }
}
