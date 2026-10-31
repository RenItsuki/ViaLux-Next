package com.visionguide.phone;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.RectF;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

final class YoloOnnxDetector implements AutoCloseable {
    private static final int INPUT_SIZE = 640;
    private static final float CONFIDENCE = 0.40f;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final List<String> labels;

    YoloOnnxDetector(Context context) throws Exception {
        AssetManager assets = context.getAssets();
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(2);
        session = env.createSession(readAllBytes(assets.open("yolo26n.onnx")), options);
        inputName = session.getInputNames().iterator().next();
        labels = readLabels(assets);
    }

    List<Detection> detect(Bitmap source) throws Exception {
        Bitmap resized = Bitmap.createScaledBitmap(source, INPUT_SIZE, INPUT_SIZE, true);
        float[] input = new float[1 * 3 * INPUT_SIZE * INPUT_SIZE];
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        if (resized != source) {
            resized.recycle();
        }

        int planeSize = INPUT_SIZE * INPUT_SIZE;
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            input[i] = ((pixel >> 16) & 0xFF) / 255f;
            input[planeSize + i] = ((pixel >> 8) & 0xFF) / 255f;
            input[planeSize * 2 + i] = (pixel & 0xFF) / 255f;
        }

        long[] shape = {1, 3, INPUT_SIZE, INPUT_SIZE};
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            OnnxValue value = result.get(0);
            Object raw = value.getValue();
            if (raw instanceof float[][][]) {
                return parseOutput((float[][][]) raw, source.getWidth(), source.getHeight());
            }
            return Collections.emptyList();
        }
    }

    private List<Detection> parseOutput(float[][][] output, int imageWidth, int imageHeight) {
        List<Detection> detections = new ArrayList<>();
        if (output.length == 0) {
            return detections;
        }
        for (float[] row : output[0]) {
            if (row.length < 6) {
                continue;
            }
            float score = row[4];
            if (score < CONFIDENCE) {
                continue;
            }
            int classId = Math.round(row[5]);
            String label = classId >= 0 && classId < labels.size() ? labels.get(classId) : "object";

            float x1 = row[0];
            float y1 = row[1];
            float x2 = row[2];
            float y2 = row[3];
            boolean normalized = Math.max(Math.max(x1, y1), Math.max(x2, y2)) <= 1.5f;
            if (normalized) {
                x1 *= imageWidth;
                x2 *= imageWidth;
                y1 *= imageHeight;
                y2 *= imageHeight;
            } else {
                x1 = x1 / INPUT_SIZE * imageWidth;
                x2 = x2 / INPUT_SIZE * imageWidth;
                y1 = y1 / INPUT_SIZE * imageHeight;
                y2 = y2 / INPUT_SIZE * imageHeight;
            }

            RectF box = new RectF(
                    clamp(Math.min(x1, x2), 0, imageWidth),
                    clamp(Math.min(y1, y2), 0, imageHeight),
                    clamp(Math.max(x1, x2), 0, imageWidth),
                    clamp(Math.max(y1, y2), 0, imageHeight)
            );
            if (box.width() > 3 && box.height() > 3) {
                detections.add(new Detection(box, label, score, classId));
            }
        }
        return detections;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static byte[] readAllBytes(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static List<String> readLabels(AssetManager assets) throws Exception {
        List<String> values = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(assets.open("classes.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    values.add(line.trim());
                }
            }
        }
        return values;
    }

    @Override
    public void close() throws Exception {
        session.close();
    }
}
