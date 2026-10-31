package com.visionguide.phone;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.ffmpegkit.llama.LlamaJNI;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * QwenVoiceAssistant — On-Device LLM Assistant powered by Qwen2.5-0.5B-Instruct (GGUF).
 *
 * Runs locally on Android via native llama.cpp (dev.ffmpegkit.llama.LlamaJNI).
 *
 * Capabilities:
 *   1. Spatial Array Understanding: Receives the full visual array of detected obstacles,
 *      classes, bounding coordinates, metric distance (via dual camera optics), and motion.
 *      Tells the user WHAT THEY SHOULD DO FIRST (stop, move left, move right, or proceed).
 *   2. Better Voice Interpretation: Handles conversational, natural, and complex phrasing.
 *   3. Intelligent Voice Navigation: Extracts destination names and route intents cleanly.
 *   4. Zero-Latency Fallback: Seamlessly falls back to SceneVoiceAssistant if the model is loading
 *      or downloading, guaranteeing 100% availability.
 */
final class QwenVoiceAssistant {
    private static final String TAG = "QwenVoiceAssistant";
    private static final String MODEL_FILENAME = "qwen2.5-0.5b-instruct-q5_k_m.gguf";
    private static final String HF_DOWNLOAD_URL =
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q5_k_m.gguf";

    interface AssistantCallback {
        void onResponse(String speechAnswer, String actionIntent, String destinationParam);
    }

    interface ModelStatusCallback {
        void onStatusChanged(boolean isReady, String message);
        void onDownloadProgress(int percent, String progressText);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile long modelHandle = 0;
    private volatile boolean isLoaded = false;
    private volatile boolean isDownloading = false;
    private final AtomicBoolean isInferring = new AtomicBoolean(false);

    QwenVoiceAssistant(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Initializes and loads the Qwen model asynchronously on a background thread.
     */
    void loadModelAsync(ModelStatusCallback callback) {
        executor.execute(() -> {
            File modelFile = findModelFile();
            if (modelFile == null || !modelFile.exists() || modelFile.length() < 100_000_000L) {
                // Check if bundled inside assets
                modelFile = extractModelFromAssetsIfAvailable();
            }

            if (modelFile != null && modelFile.exists() && modelFile.length() > 100_000_000L) {
                notifyStatus(callback, false, "Loading Qwen 2.5 Voice AI into memory...");
                try {
                    // Initialize llama.cpp native session: contextSize=2048, threads=4, gpuLayers=0 (CPU NEON)
                    long handle = LlamaJNI.INSTANCE.nativeLoadModel(modelFile.getAbsolutePath(), 2048, 4, 0);
                    if (handle != 0) {
                        modelHandle = handle;
                        isLoaded = true;
                        Log.i(TAG, "Qwen2.5-0.5B-Instruct successfully loaded! Handle=" + handle);
                        notifyStatus(callback, true, "🧠 Qwen 2.5 Voice AI Active (On-Device)");
                        return;
                    } else {
                        Log.w(TAG, "LlamaJNI nativeLoadModel returned 0");
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Error initializing Qwen model: " + t.getMessage(), t);
                }
            }

            notifyStatus(callback, false, "Qwen AI model not downloaded. Fast assistant active.");
        });
    }

    boolean isModelLoaded() {
        return isLoaded && modelHandle != 0;
    }

    boolean isModelDownloading() {
        return isDownloading;
    }

    /**
     * Finds the GGUF model file across internal storage, external files, and public Download folder.
     */
    File findModelFile() {
        File[] candidates = new File[]{
                new File(context.getFilesDir(), "models/" + MODEL_FILENAME),
                new File(context.getFilesDir(), MODEL_FILENAME),
                new File(context.getExternalFilesDir("models"), MODEL_FILENAME),
                new File(context.getExternalFilesDir(null), MODEL_FILENAME),
                new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), MODEL_FILENAME),
                new File("/sdcard/Download/" + MODEL_FILENAME)
        };

        for (File candidate : candidates) {
            if (candidate != null && candidate.exists() && candidate.length() > 400_000_000L) {
                return candidate;
            }
        }
        return null;
    }

    private File extractModelFromAssetsIfAvailable() {
        try {
            String[] list = context.getAssets().list("models");
            boolean foundInAssets = false;
            if (list != null) {
                for (String name : list) {
                    if (name.equals(MODEL_FILENAME)) {
                        foundInAssets = true;
                        break;
                    }
                }
            }
            if (!foundInAssets) {
                String[] rootList = context.getAssets().list("");
                if (rootList != null) {
                    for (String name : rootList) {
                        if (name.equals(MODEL_FILENAME)) {
                            foundInAssets = true;
                            break;
                        }
                    }
                }
            }

            if (foundInAssets) {
                File targetDir = new File(context.getFilesDir(), "models");
                if (!targetDir.exists()) targetDir.mkdirs();
                File targetFile = new File(targetDir, MODEL_FILENAME);

                if (targetFile.exists() && targetFile.length() > 400_000_000L) {
                    return targetFile;
                }

                String assetPath = "models/" + MODEL_FILENAME;
                try (InputStream in = context.getAssets().open(assetPath);
                     OutputStream out = new FileOutputStream(targetFile)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }
                return targetFile;
            }
        } catch (Exception e) {
            Log.d(TAG, "Model not found in assets: " + e.getMessage());
        }
        return null;
    }

