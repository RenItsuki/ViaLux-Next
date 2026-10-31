package com.visionguide.phone;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OcrProcessor — On-device OCR & Text Stability Analyzer.
 *
 * Capabilities:
 *   1. Stationary Text Stability Detection (2-3 seconds):
 *      When a blind user points their phone at a sign, document, or medicine box,
 *      the camera frame and text centroid become stationary. After remaining stable
 *      for 2.2 seconds, it triggers onStationaryTextDetected, prompting the user:
 *      "I see text ahead. Would you like me to read it?" and listening for "Yes".
 *   2. Active Reading & Offline Fallback:
 *      Sorts text blocks logically from top-to-bottom, eliminating choppy fragments,
 *      and passes high-res frames to Gemini Vision for complete reading.
 */
final class OcrProcessor {
    private static final String TAG = "OcrProcessor";

    interface OcrCallback {
        void onResult(String text);
    }

    interface StationaryTextListener {
        void onStationaryTextDetected(String sampleWords, String fullText, Bitmap highResFrame);
    }

    private final TextRecognizer recognizer;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private volatile boolean armed = false;
    private String lastResult = "";

    // Text stability tracking
    private PointF lastCentroid = null;
    private long stationaryStartTime = 0;
    private long lastPromptTime = 0;
    private String lastPromptSignature = "";
    private long lastRunAt = 0;
    private long lastCheckAt = 0;
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
        stationaryStartTime = 0;
    }

    boolean isArmed() {
        return armed;
    }

    /**
     * Periodic passive text presence and stability check.
     * When text in front of the camera remains stationary for 2.2+ seconds,
     * triggers the listener to ask the user if they'd like it read.
     */
    void checkStationaryText(Bitmap sourceBitmap, long now, StationaryTextListener listener) {
        if (armed || now - lastCheckAt < 450 || !isProcessing.compareAndSet(false, true)) {
            return;
        }
        lastCheckAt = now;

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
                    List<Text.TextBlock> blocks = result.getTextBlocks();
                    if (blocks.isEmpty()) {
                        stationaryStartTime = 0;
                        lastCentroid = null;
                        return;
                    }

                    // Calculate normalized centroid of all text blocks
                    float sumX = 0, sumY = 0;
                    int count = 0;
                    for (Text.TextBlock block : blocks) {
                        Rect box = block.getBoundingBox();
                        if (box != null) {
                            sumX += box.centerX();
                            sumY += box.centerY();
                            count++;
                        }
                    }

                    if (count == 0) {
                        stationaryStartTime = 0;
                        lastCentroid = null;
                        return;
                    }

                    float normX = sumX / (count * (float) frameCopy.getWidth());
                    float normY = sumY / (count * (float) frameCopy.getHeight());

                    if (lastCentroid != null) {
                        float distMoved = (float) Math.hypot(normX - lastCentroid.x, normY - lastCentroid.y);
                        // Within 8% screen delta indicates steady hand pointing at text
                        if (distMoved < 0.08f) {
                            if (stationaryStartTime == 0) {
                                stationaryStartTime = now;
                            } else if (now - stationaryStartTime >= 2200) { // Stationary for 2.2 seconds!
                                String fullText = extractFormattedText(result);
                                if (fullText.length() >= 5) {
                                    String signature = fullText.substring(0, Math.min(24, fullText.length()));
                                    if (!signature.equalsIgnoreCase(lastPromptSignature) && now - lastPromptTime > 20000) {
                                        lastPromptSignature = signature;
                                        lastPromptTime = now;
                                        stationaryStartTime = 0;
                                        String sample = fullText.length() > 30 ? fullText.substring(0, 30) + "..." : fullText;
                                        // Pass high-res frameCopy clone to listener for Gemini Vision
                                        Bitmap sampleFrame = frameCopy.copy(Bitmap.Config.ARGB_8888, false);
                                        listener.onStationaryTextDetected(sample, fullText, sampleFrame);
                                    }
                                }
                            }
                        } else {
                            // User moved camera: reset stationary timer
                            stationaryStartTime = 0;
                            lastCentroid = new PointF(normX, normY);
                        }
                    } else {
                        lastCentroid = new PointF(normX, normY);
                        stationaryStartTime = now;
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
     * Active On-Device OCR Read (instant offline fallback).
     * Sorts lines top-to-bottom so text reads in logical reading order.
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
                    String fullText = extractFormattedText(result);
                    if (fullText.isEmpty()) {
                        callback.onResult("No text found. Try holding phone closer to text.");
                    } else {
                        String spokenResult = fullText + ". Finished reading text.";
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

    /**
     * Sorts text blocks vertically from top to bottom so they read naturally like a document.
     */
    private static String extractFormattedText(Text result) {
        if (result == null || result.getTextBlocks().isEmpty()) return "";

        List<Text.TextBlock> sortedBlocks = new ArrayList<>(result.getTextBlocks());
        Collections.sort(sortedBlocks, (a, b) -> {
            Rect boxA = a.getBoundingBox();
            Rect boxB = b.getBoundingBox();
            int topA = boxA != null ? boxA.top : 0;
            int topB = boxB != null ? boxB.top : 0;
            return Integer.compare(topA, topB);
        });

        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : sortedBlocks) {
            String text = block.getText().trim();
            if (!text.isEmpty()) {
                if (sb.length() > 0) sb.append(". ");
                sb.append(text);
            }
        }
        return sb.toString().trim();
    }

    void shutdown() {
        recognizer.close();
    }
}
