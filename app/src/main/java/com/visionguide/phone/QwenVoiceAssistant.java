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
                    // Initialize llama.cpp native session: contextSize=1024, threads=4, gpuLayers=0 (CPU NEON)
                    long handle = LlamaJNI.INSTANCE.nativeLoadModel(modelFile.getAbsolutePath(), 1024, 4, 0);
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

                File tempFile = new File(targetDir, MODEL_FILENAME + ".extract.tmp");
                String assetPath = "models/" + MODEL_FILENAME;
                try (InputStream in = context.getAssets().open(assetPath);
                     OutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[1024 * 1024]; // 1 MB buffer for fast transfer
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }

                if (tempFile.length() > 400_000_000L) {
                    if (targetFile.exists()) targetFile.delete();
                    if (tempFile.renameTo(targetFile)) {
                        return targetFile;
                    }
                }
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

                String systemPrompt = "You are Via Lux, a calm, friendly, and protective sighted walking companion for a blind person. " +
                        "You speak out loud directly to the user through their headphones. " +
                        "Guidelines: " +
                        "1. Speak naturally, warmly, and concisely in simple conversational English. " +
                        "2. Never use bullet points, markdown, asterisks, numbered lists, emojis, or computer jargon. " +
                        "3. First priority is safety: if anything is blocking their walking path or approaching within 3 meters, warn them immediately and tell them exactly what to do first. " +
                        "4. If the path ahead is clear, reassure them that it's safe to walk forward. " +
                        "5. Keep your response short: exactly 1 or 2 spoken sentences so the user is not overwhelmed.";

                StringBuilder promptBuilder = new StringBuilder();
                promptBuilder.append("User asks: \"").append(userQuery.trim()).append("\"\n\n");
                promptBuilder.append("Current camera and sensor view:\n");
                promptBuilder.append(sceneDescription).append("\n");
                if (lastOcrText != null && !lastOcrText.trim().isEmpty()) {
                    promptBuilder.append("Text on signs or objects in view: \"").append(lastOcrText.trim()).append("\"\n");
                }
                if (navInstruction != null && !navInstruction.trim().isEmpty()) {
                    promptBuilder.append("Active walking route direction: \"").append(navInstruction.trim()).append("\"\n");
                }
                promptBuilder.append("\nPlease give a natural, helpful, 1 or 2 sentence spoken response directly answering the user.");

                // Generate response with llama.cpp: prompt (user message), systemPrompt (system message)
                String rawJson = LlamaJNI.INSTANCE.nativeComplete(
                        modelHandle,
                        promptBuilder.toString(), // user prompt
                        systemPrompt,             // system prompt
                        100,                      // max tokens
                        0.55f,                    // temperature for natural spoken response
                        0.90f,                    // top-p
                        40,                       // top-k
                        -1                        // random seed
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

                if (speechText != null && !speechText.isEmpty()) {
                    speechText = sanitizeLlmResponse(speechText);
                }

                // If LLM returned empty, broken, or too short, use refined natural fallback
                if (speechText == null || speechText.length() < 4) {
                    speechText = SceneVoiceAssistant.answerQuery(
                            userQuery, detections, depthEstimator, guidanceEngine, imgWidth, imgHeight, lastOcrText);
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
     * Cleans raw LLM text for spoken natural audio: strips ChatML tags, prefixes, markdown, and emojis.
     */
    static String sanitizeLlmResponse(String raw) {
        if (raw == null) return "";
        String s = raw
                .replaceAll("<\\|[^>]*\\|>", "")
                .replaceAll("(?i)^(?:assistant|via\\s*lux|ai|system|bot|response):\\s*", "")
                .replaceAll("\\*\\*", "")
                .replaceAll("\\*", "")
                .replaceAll("#{1,6}\\s*", "")
                .replaceAll("`{1,3}[^`]*`{1,3}", "")
                .replaceAll("`", "")
                .replaceAll("(?m)^\\s*[-*•\\d+.]+\\s+", "")
                .replaceAll("[\\p{So}\\p{Cn}]", "")
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("\\((?:[0-9.]+\\s*(?:m|meters?|metres?)|moving|stationary)\\)", "")
                .replaceAll("\\s+", " ")
                .trim();

        // If the model was cut off mid-sentence, trim to the last sentence ending punctuation
        int lastPeriod = Math.max(s.lastIndexOf('.'), Math.max(s.lastIndexOf('!'), s.lastIndexOf('?')));
        if (lastPeriod > 20 && lastPeriod < s.length() - 1) {
            s = s.substring(0, lastPeriod + 1).trim();
        }
        return s;
    }

    /**
     * Formats the visual scene array of detected objects into natural descriptive English.
     */
    private String buildSceneArrayContext(
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            GuidanceEngine guidanceEngine,
            int imgWidth,
            int imgHeight) {

        if (detections == null || detections.isEmpty()) {
            return "The path directly ahead is completely clear of obstacles for more than 5 meters.";
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
            if (count++ >= 5) break; // Limit to 5 nearest objects
            float dist = depthEstimator != null ? depthEstimator.estimateDistanceMeters(d, imgWidth, imgHeight) : 4f;
            String region = GuidanceEngine.regionFor(d, imgWidth);
            boolean moving = guidanceEngine != null && guidanceEngine.isMoving(d, imgWidth, imgHeight);

            String pos = region.equals("center") ? "directly ahead in your path" : "on your " + region;
            String motion = moving ? "and is currently moving" : "stationary";
            String distStr = String.format(Locale.US, "%.1f meters away", dist);

            sb.append("- A ").append(d.label).append(" is ").append(distStr).append(" ").append(pos).append(" (").append(motion).append(").\n");
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