    /**
     * Answers queries and analyzes the spatial image array to advise the user what they should do first.
     */
    void answerQuery(
            String userQuery,
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            GuidanceEngine guidanceEngine,
            int imgWidth,
            int imgHeight,
            String lastOcrText,
            String navInstruction,
            AssistantCallback callback) {

        if (!isLoaded || modelHandle == 0 || !isInferring.compareAndSet(false, true)) {
            // Instant rule-based fallback
            String fallback = SceneVoiceAssistant.answerQuery(
                    userQuery, detections, depthEstimator, guidanceEngine, imgWidth, imgHeight, lastOcrText);
            callback.onResponse(fallback, extractActionIntent(userQuery), extractDestination(userQuery));
            return;
        }

        executor.execute(() -> {
            try {
                String sceneDescription = buildSceneArrayContext(detections, depthEstimator, guidanceEngine, imgWidth, imgHeight);

                String systemPrompt = "You are Via Lux, an intelligent assistive vision AI for blind users. " +
                        "You interpret the user's voice and analyze the spatial scene array of obstacles with their real-world distances in meters. " +
                        "Always prioritize user safety: immediate obstacles (< 3m) directly ahead, moving vehicles, and path obstacles. " +
                        "Tell the user what they should do FIRST (e.g., 'Stop! Car directly ahead 2 meters', 'Path is clear ahead', 'Step right to avoid the chair'). " +
                        "Keep your response strictly under 2 sentences, clear and direct for voice.";

                String userPrompt = "<|im_start|>system\n" + systemPrompt + "<|im_end|>\n" +
                        "<|im_start|>user\n" +
                        "User query: \"" + userQuery + "\"\n" +
                        "Detected visual scene:\n" + sceneDescription + "\n" +
                        (lastOcrText != null && !lastOcrText.isEmpty() ? "Visible text in camera: \"" + lastOcrText + "\"\n" : "") +
                        (navInstruction != null && !navInstruction.isEmpty() ? "Active walking navigation step: \"" + navInstruction + "\"\n" : "") +
                        "What should I do first or what is your advice?<|im_end|>\n" +
                        "<|im_start|>assistant\n";

                // Generate response with llama.cpp
                String rawJson = LlamaJNI.INSTANCE.nativeComplete(
                        modelHandle,
                        userPrompt,
                        "",
                        80,     // max tokens
                        0.3f,   // temperature (focused & deterministic)
                        0.9f,   // top-p
                        40,     // top-k
                        -1      // random seed
                );

                String speechText = null;
                if (rawJson != null && !rawJson.isEmpty()) {
                    try {
                        JSONObject obj = new JSONObject(rawJson);
                        if (!obj.has("error")) {
                            speechText = obj.optString("text", "").trim();
                        }
                    } catch (Exception ignored) {}
                }

                if (speechText == null || speechText.isEmpty()) {
                    speechText = SceneVoiceAssistant.answerQuery(
                            userQuery, detections, depthEstimator, guidanceEngine, imgWidth, imgHeight, lastOcrText);
                } else {
                    // Clean any stray chat tokens
                    speechText = speechText
                            .replace("<|im_end|>", "")
                            .replace("<|im_start|>", "")
                            .replaceAll("\\s+", " ")
                            .trim();
                }

                String action = extractActionIntent(userQuery);
                String dest = extractDestination(userQuery);

                final String finalSpeech = speechText;
                mainHandler.post(() -> callback.onResponse(finalSpeech, action, dest));
            } catch (Throwable t) {
                Log.e(TAG, "Qwen inference error: " + t.getMessage(), t);
                String fallback = SceneVoiceAssistant.answerQuery(
                        userQuery, detections, depthEstimator, guidanceEngine, imgWidth, imgHeight, lastOcrText);
                mainHandler.post(() -> callback.onResponse(fallback, extractActionIntent(userQuery), extractDestination(userQuery)));
            } finally {
                isInferring.set(false);
            }
        });
    }

