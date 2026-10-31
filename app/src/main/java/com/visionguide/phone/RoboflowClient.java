package com.visionguide.phone;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * RoboflowClient — sends a camera frame to the hosted Roboflow BlindAid Workflow
 * and returns a list of detections. Internet required.
 *
 * SECURITY: The API key must never be hard-coded here or committed to Git.
 * It is loaded from AppSettings, which reads it from SharedPreferences
 * (entered by the user in SettingsDialog). For production builds consider
 * routing through a backend proxy so the key is not stored on the device.
 */
final class RoboflowClient {
    private static final String TAG = "RoboflowClient";

    /** Roboflow Hosted Inference endpoint — replace with your Workflow URL if different. */
    private static final String ENDPOINT =
            "https://detect.roboflow.com/blind_aid_yolov8n-o7fs2/1";

    private static final float CONFIDENCE_THRESHOLD = 0.40f;
    private static final int JPEG_QUALITY   = 80;
    private static final int TIMEOUT_S      = 8;

    private final OkHttpClient http;

    RoboflowClient() {
        http = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_S,    TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_S,   TimeUnit.SECONDS)
                .build();
    }

    /**
     * Send a bitmap to Roboflow and return detections.
     * This is a blocking call — run on a background thread.
     *
     * @param bitmap    Frame to analyse.
     * @param apiKey    Roboflow API key (from user settings, never hard-coded).
     * @param imgWidth  Original image width for coordinate scaling.
     * @param imgHeight Original image height for coordinate scaling.
     * @return List of detections, or empty list on error.
     */
    List<Detection> detect(Bitmap bitmap, String apiKey, int imgWidth, int imgHeight) {
        if (apiKey == null || apiKey.isEmpty()) {
            Log.w(TAG, "Roboflow API key is not set. Online detection skipped.");
            return Collections.emptyList();
        }
        try {
            byte[] jpeg = toJpeg(bitmap);
            String url = ENDPOINT + "?api_key=" + apiKey
                    + "&confidence=" + Math.round(CONFIDENCE_THRESHOLD * 100);
            RequestBody body = RequestBody.create(jpeg, MediaType.parse("application/octet-stream"));
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();
            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.w(TAG, "Roboflow response: " + response.code());
                    return Collections.emptyList();
                }
                return parseResponse(response.body().string(), imgWidth, imgHeight);
            }
        } catch (Exception e) {
            Log.w(TAG, "Roboflow request failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Check connectivity before making a request. */
    static boolean isOnline(ConnectivityManager cm) {
        if (cm == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null
                && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private static List<Detection> parseResponse(String json, int imgWidth, int imgHeight) {
        List<Detection> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray predictions = root.optJSONArray("predictions");
            if (predictions == null) return result;
            for (int i = 0; i < predictions.length(); i++) {
                JSONObject p = predictions.getJSONObject(i);
                float confidence = (float) p.getDouble("confidence");
                if (confidence < CONFIDENCE_THRESHOLD) continue;
                String label = p.optString("class", "object");
                // Roboflow returns centre x,y and w,h (in pixels of the submitted image)
                float cx = (float) p.getDouble("x");
                float cy = (float) p.getDouble("y");
                float w  = (float) p.getDouble("width");
                float h  = (float) p.getDouble("height");
                // Scale to original image dimensions if Roboflow resized the input
                float inW = (float) p.optDouble("image_width",  imgWidth);
                float inH = (float) p.optDouble("image_height", imgHeight);
                float scaleX = imgWidth  / Math.max(1f, inW);
                float scaleY = imgHeight / Math.max(1f, inH);
                RectF box = new RectF(
                        (cx - w / 2f) * scaleX,
                        (cy - h / 2f) * scaleY,
                        (cx + w / 2f) * scaleX,
                        (cy + h / 2f) * scaleY
                );
                result.add(new Detection(box, label, confidence, -1));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse Roboflow response: " + e.getMessage());
        }
        return result;
    }

    private static byte[] toJpeg(Bitmap bitmap) throws Exception {
        // Scale down to reduce payload size (640px on longest side)
        int maxDim = 640;
        Bitmap scaled;
        if (bitmap.getWidth() > maxDim || bitmap.getHeight() > maxDim) {
            float scale = maxDim / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
            scaled = Bitmap.createScaledBitmap(bitmap,
                    Math.round(bitmap.getWidth() * scale),
                    Math.round(bitmap.getHeight() * scale), true);
        } else {
            scaled = bitmap;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        if (scaled != bitmap) scaled.recycle();
        return out.toByteArray();
    }
}
