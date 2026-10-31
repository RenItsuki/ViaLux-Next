package com.visionguide.phone;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OcrProcessor — on-device OCR using ML Kit Text Recognition v2.
 * Fully offline, no API key required.
 *
 * Provides:
 *   1. Automatic Scene Text Presence Detection:
 *      Periodically detects when signs, labels, or notices are in view,
 *      allowing the app to proactively ask the user:
 *      "Text detected. Would you like me to read it? Say 'read' or 'yes'."
 *   2. Spoken Text Reading:
 *      Reads the detected text aloud, concluding with:
 *      "Finished reading text."
 */
final class OcrProcessor {
    private static final String TAG = "OcrProcessor";

    interface OcrCallback {
        void onResult(String text);
    }

    interface TextDetectionListener {
        void onTextDetected(String sampleWords, String fullText);
    }

    private final TextRecognizer recognizer;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private volatile boolean armed = false;
    private String lastResult = "";
    private String lastDetectedSignature = "";
    private long lastRunAt = 0;
    private long lastPresenceCheckAt = 0;
    private int intervalMs = 2500;

    OcrProcessor() {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    void setIntervalMs(int ms) {
        intervalMs = ms;
    }

    /** Arm a full OCR read on the next eligible camera frame. */
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
     * Periodic passive text presence check.
     * Detects if words/signs are visible in the camera scene without blocking inference.
     */
    void checkTextPresence(Bitmap sourceBitmap, long now, TextDetectionListener listener) {
        if (armed || now - lastPresenceCheckAt < 2200 || !isProcessing.compareAndSet(false, true)) {
            return;
        }
        lastPresenceCheckAt = now;

        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            isProcessing.set(false);
            return;
        }

        final Bitmap frameCopy;
        try {
            frameCopy = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Exception e) {
            isProcessing.set(false);
            return;
        }

        if (frameCopy == null) {
            isProcessing.set(false);
            return;
        }

        InputImage image = InputImage.fromBitmap(frameCopy, 0);
        recognizer.process(image)
                .addOnSuccessListener(result -> {
                    if (result.getTextBlocks().isEmpty()) return;
                    StringBuilder sb = new StringBuilder();
                    for (Text.TextBlock block : result.getTextBlocks()) {
                        String bt = block.getText().trim();
                        if (!bt.isEmpty()) {
                            if (sb.length() > 0) sb.append(". ");
                            sb.append(bt);
                        }
                    }
                    String fullText = sb.toString().trim();
                    // Filter out short noise (< 4 chars)
                    if (fullText.length() >= 4) {
                        String signature = fullText.substring(0, Math.min(24, fullText.length()));
                        if (!signature.equalsIgnoreCase(lastDetectedSignature)) {
                            lastDetectedSignature = signature;
                            String sample = fullText.length() > 30 ? fullText.substring(0, 30) + "..." : fullText;
                            listener.onTextDetected(sample, fullText);
                        }
                    }
                })
                .addOnCompleteListener(task -> {
                    if (!frameCopy.isRecycled()) {
                        frameCopy.recycle();
                    }
                    isProcessing.set(false);
                });
    }

    /**
     * Active OCR Read: captures and reads visible text in the scene,
     * concluding with "Finished reading text."
     */
    void process(Bitmap sourceBitmap, long now, OcrCallback callback) {
        if (!armed) return;
        if (now - lastRunAt < intervalMs) return;
        armed = false;
        lastRunAt = now;

        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            callback.onResult("Camera frame unavailable.");
            return;
        }

        final Bitmap frameCopy;
        try {
            frameCopy = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Exception e) {
            callback.onResult("Could not process frame.");
            return;
        }

        if (frameCopy == null) {
            callback.onResult("Could not process frame.");
            return;
        }

        InputImage image = InputImage.fromBitmap(frameCopy, 0);
        recognizer.process(image)
                .addOnSuccessListener(result -> {
                    String fullText = result.getText();
                    if (fullText == null || fullText.trim().isEmpty()) {
                        callback.onResult("No text found. Try holding phone closer to text.");
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (Text.TextBlock block : result.getTextBlocks()) {
                            String blockText = block.getText().trim();
                            if (!blockText.isEmpty()) {
                                if (sb.length() > 0) sb.append(". ");
                                sb.append(blockText);
                            }
                        }
                        String clean = sb.toString().trim();
                        String spokenResult = clean + ". Finished reading text.";
                        lastResult = spokenResult;
                        callback.onResult(spokenResult);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "OCR recognition error: " + e.getMessage());
                    callback.onResult("Text reading error. Please try again.");
                })
                .addOnCompleteListener(task -> {
                    if (frameCopy != null && !frameCopy.isRecycled()) {
                        frameCopy.recycle();
                    }
                });
    }

    void shutdown() {
        recognizer.close();
    }
}