    /**
     * Formats the visual scene array of detected objects with distance and position.
     */
    private String buildSceneArrayContext(
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            GuidanceEngine guidanceEngine,
            int imgWidth,
            int imgHeight) {

        if (detections == null || detections.isEmpty()) {
            return "No obstacles or objects detected. Path appears open.";
        }

        // Sort by distance (nearest first)
        List<Detection> sorted = new ArrayList<>(detections);
        sorted.sort((a, b) -> {
            float distA = depthEstimator != null ? depthEstimator.estimateDistanceMeters(a, imgWidth, imgHeight) : 10f;
            float distB = depthEstimator != null ? depthEstimator.estimateDistanceMeters(b, imgWidth, imgHeight) : 10f;
            return Float.compare(distA, distB);
        });

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Detection d : sorted) {
            if (count++ >= 6) break; // Limit to 6 nearest objects
            float dist = depthEstimator != null ? depthEstimator.estimateDistanceMeters(d, imgWidth, imgHeight) : 5f;
            String region = GuidanceEngine.regionFor(d, imgWidth);
            boolean moving = guidanceEngine != null && guidanceEngine.isMoving(d, imgWidth, imgHeight);

            sb.append("- ")
                    .append(d.label)
                    .append(": ")
                    .append(String.format(Locale.US, "%.1f", dist))
                    .append("m away, ")
                    .append(region.equals("center") ? "directly ahead (center)" : "to your " + region)
                    .append(moving ? ", MOVING" : ", stationary")
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private static String extractActionIntent(String query) {
        String q = query.toLowerCase(Locale.US).trim();
        if (q.contains("take me to") || q.contains("navigate to") || q.contains("directions to") || q.contains("walk to")) {
            return "NAVIGATE";
        }
        if (q.contains("read text") || q.contains("read sign") || q.contains("what does it say")) {
            return "READ_TEXT";
        }
        if (q.contains("start camera") || q.equals("start")) {
            return "START_CAMERA";
        }
        if (q.contains("stop camera") || q.equals("stop")) {
            return "STOP_CAMERA";
        }
        if (q.contains("medicine") || q.contains("check nfc") || q.contains("scan tag")) {
            return "CHECK_NFC";
        }
        return "NONE";
    }

    private static String extractDestination(String query) {
        String clean = query.toLowerCase(Locale.US)
                .replaceAll("^.*?(take me to|navigate to|directions to|walk to|route to|how to reach)\\s*", "")
                .trim();
        return clean.isEmpty() ? "" : clean;
    }

    /**
     * Downloads the Qwen GGUF model directly inside the app with live percentage progress.
     */
    void downloadModel(ModelStatusCallback callback) {
        if (isDownloading) return;
        isDownloading = true;

        executor.execute(() -> {
            File targetDir = new File(context.getFilesDir(), "models");
            if (!targetDir.exists()) targetDir.mkdirs();
            File targetFile = new File(targetDir, MODEL_FILENAME);
            File tempFile = new File(targetDir, MODEL_FILENAME + ".part");

            try {
                notifyStatus(callback, false, "Connecting to Hugging Face...");
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder().url(HF_DOWNLOAD_URL).build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        notifyStatus(callback, false, "Download failed (HTTP " + response.code() + ")");
                        isDownloading = false;
                        return;
                    }

                    long totalBytes = response.body().contentLength();
                    long downloadedBytes = 0;
                    byte[] buffer = new byte[64 * 1024];

                    try (InputStream in = response.body().byteStream();
                         OutputStream out = new FileOutputStream(tempFile)) {
                        int read;
                        long lastReport = 0;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            downloadedBytes += read;

                            long now = System.currentTimeMillis();
                            if (now - lastReport > 400 && totalBytes > 0) {
                                lastReport = now;
                                int percent = (int) (downloadedBytes * 100 / totalBytes);
                                int mbDownloaded = (int) (downloadedBytes / (1024 * 1024));
                                int mbTotal = (int) (totalBytes / (1024 * 1024));
                                String text = percent + "% (" + mbDownloaded + " MB / " + mbTotal + " MB)";
                                mainHandler.post(() -> callback.onDownloadProgress(percent, text));
                            }
                        }
                        out.flush();
                    }

                    if (tempFile.renameTo(targetFile)) {
                        notifyStatus(callback, false, "Download complete! Loading Qwen AI...");
                        loadModelAsync(callback);
                    } else {
                        notifyStatus(callback, false, "Failed to save model file.");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Download error: " + e.getMessage(), e);
                notifyStatus(callback, false, "Download failed: " + e.getMessage());
            } finally {
                isDownloading = false;
            }
        });
    }

    void shutdown() {
        if (modelHandle != 0) {
            try {
                LlamaJNI.INSTANCE.nativeReleaseModel(modelHandle);
            } catch (Exception ignored) {}
            modelHandle = 0;
            isLoaded = false;
        }
        executor.shutdownNow();
    }

    private void notifyStatus(ModelStatusCallback callback, boolean isReady, String msg) {
        if (callback != null) {
            mainHandler.post(() -> callback.onStatusChanged(isReady, msg));
        }
    }
}
