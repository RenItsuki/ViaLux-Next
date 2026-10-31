package com.visionguide.phone;

import android.Manifest;
import androidx.appcompat.app.AppCompatActivity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CAMERA = 42;

    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView guidanceText;
    private TextView statusText;
    private TextView loadingText;
    private TextView settingsSummary;
    private Button startStopButton;
    private Button settingsButton;
    private SpeechGuide speechGuide;
    private GuidanceEngine guidanceEngine;
    private AppSettings settings;
    private YoloOnnxDetector detector;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean analyzing = new AtomicBoolean(false);
    private long lastAnalysisTime = 0;
    private boolean running = false;
    private final Runnable loadingPulse = new Runnable() {
        @Override
        public void run() {
            if (!running || loadingText.getVisibility() != View.VISIBLE) {
                return;
            }
            loadingText.animate()
                    .alpha(loadingText.getAlpha() < 0.6f ? 1f : 0.35f)
                    .setDuration(520)
                    .withEndAction(() -> mainHandler.postDelayed(this, 80))
                    .start();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor = Executors.newSingleThreadExecutor();
        speechGuide = new SpeechGuide(this);
        guidanceEngine = new GuidanceEngine();
        settings = new AppSettings(this);
        buildUi();
        loadDetector();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startDetection();
        } else {
            Toast.makeText(this, "Camera permission is needed.", Toast.LENGTH_LONG).show();
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(7, 12, 18));
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setAlpha(0f);
        overlayView = new OverlayView(this);

        LinearLayout chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setPadding(dp(18), dp(18), dp(18), dp(18));
        chrome.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Via Lux");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);

        statusText = new TextView(this);
        statusText.setText("Ready when you are.");
        statusText.setTextColor(0xFFB8C7D8);
        statusText.setTextSize(15f);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(4), 0, dp(12));

        guidanceText = new TextView(this);
        guidanceText.setText("Press Start to open the camera.");
        guidanceText.setTextColor(0xFFFFFFFF);
        guidanceText.setTextSize(21f);
        guidanceText.setTypeface(Typeface.DEFAULT_BOLD);
        guidanceText.setGravity(Gravity.CENTER);
        guidanceText.setPadding(dp(18), dp(16), dp(18), dp(16));
        guidanceText.setBackground(card(0xCC111C28, dp(22), 0x5537D4A7));

        settingsSummary = new TextView(this);
        settingsSummary.setText(settingsSummaryText());
        settingsSummary.setTextColor(0xFF9FB0C3);
        settingsSummary.setTextSize(13f);
        settingsSummary.setGravity(Gravity.CENTER);
        settingsSummary.setPadding(0, dp(10), 0, dp(10));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        startStopButton = button("Start");
        startStopButton.setOnClickListener(v -> {
            if (running) {
                stopDetection();
            } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startDetection();
            } else {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            }
        });

        settingsButton = button("Settings");
        settingsButton.setOnClickListener(v -> SettingsDialog.show(this, settings, () -> {
            settings.load();
            guidanceEngine.reset();
            settingsSummary.setText(settingsSummaryText());
            Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show();
        }));

        controls.addView(startStopButton, buttonParams());
        controls.addView(settingsButton, buttonParams());

        loadingText = new TextView(this);
        loadingText.setText("Opening camera...");
        loadingText.setTextColor(0xFF37D4A7);
        loadingText.setTextSize(18f);
        loadingText.setTypeface(Typeface.DEFAULT_BOLD);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setVisibility(View.GONE);

        LinearLayout.LayoutParams fullWidth = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        fullWidth.setMargins(0, dp(8), 0, dp(8));
        chrome.addView(title, fullWidth);
        chrome.addView(statusText, fullWidth);
        chrome.addView(guidanceText, fullWidth);
        chrome.addView(settingsSummary, fullWidth);
        chrome.addView(controls, fullWidth);
        chrome.addView(loadingText, fullWidth);

        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        FrameLayout.LayoutParams guidanceParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        root.addView(chrome, guidanceParams);
        setContentView(root);
    }

    private void loadDetector() {
        cameraExecutor.execute(() -> {
            try {
                detector = new YoloOnnxDetector(this);
                runOnUiThread(() -> statusText.setText("AI ready. Press Start."));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Detector error");
                    guidanceText.setText(e.getMessage());
                });
            }
        });
    }

    private void startDetection() {
        running = true;
        guidanceEngine.reset();
        startStopButton.setText("Stop");
        statusText.setText("Opening camera...");
        guidanceText.setText("Hold phone upright and point forward.");
        loadingText.setVisibility(View.VISIBLE);
        loadingText.setAlpha(1f);
        mainHandler.post(loadingPulse);
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                );
                statusText.setText("Camera active");
                loadingText.animate().cancel();
                loadingText.setVisibility(View.GONE);
                previewView.animate().alpha(1f).setDuration(450).start();
            } catch (Exception e) {
                running = false;
                startStopButton.setText("Start");
                guidanceText.setText("Camera error: " + e.getMessage());
            }
        }, command -> mainHandler.post(command));
    }

    private void stopDetection() {
        running = false;
        analyzing.set(false);
        loadingText.animate().cancel();
        loadingText.setVisibility(View.GONE);
        startStopButton.setText("Start");
        statusText.setText("Stopped");
        guidanceText.setText("Detection stopped. Press Start when ready.");
        previewView.animate().alpha(0.25f).setDuration(250).start();
        overlayView.update(Collections.emptyList(), guidanceEngine, 1, 1);
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    private void analyzeImage(ImageProxy image) {
        if (!running) {
            image.close();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastAnalysisTime < 350 || !analyzing.compareAndSet(false, true)) {
            image.close();
            return;
        }
        lastAnalysisTime = now;
        try {
            YoloOnnxDetector localDetector = detector;
            if (localDetector == null) {
                image.close();
                analyzing.set(false);
                return;
            }
            Bitmap bitmap = ImageTools.bitmapFromRgbaImage(image);
            image.close();
            List<Detection> detections = localDetector.detect(bitmap);
            Guidance guidance = guidanceEngine.evaluate(detections, bitmap.getWidth(), bitmap.getHeight(), now, settings);
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            bitmap.recycle();
            runOnUiThread(() -> updateUi(detections, guidance, width, height));
        } catch (Exception e) {
            image.close();
            runOnUiThread(() -> guidanceText.setText("Analysis error: " + e.getMessage()));
        } finally {
            analyzing.set(false);
        }
    }

    private void updateUi(List<Detection> detections, Guidance guidance, int width, int height) {
        if (!running) {
            return;
        }
        overlayView.update(detections == null ? Collections.emptyList() : detections, guidanceEngine, width, height);
        if (guidance != null) {
            guidanceText.setText(guidance.message);
            speechGuide.speak(guidance.message, guidance.emergency, guidance.priority);
        }
    }

    private String settingsSummaryText() {
        return "Speech " + (settings.speechDelayMs / 1000f) + "s"
                + " - Repeat " + (settings.repeatCooldownMs / 1000) + "s"
                + " - Motion " + settings.motionLabel()
                + " - Notify " + settings.notifyDistanceLabel();
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16f);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(card(0xDD1A2A3B, dp(18), 0x6637D4A7));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(54), 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private GradientDrawable card(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDetection();
        if (speechGuide != null) {
            speechGuide.shutdown();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdownNow();
        }
        if (detector != null) {
            try {
                detector.close();
            } catch (Exception ignored) {
            }
        }
    }
}
