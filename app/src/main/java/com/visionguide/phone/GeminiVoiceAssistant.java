package com.visionguide.phone;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * GeminiVoiceAssistant — Advanced Conversational AI powered by Google Gemini 3.5 Flash.
 *
 * Designed for visually impaired and blind users:
 *   1. Full Object Array Understanding:
 *      Interprets the 3D scene from YOLO detections, dual-camera depth estimates,
 *      corridor status, and clock-direction bearings. Explains room layout, environment,
 *      and informs the user WHAT THEY SHOULD DO FIRST to avoid hazards.
 *   2. Free-Hand App Navigation & Actions:
 *      Intelligently routes voice requests to actions (e.g. [ACTION:NAVIGATE:<dest>],
 *      [ACTION:START_CAMERA], [ACTION:READ_TEXT], [ACTION:CHECK_NFC], [ACTION:STOP]).
 *   3. Gemini Vision OCR:
 *      Processes high-res camera frames with multimodal vision to read medicine boxes,
 *      signs, documents, and packaging with 100% human-grade clarity.
 *   4. Zero-Downtime Fallback:
 *      If internet connectivity drops, seamlessly falls back to SceneVoiceAssistant
 *      so the user is never left without auditory guidance.
 */
final class GeminiVoiceAssistant {
    private static final String TAG = "GeminiVoiceAssistant";

    private static final String GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String PRIMARY_MODEL = "gemini-3.5-flash";
    private static final String FALLBACK_MODEL = "gemini-flash-latest";

    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.parse("application/json; charset=utf-8");

    interface AssistantCallback {
        void onResponse(String speechAnswer, String actionIntent, String destinationParam);
    }

    interface VisionOcrCallback {
        void onResult(String readableText);
    }

    interface AssistantStatusCallback {
        void onStatusChanged(boolean isReady, String message);
    }

    private final Context context;
    private final AppSettings settings;
    private final OkHttpClient httpClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isRequesting = new AtomicBoolean(false);

