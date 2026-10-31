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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends AppCompatActivity implements VoiceNavigationManager.CommandListener {

    // ── Permission request codes ───────────────────────────────────────────
    private static final int REQ_CAMERA   = 42;
    private static final int REQ_LOCATION = 43;
    private static final int REQ_AUDIO    = 44;

    // ── Timing ────────────────────────────────────────────────────────────
    private static final long ANALYSIS_INTERVAL_MS = 350;
    private static final long ONLINE_INTERVAL_MS   = 2000;

    // ── UI Components ─────────────────────────────────────────────────────
    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView guidanceText;
    private TextView statusPill;
    private TextView voiceStatusText;
    private TextView settingsSummary;
    private Button voiceMicButton;
    private Button startStopButton;
    private Button ocrButton;
    private Button navButton;
    private Button repeatButton;
    private Button settingsButton;
    private Button nfcButton;
    private TextView loadingText;

    // ── Engine Components ─────────────────────────────────────────────────
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
    private VoiceNavigationManager voiceNav;
    private Vibrator vibrator;

    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private ExecutorService stairExecutor;
    private ExecutorService onlineExecutor;
    private ConnectivityManager connectivityManager;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean analyzing = new AtomicBoolean(false);
    private final AtomicBoolean onlinePending = new AtomicBoolean(false);
    private long lastAnalysisTime = 0;
    private long lastOnlineTime   = 0;
    private boolean running = false;

    private volatile List<Detection> latestOnlineDetections = Collections.emptyList();

    // ── Pulsing camera loader animation ────────────────────────────────────
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
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor  = Executors.newSingleThreadExecutor();
        stairExecutor   = Executors.newSingleThreadExecutor();
        onlineExecutor  = Executors.newSingleThreadExecutor();
        speechGuide     = new SpeechGuide(this);
        guidanceEngine  = new GuidanceEngine();
        settings        = new AppSettings(this);
        ocrProcessor    = new OcrProcessor();
        roboflowClient  = new RoboflowClient();
        navigationGuide = new NavigationGuide(this);
        medicineStore   = new MedicineStore(this);
        nfcReader       = new NfcMedicineReader(this);
        voiceNav        = new VoiceNavigationManager(this, this);
        vibrator        = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        speechGuide.setTtsStateListener(new SpeechGuide.TtsStateListener() {
            @Override
            public void onTtsStarted() {
                voiceNav.setTtsSpeaking(true);
            }

            @Override
            public void onTtsFinished() {
                voiceNav.setTtsSpeaking(false);
            }
        });

        buildUi();
        loadDetectors();
        handleNfcIntent(getIntent());

        // Check audio & camera permissions for zero-contact experience
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQ_AUDIO);
        } else {
            voiceNav.setContinuousEnabled(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (settings.nfcEnabled) nfcReader.enable(this);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceNav.setContinuousEnabled(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        nfcReader.disable(this);
        voiceNav.setContinuousEnabled(false);
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
        if (speechGuide     != null) speechGuide.shutdown();
        if (ocrProcessor    != null) ocrProcessor.shutdown();
        if (navigationGuide != null) navigationGuide.stop();
        if (voiceNav        != null) voiceNav.shutdown();
        if (cameraExecutor  != null) cameraExecutor.shutdownNow();
        if (stairExecutor   != null) stairExecutor.shutdownNow();
        if (onlineExecutor  != null) onlineExecutor.shutdownNow();
        if (detector        != null) { try { detector.close(); } catch (Exception ignored) {} }
        if (stairDetector   != null) { try { stairDetector.close(); } catch (Exception ignored) {} }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceNav.setContinuousEnabled(true);
        }
        if (code == REQ_CAMERA && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            startDetection();
        } else if (code == REQ_LOCATION && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            showDestinationDialog();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Modern High-Contrast Accessible UI Construction
    // ─────────────────────────────────────────────────────────────────────

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(9, 13, 20));

        // Camera preview & overlay
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setAlpha(0f);
        overlayView = new OverlayView(this);

        // Main chrome container (bottom sheet)
        LinearLayout chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setPadding(dp(16), dp(16), dp(16), dp(20));
        chrome.setGravity(Gravity.CENTER_HORIZONTAL);
        chrome.setBackground(card(0xF00E1724, dp(28), 0x3337D4A7));

        // Header: App title + Status Badge
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));

        TextView title = new TextView(this);
        title.setText("Via Lux");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26f);
        title.setTypeface(Typeface.DEFAULT_BOLD);

        statusPill = new TextView(this);
        statusPill.setText("● Ready");
        statusPill.setTextColor(0xFF37D4A7);
        statusPill.setTextSize(13f);
        statusPill.setTypeface(Typeface.DEFAULT_BOLD);
        statusPill.setPadding(dp(12), dp(4), dp(12), dp(4));
        statusPill.setBackground(card(0x3337D4A7, dp(14), 0x6637D4A7));

        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.setMargins(dp(12), 0, 0, 0);

        header.addView(title);
        header.addView(statusPill, pillParams);

        // Prominent Spoken Guidance Card
        guidanceText = new TextView(this);
        guidanceText.setText("Tap Start or say 'Start camera' to begin.");
        guidanceText.setTextColor(Color.WHITE);
        guidanceText.setTextSize(21f);
        guidanceText.setTypeface(Typeface.DEFAULT_BOLD);
        guidanceText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        guidanceText.setPadding(dp(18), dp(16), dp(18), dp(16));
        guidanceText.setBackground(card(0xCC132030, dp(18), 0x8837D4A7));

        // Voice Feedback Status Line
        voiceStatusText = new TextView(this);
        voiceStatusText.setText("🎙️ Hands-Free Voice Active: Speak anytime without touching phone");
        voiceStatusText.setTextColor(0xFF00E699);
        voiceStatusText.setTextSize(13f);
        voiceStatusText.setGravity(Gravity.CENTER);
        voiceStatusText.setPadding(0, dp(8), 0, dp(8));

        // Voice Mic Primary Action Button
        voiceMicButton = new Button(this);
        voiceMicButton.setText("🎙️  Hands-Free Voice Control Active");
        voiceMicButton.setTextColor(Color.BLACK);
        voiceMicButton.setTextSize(16f);
        voiceMicButton.setTypeface(Typeface.DEFAULT_BOLD);
        voiceMicButton.setAllCaps(false);
        voiceMicButton.setBackground(card(0xFF00E699, dp(22), 0xFFFFFFFF));
        voiceMicButton.setOnClickListener(v -> onHelpCommand());

        // Row 1: Start/Stop Camera + Read Text
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);

        startStopButton = button("▶ Start Camera", 0xFF1B3B2B, 0xFF00E699);
        startStopButton.setOnClickListener(v -> toggleCameraDetection());

        ocrButton = button("📖 Read Text", 0xFF143048, 0xFF00D2FF);
        ocrButton.setOnClickListener(v -> triggerOcrRead());

        row1.addView(startStopButton, buttonParams());
        row1.addView(ocrButton, buttonParams());

        // Row 2: Navigate + Repeat
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);

        navButton = button("🧭 Navigate", 0xFF1E284A, 0xFF5B8DEF);
        navButton.setOnClickListener(v -> {
            if (navigationGuide.isActive()) {
                navigationGuide.stop();
                navButton.setText("🧭 Navigate");
                statusPill.setText("● Camera Active");
                speechGuide.forceSpeak("Navigation stopped.", true);
            } else {
                showDestinationDialog();
            }
        });

        repeatButton = button("🔁 Repeat", 0xFF3D2E14, 0xFFFFB300);
        repeatButton.setOnClickListener(v -> onRepeatCommand());

        row2.addView(navButton, buttonParams());
        row2.addView(repeatButton, buttonParams());

        // Row 3: NFC Tags + Settings
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setGravity(Gravity.CENTER);

        nfcButton = button("🏷️ NFC Tags", 0xFF2A2038, 0xFFC084FC);
        nfcButton.setOnClickListener(v ->
                startActivity(new Intent(this, MedicineRegisterActivity.class)));

        settingsButton = button("⚙️ Settings", 0xFF202630, 0xFF9FB0C3);
        settingsButton.setOnClickListener(v -> SettingsDialog.show(this, settings, () -> {
            settings.load();
            guidanceEngine.reset();
            ocrProcessor.setIntervalMs(settings.ocrIntervalMs);
            settingsSummary.setText(settingsSummaryText());
            Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show();
        }));

        row3.addView(nfcButton, buttonParams());
        row3.addView(settingsButton, buttonParams());

        // Status Summary Footer
        settingsSummary = new TextView(this);
        settingsSummary.setText(settingsSummaryText());
        settingsSummary.setTextColor(0xFF8A9BA8);
        settingsSummary.setTextSize(12f);
        settingsSummary.setGravity(Gravity.CENTER);
        settingsSummary.setPadding(0, dp(8), 0, 0);

        loadingText = new TextView(this);
        loadingText.setText("Opening camera feed...");
        loadingText.setTextColor(0xFF00E699);
        loadingText.setTextSize(16f);
        loadingText.setTypeface(Typeface.DEFAULT_BOLD);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setVisibility(View.GONE);

        // Layout Assembly
        LinearLayout.LayoutParams fullWidth = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fullWidth.setMargins(0, dp(4), 0, dp(4));

        chrome.addView(header, fullWidth);
        chrome.addView(guidanceText, fullWidth);
        chrome.addView(voiceStatusText, fullWidth);
        chrome.addView(voiceMicButton, fullWidth);
        chrome.addView(row1, fullWidth);
        chrome.addView(row2, fullWidth);
        chrome.addView(row3, fullWidth);
        chrome.addView(settingsSummary, fullWidth);
        chrome.addView(loadingText, fullWidth);

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
    // Voice Navigation & Command Actions
    // ─────────────────────────────────────────────────────────────────────

    private void triggerVoiceListening() {
        vibrate();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        speechGuide.stopSpeaking();
        voiceNav.startListening();
    }

    @Override
    public void onStartCommand() {
        runOnUiThread(() -> {
            vibrate();
            if (!running) startDetection();
        });
    }

    @Override
    public void onStopCommand() {
        runOnUiThread(() -> {
            vibrate();
            if (running) stopDetection();
        });
    }

    @Override
    public void onReadTextCommand() {
        runOnUiThread(this::triggerOcrRead);
    }

    @Override
    public void onRepeatCommand() {
        runOnUiThread(() -> {
            vibrate();
            String ocrLast = ocrProcessor.repeat();
            if (ocrLast != null && !ocrLast.trim().isEmpty()) {
                speechGuide.forceSpeak(ocrLast, true);
                guidanceText.setText(ocrLast);
                return;
            }
            String last = speechGuide.getLastMessage();
            if (last != null && !last.trim().isEmpty()) {
                speechGuide.forceSpeak(last, true);
                guidanceText.setText(last);
            } else {
                speechGuide.forceSpeak("No recent announcements.", true);
            }
        });
    }

    @Override
    public void onNavigateCommand(String destination) {
        runOnUiThread(() -> {
            vibrate();
            requestLocationThenNavigate(destination);
        });
    }

    @Override
    public void onStopNavCommand() {
        runOnUiThread(() -> {
            vibrate();
            if (navigationGuide.isActive()) {
                navigationGuide.stop();
                navButton.setText("🧭 Navigate");
                statusPill.setText("● Camera Active");
                speechGuide.forceSpeak("Navigation stopped.", true);
            }
        });
    }

    @Override
    public void onSettingsCommand() {
        runOnUiThread(() -> SettingsDialog.show(this, settings, () -> {
            settings.load();
            guidanceEngine.reset();
            settingsSummary.setText(settingsSummaryText());
        }));
    }

    @Override
    public void onHelpCommand() {
        runOnUiThread(() -> {
            String helpText = "Voice commands: Start camera, Stop camera, Read text, Repeat, Navigate to a place, Stop navigation, or Settings.";
            voiceStatusText.setText("💡 " + helpText);
            speechGuide.forceSpeak(helpText, true);
        });
    }

    @Override
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> voiceStatusText.setText(status));
    }

    @Override
    public void onListeningStateChanged(boolean isListening) {
        runOnUiThread(() -> {
            if (isListening) {
                voiceMicButton.setText("🔴 Hands-Free Listening (Speak Command)");
                voiceMicButton.setBackground(card(0xFFFF4B4B, dp(22), Color.WHITE));
            } else {
                voiceMicButton.setText("🎙️  Hands-Free Voice Control Active");
                voiceMicButton.setBackground(card(0xFF00E699, dp(22), Color.WHITE));
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // Text Reading (OCR) Trigger
    // ─────────────────────────────────────────────────────────────────────

    private void triggerOcrRead() {
        vibrate();
        ocrButton.setText("⏳ Reading...");
        guidanceText.setText("Reading text in view. Hold steady...");
        speechGuide.forceSpeak("Reading text in view", true);

        // If camera is stopped, start it automatically so frames are available
        if (!running) {
            startDetection();
        }
        ocrProcessor.requestRead();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Detectors loading (Dual Parallel: YOLO26n + StairVision)
    // ─────────────────────────────────────────────────────────────────────

    private void loadDetectors() {
        cameraExecutor.execute(() -> {
            try {
                detector = new YoloOnnxDetector(this);
                try {
                    stairDetector = new StairVisionDetector(this);
                } catch (Exception e) {
                    android.util.Log.w("MainActivity", "StairVision optional load error: " + e.getMessage());
                }
                runOnUiThread(() -> statusPill.setText("● AI Ready"));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusPill.setText("● Detector Error");
                    guidanceText.setText(e.getMessage());
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // Camera Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    private void toggleCameraDetection() {
        if (running) {
            stopDetection();
        } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startDetection();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void startDetection() {
        running = true;
        guidanceEngine.reset();
        startStopButton.setText("⏹ Stop Camera");
        startStopButton.setBackground(card(0xFF3B1B1B, dp(18), 0xFFFF5555));
        statusPill.setText("● Opening...");
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

                statusPill.setText("● Active");
                loadingText.animate().cancel();
                loadingText.setVisibility(View.GONE);
                previewView.animate().alpha(1f).setDuration(450).start();
            } catch (Exception e) {
                running = false;
                startStopButton.setText("▶ Start Camera");
                startStopButton.setBackground(card(0xFF1B3B2B, dp(18), 0xFF00E699));
                statusPill.setText("● Error");
                guidanceText.setText("Camera error: " + e.getMessage());
            }
        }, cmd -> mainHandler.post(cmd));
    }

    private void stopDetection() {
        running = false;
        analyzing.set(false);
        loadingText.animate().cancel();
        loadingText.setVisibility(View.GONE);
        startStopButton.setText("▶ Start Camera");
        startStopButton.setBackground(card(0xFF1B3B2B, dp(18), 0xFF00E699));
        statusPill.setText("● Stopped");
        guidanceText.setText("Detection stopped. Tap Start or say 'Start camera'.");
        previewView.animate().alpha(0.25f).setDuration(250).start();
        overlayView.update(Collections.emptyList(), guidanceEngine, 1, 1);
        if (cameraProvider != null) cameraProvider.unbindAll();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Dual Parallel Frame Analysis & Merging
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

            // ── Parallel on-device dual inference ─────────────────────────
            // 1. StairVision on dedicated stairExecutor
            final StairVisionDetector localStairDetector = stairDetector;
            Future<List<Detection>> stairFuture = null;
            if (localStairDetector != null) {
                stairFuture = stairExecutor.submit(() -> {
                    try {
                        return localStairDetector.detect(bitmap);
                    } catch (Exception e) {
                        return Collections.emptyList();
                    }
                });
            }

            // 2. YOLO26n on cameraExecutor concurrently
            List<Detection> yoloDetections = Collections.emptyList();
            try {
                yoloDetections = localDetector.detect(bitmap);
            } catch (Exception ignored) {}

            List<Detection> stairDetections = Collections.emptyList();
            if (stairFuture != null) {
                try {
                    stairDetections = stairFuture.get();
                } catch (Exception ignored) {}
            }

            // Merge both on-device models
            List<Detection> local = DetectionMerger.merge(yoloDetections, stairDetections);

            // ── Online inference (throttled background) ───────────────────
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

            List<Detection> merged = DetectionMerger.merge(local, latestOnlineDetections);

            // ── OCR Text Reading ──────────────────────────────────────────
            if (settings.ocrEnabled) {
                ocrProcessor.process(bitmap, now, recognizedText -> runOnUiThread(() -> {
                    guidanceText.setText(recognizedText);
                    speechGuide.forceSpeak(recognizedText, true);
                    ocrButton.setText("📖 Read Text");
                }));
            }

            // ── Guidance Evaluation ───────────────────────────────────────
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
    // Walking Navigation
    // ─────────────────────────────────────────────────────────────────────

    private void showDestinationDialog() {
        EditText input = new EditText(this);
        input.setHint("Enter destination (e.g. Pharmacy, Station)");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF607080);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AlertDialog.Builder(this)
                .setTitle("Walking Navigation")
                .setMessage("Works with Google Maps API key or free OpenStreetMap routing.")
                .setView(input)
                .setPositiveButton("Navigate", (d, w) -> {
                    String dest = input.getText().toString().trim();
                    if (!dest.isEmpty()) requestLocationThenNavigate(dest);
                })
                .setNeutralButton("Speak Destination", (d, w) -> triggerVoiceListening())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestLocationThenNavigate(String destination) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            return;
        }
        startWalkingRoute(destination);
    }

    private void startWalkingRoute(String destination) {
        navButton.setText("⏹ Stop Nav");
        statusPill.setText("● Routing...");
        guidanceText.setText("Calculating walking route to " + destination + "...");
        speechGuide.forceSpeak("Finding walking route to " + destination, true);

        navigationGuide.start(destination, settings.mapsApiKey, (instruction, isError) -> {
            guidanceText.setText(instruction);
            speechGuide.speak(instruction, false, Guidance.PRIORITY_INFO);
            if (isError) {
                navButton.setText("🧭 Navigate");
                statusPill.setText("● Active");
            } else {
                statusPill.setText("● Navigating");
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // NFC Medicine Tags
    // ─────────────────────────────────────────────────────────────────────

    private void handleNfcIntent(Intent intent) {
        if (!settings.nfcEnabled) return;
        String message = nfcReader.handleIntent(intent, medicineStore);
        if (message != null) {
            guidanceText.setText(message);
            speechGuide.forceSpeak(message, true);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UI Helpers
    // ─────────────────────────────────────────────────────────────────────

    private void vibrate() {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(45);
            }
        }
    }

    private String settingsSummaryText() {
        return "Speech " + (settings.speechDelayMs / 1000f) + "s"
                + " · Repeat " + (settings.repeatCooldownMs / 1000) + "s"
                + " · " + settings.motionLabel()
                + " · " + settings.notifyDistanceLabel();
    }

    private Button button(String text, int bgColor, int strokeColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15f);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(card(bgColor, dp(18), strokeColor));
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1f);
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
