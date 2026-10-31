package com.visionguide.phone;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
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

    // ── Permission request codes ───────────────────────────────────────────
    private static final int REQ_CAMERA   = 42;
    private static final int REQ_LOCATION = 43;

    // ── Timing ────────────────────────────────────────────────────────────
    /** Minimum ms between inference runs (local model). */
    private static final long ANALYSIS_INTERVAL_MS = 350;
    /** Minimum ms between Roboflow requests. */
    private static final long ONLINE_INTERVAL_MS   = 2000;

    // ── Core components ───────────────────────────────────────────────────
    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView guidanceText;
    private TextView statusText;
    private TextView loadingText;
    private TextView settingsSummary;
    private Button startStopButton;
    private Button settingsButton;
    private Button ocrButton;
    private Button navButton;
    private Button nfcButton;

    private SpeechGuide speechGuide;
    private GuidanceEngine guidanceEngine;
    private AppSettings settings;
    private YoloOnnxDetector detector;
    private StairVisionDetector stairDetector;
    private OcrProcessor ocrProcessor;
    private RoboflowClient roboflowClient;
    private NavigationGuide navigationGuide;
    private NfcMedicineReader nfcReader;
    private MedicineStore medicineStore;
    private DetectionMerger detectionMerger; // stateless utility — kept for clarity

    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private ExecutorService onlineExecutor;
    private ConnectivityManager connectivityManager;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean analyzing = new AtomicBoolean(false);
    private final AtomicBoolean onlinePending = new AtomicBoolean(false);
    private long lastAnalysisTime = 0;
    private long lastOnlineTime   = 0;
    private boolean running = false;

    // Latest online detections (written by online executor, read on camera executor)
    private volatile List<Detection> latestOnlineDetections = Collections.emptyList();

    // ── Loading animation ──────────────────────────────────────────────────
    private final Runnable loadingPulse = new Runnable() {
        @Override public void run() {
            if (!running || loadingText.getVisibility() != View.VISIBLE) return;
            loadingText.animate()
                    .alpha(loadingText.getAlpha() < 0.6f ? 1f : 0.35f)
                    .setDuration(520)
                    .withEndAction(() -> mainHandler.postDelayed(this, 80))
                    .start();
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // Activity lifecycle
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor  = Executors.newSingleThreadExecutor();
        onlineExecutor  = Executors.newSingleThreadExecutor();
        speechGuide     = new SpeechGuide(this);
        guidanceEngine  = new GuidanceEngine();
        settings        = new AppSettings(this);
        ocrProcessor    = new OcrProcessor();
        roboflowClient  = new RoboflowClient();
        navigationGuide = new NavigationGuide(this);
        medicineStore   = new MedicineStore(this);
        nfcReader       = new NfcMedicineReader(this);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        buildUi();
        loadDetector();
        // Handle NFC tap at launch
        handleNfcIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (settings.nfcEnabled) nfcReader.enable(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        nfcReader.disable(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNfcIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDetection();
        if (speechGuide    != null) speechGuide.shutdown();
        if (ocrProcessor   != null) ocrProcessor.shutdown();
        if (navigationGuide != null) navigationGuide.stop();
        if (cameraExecutor != null) cameraExecutor.shutdownNow();
        if (onlineExecutor != null) onlineExecutor.shutdownNow();
        if (detector != null) { try { detector.close(); } catch (Exception ignored) {} }
        if (stairDetector != null) { try { stairDetector.close(); } catch (Exception ignored) {} }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == REQ_CAMERA && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            startDetection();
        } else if (code == REQ_LOCATION && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            startNavigation();
        } else {
            Toast.makeText(this, "Permission required.", Toast.LENGTH_LONG).show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UI construction
    // ─────────────────────────────────────────────────────────────────────

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

        // Row 1: Start / Stop + Settings
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);

        startStopButton = button("Start");
        startStopButton.setOnClickListener(v -> {
            if (running) {
                stopDetection();
            } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startDetection();
            } else {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
        });

        settingsButton = button("Settings");
        settingsButton.setOnClickListener(v -> SettingsDialog.show(this, settings, () -> {
            settings.load();
            guidanceEngine.reset();
            ocrProcessor.setIntervalMs(settings.ocrIntervalMs);
            settingsSummary.setText(settingsSummaryText());
            updateModeIndicators();
            Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show();
        }));

        row1.addView(startStopButton, buttonParams());
        row1.addView(settingsButton,  buttonParams());

        // Row 2: OCR + Nav + NFC
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);

        ocrButton = button("Read Text");
        ocrButton.setVisibility(settings.ocrEnabled ? View.VISIBLE : View.GONE);
        ocrButton.setOnClickListener(v -> {
            if (ocrProcessor.isArmed()) {
                String last = ocrProcessor.repeat();
                if (!last.isEmpty()) speechGuide.speak(last, false, Guidance.PRIORITY_INFO);
            } else {
                ocrProcessor.requestRead();
                ocrButton.setText("Reading...");
            }
        });

        navButton = button("Navigate");
        navButton.setVisibility(settings.navigationEnabled ? View.VISIBLE : View.GONE);
        navButton.setOnClickListener(v -> {
            if (navigationGuide.isActive()) {
                navigationGuide.stop();
                navButton.setText("Navigate");
                statusText.setText("Navigation stopped.");
            } else {
                showDestinationDialog();
            }
        });

        nfcButton = button("NFC Tags");
        nfcButton.setVisibility(settings.nfcEnabled ? View.VISIBLE : View.GONE);
        nfcButton.setOnClickListener(v ->
                startActivity(new Intent(this, MedicineRegisterActivity.class)));

        row2.addView(ocrButton,  buttonParams());
        row2.addView(navButton,  buttonParams());
        row2.addView(nfcButton,  buttonParams());

        loadingText = new TextView(this);
        loadingText.setText("Opening camera...");
        loadingText.setTextColor(0xFF37D4A7);
        loadingText.setTextSize(18f);
        loadingText.setTypeface(Typeface.DEFAULT_BOLD);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setVisibility(View.GONE);

        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        full.setMargins(0, dp(8), 0, dp(8));

        chrome.addView(title,           full);
        chrome.addView(statusText,      full);
        chrome.addView(guidanceText,    full);
        chrome.addView(settingsSummary, full);
        chrome.addView(row1,            full);
        chrome.addView(row2,            full);
        chrome.addView(loadingText,     full);

        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams guideParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(chrome, guideParams);
        setContentView(root);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Detector loading
    // ─────────────────────────────────────────────────────────────────────

    private void loadDetector() {
        cameraExecutor.execute(() -> {
            try {
                detector = new YoloOnnxDetector(this);
                try {
                    stairDetector = new StairVisionDetector(this);
                } catch (Exception e) {
                    android.util.Log.w("MainActivity", "StairVision optional load error: " + e.getMessage());
                }
                runOnUiThread(() -> statusText.setText("AI ready. Press Start."));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Detector error");
                    guidanceText.setText(e.getMessage());
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // Camera start / stop
    // ─────────────────────────────────────────────────────────────────────

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
                cameraProvider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);

                statusText.setText("Camera active");
                loadingText.animate().cancel();
                loadingText.setVisibility(View.GONE);
                previewView.animate().alpha(1f).setDuration(450).start();
            } catch (Exception e) {
                running = false;
                startStopButton.setText("Start");
                guidanceText.setText("Camera error: " + e.getMessage());
            }
        }, cmd -> mainHandler.post(cmd));
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
        if (cameraProvider != null) cameraProvider.unbindAll();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Analysis loop (runs on cameraExecutor)
    // ─────────────────────────────────────────────────────────────────────

    private void analyzeImage(ImageProxy image) {
        if (!running) { image.close(); return; }
        long now = SystemClock.elapsedRealtime();
        if (now - lastAnalysisTime < ANALYSIS_INTERVAL_MS || !analyzing.compareAndSet(false, true)) {
            image.close(); return;
        }
        lastAnalysisTime = now;
        try {
            YoloOnnxDetector localDetector = detector;
            if (localDetector == null) { image.close(); analyzing.set(false); return; }

            Bitmap bitmap = ImageTools.bitmapFromRgbaImage(image);
            image.close();
            int width  = bitmap.getWidth();
            int height = bitmap.getHeight();

            // ── Local inference ─────────────────────────────────────────
            List<Detection> local = localDetector.detect(bitmap);

            // ── StairVision offline stair detection ─────────────────────
            StairVisionDetector localStairDetector = stairDetector;
            if (localStairDetector != null) {
                List<Detection> stairDetections = localStairDetector.detect(bitmap);
                if (!stairDetections.isEmpty()) {
                    local = DetectionMerger.merge(local, stairDetections);
                }
            }

            // ── Online inference (throttled, non-blocking) ──────────────
            if (settings.onlineModeEnabled
                    && !settings.roboflowApiKey.isEmpty()
                    && now - lastOnlineTime >= ONLINE_INTERVAL_MS
                    && onlinePending.compareAndSet(false, true)
                    && RoboflowClient.isOnline(connectivityManager)) {
                lastOnlineTime = now;
                final Bitmap frameCopy = bitmap.copy(bitmap.getConfig(), false);
                final int fw = width, fh = height;
                onlineExecutor.execute(() -> {
                    List<Detection> online = roboflowClient.detect(
                            frameCopy, settings.roboflowApiKey, fw, fh);
                    frameCopy.recycle();
                    latestOnlineDetections = online;
                    onlinePending.set(false);
                });
            }

            // ── Merge ───────────────────────────────────────────────────
            List<Detection> merged = DetectionMerger.merge(local, latestOnlineDetections);

            // ── OCR (on demand) ─────────────────────────────────────────
            if (settings.ocrEnabled) {
                ocrProcessor.process(bitmap, now, text -> runOnUiThread(() -> {
                    guidanceText.setText(text);
                    speechGuide.speak(text, false, Guidance.PRIORITY_INFO);
                    ocrButton.setText("Read Text");
                }));
            }

            // ── GuidanceEngine ──────────────────────────────────────────
            Guidance guidance = guidanceEngine.evaluate(merged, width, height, now, settings);
            bitmap.recycle();

            runOnUiThread(() -> updateUi(merged, guidance, width, height));
        } catch (Exception e) {
            image.close();
            runOnUiThread(() -> guidanceText.setText("Analysis error: " + e.getMessage()));
        } finally {
            analyzing.set(false);
        }
    }

    private void updateUi(List<Detection> detections, Guidance guidance, int width, int height) {
        if (!running) return;
        overlayView.update(
                detections == null ? Collections.emptyList() : detections,
                guidanceEngine, width, height);
        if (guidance != null) {
            guidanceText.setText(guidance.message);
            speechGuide.speak(guidance.message, guidance.emergency, guidance.priority);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Navigation
    // ─────────────────────────────────────────────────────────────────────

    private void showDestinationDialog() {
        EditText input = new EditText(this);
        input.setHint("Enter destination");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF607080);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AlertDialog.Builder(this)
                .setTitle("Navigate to…")
                .setView(input)
                .setPositiveButton("Go", (d, w) -> {
                    String dest = input.getText().toString().trim();
                    if (!dest.isEmpty()) requestLocationThenNavigate(dest);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestLocationThenNavigate(String destination) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Store destination temporarily — handled after permission is granted
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            return;
        }
        startNavigation(destination);
    }

    private void startNavigation() {
        // Called after location permission is granted; destination already known
        showDestinationDialog();
    }

    private void startNavigation(String destination) {
        navButton.setText("Stop Nav");
        statusText.setText("Getting route…");
        navigationGuide.start(destination, settings.mapsApiKey, (instruction, isError) -> {
            if (isError) {
                statusText.setText(instruction);
                speechGuide.speak(instruction, false, Guidance.PRIORITY_INFO);
                navButton.setText("Navigate");
            } else {
                statusText.setText(instruction);
                speechGuide.speak(instruction, false, Guidance.PRIORITY_INFO);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // NFC
    // ─────────────────────────────────────────────────────────────────────

    private void handleNfcIntent(Intent intent) {
        if (!settings.nfcEnabled) return;
        String message = nfcReader.handleIntent(intent, medicineStore);
        if (message != null) {
            guidanceText.setText(message);
            speechGuide.speak(message, false, Guidance.PRIORITY_IMPORTANT);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────

    private void updateModeIndicators() {
        ocrButton.setVisibility(settings.ocrEnabled ? View.VISIBLE : View.GONE);
        navButton.setVisibility(settings.navigationEnabled ? View.VISIBLE : View.GONE);
        nfcButton.setVisibility(settings.nfcEnabled ? View.VISIBLE : View.GONE);
    }

    private String settingsSummaryText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Speech ").append(settings.speechDelayMs / 1000f).append("s")
          .append(" · Repeat ").append(settings.repeatCooldownMs / 1000).append("s")
          .append(" · Motion ").append(settings.motionLabel())
          .append(" · ").append(settings.notifyDistanceLabel());
        if (settings.onlineModeEnabled)  sb.append(" · Online");
        if (settings.navigationEnabled)  sb.append(" · Nav");
        if (settings.ocrEnabled)         sb.append(" · OCR");
        if (settings.nfcEnabled)         sb.append(" · NFC");
        return sb.toString();
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15f);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(card(0xDD1A2A3B, dp(18), 0x6637D4A7));
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private GradientDrawable card(int color, int radius, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
