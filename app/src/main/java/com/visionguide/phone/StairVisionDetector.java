package com.visionguide.phone;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * StairVisionDetector — On-device stair detection using the trained YOLOv8n
 * StairVision TFLite model (CS663 Mobile Vision Project / Yeshwanth Nani).
 *
 * Model specifications:
 *   - File: stair_yolo_best_float32.tflite
 *   - Input: [1, 640, 640, 3] Float32 RGB normalized to [0, 1]
 *   - Output: [1, 5, 8400] Float32 (cx, cy, w, h, confidence)
 *   - Target: Ascending, descending, and side-view stairs
 */
final class StairVisionDetector implements AutoCloseable {
    private static final String TAG = "StairVisionDetector";
    private static final String MODEL_FILE = "stair_yolo_best_float32.tflite";
    private static final int INPUT_SIZE = 640;
    private static final float CONFIDENCE_THRESHOLD = 0.50f;
    private static final float NMS_IOU_THRESHOLD = 0.45f;

    private final Interpreter interpreter;
    private final ByteBuffer inputBuffer;
    private final int[] pixelArray = new int[INPUT_SIZE * INPUT_SIZE];

    StairVisionDetector(Context context) throws IOException {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(2);
        ByteBuffer modelBuffer = loadModelFile(context, MODEL_FILE);
        interpreter = new Interpreter(modelBuffer, options);

        // Pre-allocate direct byte buffer for input: 1 * 640 * 640 * 3 * 4 bytes
        inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());

        Log.d(TAG, "StairVision YOLOv8n TFLite model loaded successfully.");
    }

    private static ByteBuffer loadModelFile(Context context, String filename) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(filename);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        ByteBuffer buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        inputStream.close();
        return buffer;
    }

    /**
     * Run stair detection on a camera frame.
     *
     * @param source Source camera bitmap.
     * @return List of detected stair objects with classified types (ascending, descending, etc.).
     */
    synchronized List<Detection> detect(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return Collections.emptyList();
        }

        Bitmap resized = Bitmap.createScaledBitmap(source, INPUT_SIZE, INPUT_SIZE, true);
        resized.getPixels(pixelArray, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        if (resized != source) {
            resized.recycle();
        }

        // Fill input buffer: NHWC [1, 640, 640, 3] Float32 RGB [0, 1]
        inputBuffer.rewind();
        for (int pixel : pixelArray) {
            float r = ((pixel >> 16) & 0xFF) / 255.0f;
            float g = ((pixel >> 8) & 0xFF) / 255.0f;
            float b = (pixel & 0xFF) / 255.0f;
            inputBuffer.putFloat(r);
            inputBuffer.putFloat(g);
            inputBuffer.putFloat(b);
        }

        // Output tensor shape: [1][5][8400]
        float[][][] output = new float[1][5][8400];
        try {
            interpreter.run(inputBuffer, output);
        } catch (Exception e) {
            Log.e(TAG, "Inference error: " + e.getMessage());
            return Collections.emptyList();
        }

        return parseOutput(output[0], source.getWidth(), source.getHeight());
    }

    private List<Detection> parseOutput(float[][] out, int imgWidth, int imgHeight) {
        List<Detection> rawCandidates = new ArrayList<>();

        for (int i = 0; i < 8400; i++) {
            float score = out[4][i];
            if (score < CONFIDENCE_THRESHOLD) {
                continue;
            }

            float cx = out[0][i];
            float cy = out[1][i];
            float w = out[2][i];
            float h = out[3][i];

            // Normalize coordinates if model outputs pixel dimensions in 640 space
            boolean normalized = Math.max(Math.max(cx, cy), Math.max(w, h)) <= 1.5f;
            float normCx = normalized ? cx : cx / INPUT_SIZE;
            float normCy = normalized ? cy : cy / INPUT_SIZE;
            float normW = normalized ? w : w / INPUT_SIZE;
            float normH = normalized ? h : h / INPUT_SIZE;

            if (normW <= 0.01f || normH <= 0.01f) {
                continue;
            }

            float x1 = (normCx - normW / 2.0f) * imgWidth;
            float y1 = (normCy - normH / 2.0f) * imgHeight;
            float x2 = (normCx + normW / 2.0f) * imgWidth;
            float y2 = (normCy + normH / 2.0f) * imgHeight;

            RectF box = new RectF(
                    clamp(Math.min(x1, x2), 0, imgWidth),
                    clamp(Math.min(y1, y2), 0, imgHeight),
                    clamp(Math.max(x1, x2), 0, imgWidth),
                    clamp(Math.max(y1, y2), 0, imgHeight)
            );

            if (box.width() > 5 && box.height() > 5) {
                String stairLabel = analyzeStairType(normCx, normCy, normW, normH);
                rawCandidates.add(new Detection(box, stairLabel, score, -100));
            }
        }

        return applyNms(rawCandidates);
    }

    /**
     * Classifies stair type based on position and shape heuristics from the StairVision project.
     */
    private static String analyzeStairType(float cx, float cy, float w, float h) {
        float aspectRatio = w / Math.max(0.001f, h);

        // Ascending: stairs appear in upper portion with vertical perspective
        if (cy < 0.45f && h > 0.25f && aspectRatio < 1.3f) {
            return "ascending stairs";
        }
        // Descending: stairs appear in lower portion, wider horizontal perspective
        if (cy > 0.55f && w > 0.35f && aspectRatio > 0.9f) {
            return "descending stairs";
        }
        // Side view: tall and narrow
        if (aspectRatio < 0.6f && h > 0.35f) {
            return "stairs from side";
        }
        // Default general stairs
        return "stairs";
    }

    /**
     * Non-Maximum Suppression to remove redundant overlapping bounding boxes.
     */
    private static List<Detection> applyNms(List<Detection> boxes) {
        if (boxes.isEmpty()) {
            return boxes;
        }

        // Sort descending by confidence
        Collections.sort(boxes, (a, b) -> Float.compare(b.confidence, a.confidence));

        List<Detection> selected = new ArrayList<>();
        boolean[] suppressed = new boolean[boxes.size()];

        for (int i = 0; i < boxes.size(); i++) {
            if (suppressed[i]) continue;
            Detection current = boxes.get(i);
            selected.add(current);

            for (int j = i + 1; j < boxes.size(); j++) {
                if (suppressed[j]) continue;
                if (calculateIoU(current.box, boxes.get(j).box) >= NMS_IOU_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }
        return selected;
    }

    private static float calculateIoU(RectF a, RectF b) {
        float interLeft = Math.max(a.left, b.left);
        float interTop = Math.max(a.top, b.top);
        float interRight = Math.min(a.right, b.right);
        float interBottom = Math.min(a.bottom, b.bottom);

        if (interRight <= interLeft || interBottom <= interTop) {
            return 0f;
        }

        float interArea = (interRight - interLeft) * (interBottom - interTop);
        float aArea = a.width() * a.height();
        float bArea = b.width() * b.height();
        float unionArea = aArea + bArea - interArea;
        return unionArea <= 0f ? 0f : interArea / unionArea;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void close() {
        interpreter.close();
        Log.d(TAG, "StairVisionDetector closed.");
    }
}
