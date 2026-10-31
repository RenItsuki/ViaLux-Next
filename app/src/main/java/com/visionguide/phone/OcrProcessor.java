package com.visionguide.phone;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * OcrProcessor — on-device OCR using ML Kit Text Recognition v2.
 * Fully offline, no API key required.
 *
 * Safe for asynchronous execution: creates an internal isolated copy of the frame
 * so the camera pipeline's bitmap recycling never causes crashes or missed reads.
 */
final class OcrProcessor {
    private static final String TAG = "OcrProcessor";

    interface OcrCallback {
        void onResult(String text);
    }

    private final TextRecognizer recognizer;
    private volatile boolean armed = false;
    private String lastResult = "";
    private long lastRunAt = 0;
    private int intervalMs = 2500;

    OcrProcessor() {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    void setIntervalMs(int ms) {
        intervalMs = ms;
    }

    /** Arm a single OCR pass on the next eligible camera frame. */
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
     * Process a camera frame if armed and cooldown has passed.
     * Safely makes an independent bitmap copy that is recycled only after ML Kit finishes.
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
                        // Extract lines and format into clear, pronounceable sentences
                        StringBuilder sb = new StringBuilder();
                        for (Text.TextBlock block : result.getTextBlocks()) {
                            String blockText = block.getText().trim();
                            if (!blockText.isEmpty()) {
                                if (sb.length() > 0) sb.append(". ");
                                sb.append(blockText);
                            }
                        }
                        String clean = sb.toString().trim();
                        lastResult = clean;
                        callback.onResult(clean);
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