    GeminiVoiceAssistant(Context context, AppSettings settings) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build();
    }

    void initialize(AssistantStatusCallback callback) {
        if (callback != null) {
            String key = settings.geminiApiKey;
            if (key != null && !key.trim().isEmpty()) {
                callback.onStatusChanged(true, "✨ Gemini Voice AI Active (Gemini 3.5 Flash)");
            } else {
                callback.onStatusChanged(false, "⚠️ Gemini API key not set in Settings");
            }
        }
    }

    /**
     * Answers conversational queries and natural commands with deep spatial reasoning.
     */
    void askAssistant(
            String userQuery,
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            GuidanceEngine guidanceEngine,
            int imgWidth,
            int imgHeight,
            String lastOcrText,
            AssistantCallback callback) {

        final String cleanQuery = userQuery != null ? userQuery.trim() : "";
        if (cleanQuery.isEmpty()) return;

        executor.execute(() -> {
            String apiKey = settings.geminiApiKey != null ? settings.geminiApiKey.trim() : "";
            if (apiKey.isEmpty()) {
                fallbackLocal(cleanQuery, detections, depthEstimator, guidanceEngine, imgWidth, imgHeight, lastOcrText, callback);
                return;
            }

            try {
                String prompt = buildSpatialPrompt(cleanQuery, detections, depthEstimator, guidanceEngine, imgWidth, imgHeight, lastOcrText);
                String responseText = callGeminiApi(prompt, apiKey, PRIMARY_MODEL);
                if (responseText == null || responseText.trim().isEmpty()) {
                    responseText = callGeminiApi(prompt, apiKey, FALLBACK_MODEL);
                }

                if (responseText != null && !responseText.trim().isEmpty()) {
                    ParsedResponse parsed = parseResponse(responseText);
                    mainHandler.post(() -> callback.onResponse(parsed.speech, parsed.action, parsed.dest));
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Gemini assistant call failed: " + e.getMessage());
            }

            // Local fallback
            fallbackLocal(cleanQuery, detections, depthEstimator, guidanceEngine, imgWidth, imgHeight, lastOcrText, callback);
        });
    }

    /**
     * Reads text in the given camera frame using Gemini Multimodal Vision.
     */
    void readImageWithGemini(Bitmap sourceBitmap, VisionOcrCallback callback) {
        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            mainHandler.post(() -> callback.onResult("Camera frame unavailable."));
            return;
        }

        executor.execute(() -> {
            String apiKey = settings.geminiApiKey != null ? settings.geminiApiKey.trim() : "";
            if (apiKey.isEmpty()) {
                mainHandler.post(() -> callback.onResult("Gemini API key is not configured."));
                return;
            }

            try {
                // Compress frame to high-quality JPEG
                int maxDim = 1280;
                Bitmap scaled = sourceBitmap;
                if (sourceBitmap.getWidth() > maxDim || sourceBitmap.getHeight() > maxDim) {
                    float ratio = maxDim / (float) Math.max(sourceBitmap.getWidth(), sourceBitmap.getHeight());
                    scaled = Bitmap.createScaledBitmap(
                            sourceBitmap,
                            Math.round(sourceBitmap.getWidth() * ratio),
                            Math.round(sourceBitmap.getHeight() * ratio),
                            true);
                }

                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream);
                byte[] jpegBytes = stream.toByteArray();
                if (scaled != sourceBitmap) scaled.recycle();

                String base64Jpeg = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);

                String instruction = "You are an assistive reading assistant for a blind person. " +
                        "Read ALL text visible in this image clearly, completely, and accurately. " +
                        "If it is a medicine box, document, product packaging, or sign, read the critical details in logical reading order (e.g. medicine name, strength, dosage, directions, or notice details). " +
                        "Speak naturally and clearly in plain text with no markdown, asterisks, bullet points, or emojis. " +
                        "If no text is visible, say: 'No readable text found.'";

                JSONObject inlineData = new JSONObject();
                inlineData.put("mime_type", "image/jpeg");
                inlineData.put("data", base64Jpeg);

                JSONObject textPart = new JSONObject();
                textPart.put("text", instruction);

                JSONObject imagePart = new JSONObject();
                imagePart.put("inline_data", inlineData);

                JSONArray parts = new JSONArray();
                parts.put(textPart);
                parts.put(imagePart);

                JSONObject contentObj = new JSONObject();
                contentObj.put("parts", parts);

                JSONArray contents = new JSONArray();
                contents.put(contentObj);

                JSONObject root = new JSONObject();
                root.put("contents", contents);

                String url = GEMINI_BASE_URL + PRIMARY_MODEL + ":generateContent?key=" + apiKey;
                RequestBody body = RequestBody.create(root.toString(), JSON_MEDIA_TYPE);
                Request request = new Request.Builder().url(url).post(body).build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String jsonResp = response.body().string();
                        String extracted = extractTextFromGeminiJson(jsonResp);
                        if (extracted != null && !extracted.trim().isEmpty()) {
                            String clean = cleanSpeech(extracted);
                            mainHandler.post(() -> callback.onResult(clean));
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Gemini vision OCR error: " + e.getMessage());
            }

            mainHandler.post(() -> callback.onResult("Could not read text with Gemini. Please try again."));
        });
    }

    private String callGeminiApi(String prompt, String apiKey, String model) throws IOException {
        try {
            JSONObject textPart = new JSONObject();
            textPart.put("text", prompt);

            JSONArray parts = new JSONArray();
            parts.put(textPart);

            JSONObject contentObj = new JSONObject();
            contentObj.put("parts", parts);

            JSONArray contents = new JSONArray();
            contents.put(contentObj);

            JSONObject root = new JSONObject();
            root.put("contents", contents);

            String url = GEMINI_BASE_URL + model + ":generateContent?key=" + apiKey;
            RequestBody body = RequestBody.create(root.toString(), JSON_MEDIA_TYPE);
            Request request = new Request.Builder().url(url).post(body).build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return extractTextFromGeminiJson(response.body().string());
                } else {
                    Log.w(TAG, "Gemini API error code: " + response.code());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "callGeminiApi error: " + e.getMessage());
        }
        return null;
    }

    private static String extractTextFromGeminiJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates != null && candidates.length() > 0) {
                JSONObject first = candidates.getJSONObject(0);
                JSONObject content = first.optJSONObject("content");
                if (content != null) {
                    JSONArray parts = content.optJSONArray("parts");
                    if (parts != null && parts.length() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < parts.length(); i++) {
                            JSONObject p = parts.getJSONObject(i);
                            if (p.has("text")) {
                                sb.append(p.getString("text")).append(" ");
                            }
                        }
                        return sb.toString().trim();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "JSON extraction error: " + e.getMessage());
        }
        return null;
    }

    private String buildSpatialPrompt(
            String userQuery,
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            GuidanceEngine guidanceEngine,
            int imgWidth,
            int imgHeight,
            String lastOcrText) {

        SceneLayoutAnalyzer.LayoutInfo layout = SceneLayoutAnalyzer.analyze(
                detections, depthEstimator, imgWidth, imgHeight);

        StringBuilder sb = new StringBuilder();
        sb.append("System Instructions:\n");
        sb.append("You are NeuroVision, a state-of-the-art visual guide and safety navigator for a blind user.\n");
        sb.append("1. Speak naturally, warmly, and concisely in simple conversational English.\n");
        sb.append("2. Keep your response short: exactly 1 or 2 spoken sentences so the blind user is never overwhelmed.\n");
        sb.append("3. Never use asterisks, markdown, bullet points, numbered lists, emojis, or computer jargon.\n");
        sb.append("4. Prioritize immediate physical safety: if an obstacle or hazard blocks their path, warn them immediately and tell them exactly what action to take first (e.g. stop, move left, move right).\n");
        sb.append("5. If both left and right sides are blocked, tell them to STOP and scan slowly left and right.\n");
        sb.append("6. When multiple objects of the same kind exist (e.g. 3 chairs), state the count (e.g. 'There are 3 chairs ahead') and refer to them as Chair 1, Chair 2, etc.\n");
        sb.append("7. If the user asks to perform an action, append an action tag at the very end of your response:\n");
        sb.append("   - To navigate somewhere: [ACTION:NAVIGATE:<destination>]\n");
        sb.append("   - To start camera: [ACTION:START_CAMERA]\n");
        sb.append("   - To stop camera: [ACTION:STOP_CAMERA]\n");
        sb.append("   - To read text: [ACTION:READ_TEXT]\n");
        sb.append("   - To check NFC medicine tag: [ACTION:CHECK_NFC]\n");
        sb.append("   - To silence/stop: [ACTION:STOP]\n\n");

        sb.append("Current Scene Understanding:\n");
        sb.append("Environment: ").append(layout.environmentSummary).append("\n");
        sb.append("Walking Corridors: Center is ").append(layout.centerBlocked ? "BLOCKED" : "CLEAR").append("\n");
        sb.append("Recommended Walking Corridor: ").append(layout.recommendedCorridor).append(" corridor\n");

        if (guidanceEngine != null && guidanceEngine.getInstanceTracker() != null) {
            String aggregated = guidanceEngine.getInstanceTracker().buildAggregatedDescription(
                    detections, depthEstimator, imgWidth, imgHeight);
            if (!aggregated.isEmpty()) {
                sb.append("Multi-Instance Summary: ").append(aggregated).append("\n");
            }
        }

        if (lastOcrText != null && !lastOcrText.isEmpty()) {
            sb.append("Visible Text in view: \"").append(lastOcrText).append("\"\n");
        }

        sb.append("Detected Objects Array:\n");
        if (detections == null || detections.isEmpty()) {
            sb.append("- None. Path ahead is completely open.\n");
        } else {
            int count = 0;
            for (Detection d : detections) {
                if (count++ >= 8) break;
                float dist = depthEstimator != null
                        ? depthEstimator.estimateDistanceMeters(d, imgWidth, imgHeight)
                        : 3.0f;
                String reg = GuidanceEngine.regionFor(d, imgWidth);
                String cell = GuidanceEngine.gridCellFor(d, imgWidth, imgHeight);
                boolean moving = guidanceEngine != null && guidanceEngine.isMoving(d, imgWidth, imgHeight);

                // Clock position
                float normX = d.centerX() / (float) imgWidth;
                String clock = normX < 0.35f ? "10 o'clock (to your left)" :
                        normX > 0.65f ? "2 o'clock (to your right)" : "12 o'clock (straight ahead)";

                sb.append("- ").append(d.label).append(": at ").append(clock)
                        .append(", ").append(String.format(Locale.US, "%.1f", dist)).append(" meters away")
                        .append(", in ").append(cell).append(" (").append(reg).append(")")
                        .append(moving ? ", moving" : ", stationary").append("\n");
            }
        }

        sb.append("\nUser asks: \"").append(userQuery).append("\"\n");
        sb.append("Assistant Spoken Response:\n");
        return sb.toString();
    }

    private ParsedResponse parseResponse(String raw) {
        String clean = raw.trim();
        String action = null;
        String dest = null;

        if (clean.contains("[ACTION:NAVIGATE:")) {
            int start = clean.indexOf("[ACTION:NAVIGATE:");
            int end = clean.indexOf("]", start);
            if (end > start) {
                dest = clean.substring(start + 17, end).trim();
                action = "NAVIGATE";
                clean = clean.substring(0, start) + clean.substring(end + 1);
            }
        } else if (clean.contains("[ACTION:START_CAMERA]")) {
            action = "START_CAMERA";
            clean = clean.replace("[ACTION:START_CAMERA]", "");
        } else if (clean.contains("[ACTION:STOP_CAMERA]")) {
            action = "STOP_CAMERA";
            clean = clean.replace("[ACTION:STOP_CAMERA]", "");
        } else if (clean.contains("[ACTION:READ_TEXT]")) {
            action = "READ_TEXT";
            clean = clean.replace("[ACTION:READ_TEXT]", "");
        } else if (clean.contains("[ACTION:CHECK_NFC]")) {
            action = "CHECK_NFC";
            clean = clean.replace("[ACTION:CHECK_NFC]", "");
        } else if (clean.contains("[ACTION:STOP]")) {
            action = "STOP";
            clean = clean.replace("[ACTION:STOP]", "");
        }

        String speech = cleanSpeech(clean);
        return new ParsedResponse(speech, action, dest);
    }

    private void fallbackLocal(
            String userQuery,
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            GuidanceEngine guidanceEngine,
            int imgWidth,
            int imgHeight,
            String lastOcrText,
            AssistantCallback callback) {

        String answer = SceneVoiceAssistant.answerQuery(
                userQuery,
                detections,
                depthEstimator,
                guidanceEngine,
                imgWidth,
                imgHeight,
                lastOcrText
        );
        mainHandler.post(() -> callback.onResponse(answer, null, null));
    }

    private static String cleanSpeech(String text) {
        if (text == null) return "";
        return text
                .replaceAll("\\*\\*", "")
                .replaceAll("\\*", "")
                .replaceAll("#{1,6}\\s*", "")
                .replaceAll("`{1,3}[^`]*`{1,3}", "")
                .replaceAll("(?m)^\\s*[-*•\\d+.]+\\s+", "")
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("[\\p{So}\\p{Cn}]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private static final class ParsedResponse {
        final String speech;
        final String action;
        final String dest;
        ParsedResponse(String speech, String action, String dest) {
            this.speech = speech; this.action = action; this.dest = dest;
        }
    }
}
