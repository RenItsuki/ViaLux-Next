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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MainActivity — Via Lux Assistive Vision Application.
 *
 * Streamlined Architecture:
 *   1. Dual-Camera Metric Depth: Estimates real distance using Camera2 stereo optics and pinhole geometry.
 *   2. On-Device YOLO26n Object Detection: Fast, reliable, low-power detection.
 *   3. Conversational Scene Assistant: Understands natural language questions like
 *      "Is there any car in front of me?", "What is in front of me?", "Is the path clear?".
 *   4. Clean, Simple, Non-Intrusive UI: Accessible high-contrast cards without technical clutter.
 */
public final class MainActivity extends AppCompatActivity implements VoiceNavigationManager.CommandListener {

    // ── Permission request codes ───────────────────────────────────────────
    private static final int REQ_CAMERA   = 42;
    private static final int REQ_LOCATION = 43;
    private static final int REQ_AUDIO    = 44;

    // ── Timing ────────────────────────────────────────────────────────────
    private static final long ANALYSIS_INTERVAL_MS = 750;
    private static final long ONLINE_INTERVAL_MS   = 2000;

    // ── UI Components ─────────────────────────────────────────────────────
    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView guidanceText;
    private TextView statusPill;
    private TextView voiceStatusText;
    private Button startStopButton;
    private Button ocrButton;
    private Button navButton;
    private Button openMapsButton;
    private Button nfcButton;
    private Button settingsButton;
    private TextView loadingText;

    // ── Engine Components ─────────────────────────────────────────────────
    private SpeechGuide speechGuide;
    private GuidanceEngine guidanceEngine;
    private AppSettings settings;
    private YoloOnnxDetector detector;
    private DualCameraDepthEstimator depthEstimator;
    private OcrProcessor ocrProcessor;
    private RoboflowClient roboflowClient;
    private NavigationGuide navigationGuide;
    private NfcMedicineReader nfcReader;
    private MedicineStore medicineStore;
    private VoiceNavigationManager voiceNav;
    private Vibrator vibrator;

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
    private boolean hasSpokenWelcome = false;

    // Real-time scene memory for natural language assistant queries
    private volatile List<Detection> currentDetections = Collections.emptyList();
    private volatile int currentImageWidth = 640;
    private volatile int currentImageHeight = 480;

    // Text presence asking state
    private String pendingDetectedText = null;
    private long lastTextAskAt = 0;
    private boolean textConfirmationActive = false;

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
        onlineExecutor  = Executors.newSingleThreadExecutor();
        speechGuide     = new SpeechGuide(this);
        guidanceEngine  = new GuidanceEngine();
        settings        = new AppSettings(this);
        depthEstimator  = new DualCameraDepthEstimator(this);
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

        speechGuide.setOnInitListener(this::speakWelcomeOptions);

        buildUi();
        loadDetector();
        handleNfcIntent(getIntent());

        // Check audio & camera permissions
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
        if (onlineExecutor  != null) onlineExecutor.shutdownNow();
        if (detector        != null) { try { detector.close(); } catch (Exception ignored) {} }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceNav.setContinuousEnabled(true);
            voiceNav.startListening();
        }
        if (code == REQ_CAMERA && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            startDetection();
        } else if (code == REQ_LOCATION && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            showDestinationDialog();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Welcome Voice Options on App Open
    // ─────────────────────────────────────────────────────────────────────

    private void speakWelcomeOptions() {
        if (hasSpokenWelcome) return;
        hasSpokenWelcome = true;
        mainHandler.postDelayed(() -> {
            String welcome = "Welcome to Via Lux. Say 'Start camera' to detect obstacles, 'Take me to' with any place name to navigate, or ask: 'Is there any car in front of me?'. Say 'Start' anytime to begin.";
            speechGuide.forceSpeak(welcome, true);
        }, 600);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Clean, Simple, Accessible UI Construction
    // ─────────────────────────────────────────────────────────────────────

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(10, 14, 20));

        // Camera preview & overlay
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setAlpha(0f);
        overlayView = new OverlayView(this);

        // Simple, clean control panel
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(16));
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackground(card(0xF40E1624, dp(22), 0x22FFFFFF));

        // Header: Title + Status Pill
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(6));

        TextView title = new TextView(this);
        title.setText("Via Lux");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);

        statusPill = new TextView(this);
        statusPill.setText("● Ready");
        statusPill.setTextColor(0xFF00E699);
        statusPill.setTextSize(13f);
        statusPill.setTypeface(Typeface.DEFAULT_BOLD);
        statusPill.setPadding(dp(12), dp(4), dp(12), dp(4));
        statusPill.setBackground(card(0x2800E699, dp(14), 0x6600E699));

        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.setMargins(dp(12), 0, 0, 0);

        header.addView(title);
        header.addView(statusPill, pillParams);

        // Primary Spoken Guidance Card (Large & High Contrast)
        guidanceText = new TextView(this);
        guidanceText.setText("Tap Start to begin.\nAsk anytime: 'Is there any car in front of me?'");
        guidanceText.setTextColor(Color.WHITE);
        guidanceText.setTextSize(19f);
        guidanceText.setTypeface(Typeface.DEFAULT_BOLD);
        guidanceText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        guidanceText.setPadding(dp(16), dp(14), dp(16), dp(14));
        guidanceText.setBackground(card(0xDD121D2C, dp(16), 0x6600E699));
        guidanceText.setOnClickListener(v -> onGuidanceCardTapped());

        // Simple Voice Feedback & Assistant Prompt
        voiceStatusText = new TextView(this);
        voiceStatusText.setText("🎙️ Ask: 'Is there any car in front of me?' or 'What is in front of me?'");
        voiceStatusText.setTextColor(0xFF00E699);
        voiceStatusText.setTextSize(13f);
        voiceStatusText.setGravity(Gravity.CENTER);
        voiceStatusText.setPadding(0, dp(6), 0, dp(6));

        // Open in Google Maps button (appears only during walking route)
        openMapsButton = button("🗺️ Open in Google Maps", 0xFF142B44, 0xFF4285F4);
        openMapsButton.setVisibility(View.GONE);
        openMapsButton.setOnClickListener(v -> onOpenMapsCommand());

        // 4 Main Accessible Buttons (2x2 Simple Grid)
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);

        startStopButton = button("▶ Start Camera", 0xFF143422, 0xFF00E699);
        startStopButton.setOnClickListener(v -> toggleCameraDetection());

        ocrButton = button("📖 Read Text", 0xFF10283C, 0xFF00D2FF);
        ocrButton.setOnClickListener(v -> triggerOcrRead());

        row1.addView(startStopButton, buttonParams());
        row1.addView(ocrButton, buttonParams());

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);

        navButton = button("🧭 Navigate", 0xFF182240, 0xFF5B8DEF);
        navButton.setOnClickListener(v -> {
            if (navigationGuide.isActive()) {
                navigationGuide.stop();
                navButton.setText("🧭 Navigate");
                openMapsButton.setVisibility(View.GONE);
                statusPill.setText("● Active");
                speechGuide.forceSpeak("Navigation stopped.", true);
            } else {
                showDestinationDialog();
            }
        });

        nfcButton = button("🏷️ Medicine (NFC)", 0xFF241834, 0xFFC084FC);
        nfcButton.setOnClickListener(v -> onCheckNfcCommand());

        row2.addView(navButton, buttonParams());
        row2.addView(nfcButton, buttonParams());

        // Settings / Options footer
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setGravity(Gravity.CENTER);

        Button helpBtn = button("💡 Voice Help", 0xFF1C222C, 0xFF8A9BA8);
        helpBtn.setOnClickListener(v -> onHelpCommand());

        settingsButton = button("⚙️ Settings", 0xFF1C222C, 0xFF8A9BA8);
        settingsButton.setOnClickListener(v -> SettingsDialog.show(this, settings, () -> {
            settings.load();
            guidanceEngine.reset();
            ocrProcessor.setIntervalMs(settings.ocrIntervalMs);
            Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show();
        }));

        row3.addView(helpBtn, buttonParams());
        row3.addView(settingsButton, buttonParams());

        loadingText = new TextView(this);
        loadingText.setText("Opening camera feed...");
        loadingText.setTextColor(0xFF00E699);
        loadingText.setTextSize(15f);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setVisibility(View.GONE);

        // Layout Assembly
        LinearLayout.LayoutParams fullWidth = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fullWidth.setMargins(0, dp(3), 0, dp(3));

        panel.addView(header, fullWidth);
        panel.addView(guidanceText, fullWidth);
        panel.addView(voiceStatusText, fullWidth);
        panel.addView(openMapsButton, fullWidth);
        panel.addView(row1, fullWidth);
        panel.addView(row2, fullWidth);
        panel.addView(row3, fullWidth);
        panel.addView(loadingText, fullWidth);

        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams guideParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(panel, guideParams);

        setContentView(root);
    }

    private void onGuidanceCardTapped() {
        vibrate();
        if (textConfirmationActive && pendingDetectedText != null) {
            onTextConfirmedCommand();
        } else {
            triggerVoiceListening();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Conversational Scene Voice Assistant & Commands
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
    public void onSceneAssistantQuery(String question) {
        runOnUiThread(() -> {
            vibrate();
            // Query the current visual scene memory
            String answer = SceneVoiceAssistant.answerQuery(
                    question,
                    currentDetections,
                    depthEstimator,
                    guidanceEngine,
                    currentImageWidth,
                    currentImageHeight,
                    ocrProcessor.repeat()
            );
            guidanceText.setText(answer);
            speechGuide.forceSpeak(answer, true);
            voiceStatusText.setText("💬 " + question);
        });
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
    public void onTextConfirmedCommand() {
        runOnUiThread(() -> {
            vibrate();
            if (pendingDetectedText != null && !pendingDetectedText.isEmpty()) {
                String textToSpeak = pendingDetectedText + ". Finished reading text.";
                guidanceText.setText(textToSpeak);
                speechGuide.forceSpeak(textToSpeak, true);
                pendingDetectedText = null;
                textConfirmationActive = false;
            } else {
                triggerOcrRead();
            }
        });
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
    public void onOpenMapsCommand() {
        runOnUiThread(() -> {
            vibrate();
            String dest = navigationGuide.getDestinationName();
            if (dest.isEmpty()) dest = "Destination";
            NavigationGuide.openInGoogleMaps(this, dest, navigationGuide.getDestLat(), navigationGuide.getDestLng());
        });
    }

    @Override
    public void onStopNavCommand() {
        runOnUiThread(() -> {
            vibrate();
            if (navigationGuide.isActive()) {
                navigationGuide.stop();
                navButton.setText("🧭 Navigate");
                openMapsButton.setVisibility(View.GONE);
                statusPill.setText("● Active");
                speechGuide.forceSpeak("Navigation stopped.", true);
            }
        });
    }

    @Override
    public void onCheckNfcCommand() {
        runOnUiThread(() -> {
            vibrate();
            if (!settings.nfcEnabled || !nfcReader.isAvailable()) {
                speechGuide.forceSpeak("NFC is turned off or not available on this phone.", true);
                voiceStatusText.setText("⚠️ NFC is disabled or not supported.");
                return;
            }
            String prompt = "NFC active. Hold any medicine box or tag against the upper back of your phone.";
            speechGuide.forceSpeak(prompt, true);
            guidanceText.setText(prompt);
            voiceStatusText.setText("🏷️ Waiting for NFC tag tap on phone back...");
        });
    }

    @Override
    public void onSettingsCommand() {
        runOnUiThread(() -> SettingsDialog.show(this, settings, () -> {
            settings.load();
            guidanceEngine.reset();
        }));
    }

    @Override
    public void onHelpCommand() {
        runOnUiThread(() -> {
            vibrate();
            String help = "You can ask: 'Is there any car in front of me?', 'What is in front of me?', 'Is the path clear?', 'Take me to' with any place name, 'Read text', or 'Check NFC'.";
            voiceStatusText.setText("💡 " + help);
            guidanceText.setText(help);
            speechGuide.forceSpeak(help, true);
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
                statusPill.setText("🎙️ Listening...");
                statusPill.setTextColor(0xFFFF5555);
            } else {
                statusPill.setText(running ? "● Active" : "● Ready");
                statusPill.setTextColor(0xFF00E699);
            }
        });
    }

    @Override
    public void onPartialSpeechHeard(String partial) {
        runOnUiThread(() -> voiceStatusText.setText("🎙️ Hearing: \"" + partial + "\""));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Text Reading (OCR) Trigger
    // ─────────────────────────────────────────────────────────────────────

    private void triggerOcrRead() {
        vibrate();
        ocrButton.setText("⏳ Reading...");
        guidanceText.setText("Reading text in view. Hold phone steady...");
        speechGuide.forceSpeak("Reading text", true);

        if (!running) {
            startDetection();
        }
        ocrProcessor.requestRead();
    }

    // ─────────────────────────────────────────────────────────────────────
    // AI Detector Loading (YOLO26n ONNX)
    // ─────────────────────────────────────────────────────────────────────

    private void loadDetector() {
        cameraExecutor.execute(() -> {
            try {
                detector = new YoloOnnxDetector(this);
                runOnUiThread(() -> {
                    String hw = depthEstimator.getCameraHardwareStatus();
                    statusPill.setText("● Ready");
                    voiceStatusText.setText("Ready · " + hw);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusPill.setText("● Error");
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
        startStopButton.setBackground(card(0xFF341414, dp(18), 0xFFFF5555));
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
                startStopButton.setBackground(card(0xFF143422, dp(18), 0xFF00E699));
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
        startStopButton.setBackground(card(0xFF143422, dp(18), 0xFF00E699));
        statusPill.setText("● Stopped");
        guidanceText.setText("Detection stopped. Tap Start or say 'Start camera'.");
        previewView.animate().alpha(0.25f).setDuration(250).start();
        overlayView.update(Collections.emptyList(), guidanceEngine, depthEstimator, 1, 1);
        if (cameraProvider != null) cameraProvider.unbindAll();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Single Fast Model Inference (YOLO26n + Dual-Camera Depth)
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

            // Run YOLO26n on cameraExecutor directly
            List<Detection> yoloDetections = localDetector.detect(bitmap);

            // Online inference (if enabled in settings)
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

            List<Detection> merged = DetectionMerger.merge(yoloDetections, latestOnlineDetections);

            // Store current visual scene state for Natural Language Voice Assistant Q&A
            currentDetections = merged;
            currentImageWidth = width;
            currentImageHeight = height;

            // ── Passive Scene Text Detection ("Ask when it detects text") ──
            if (settings.ocrEnabled) {
                ocrProcessor.checkTextPresence(bitmap, now, (sample, fullText) -> runOnUiThread(() -> {
                    long t = SystemClock.elapsedRealtime();
                    if (!textConfirmationActive && t - lastTextAskAt > 15000) {
                        lastTextAskAt = t;
                        pendingDetectedText = fullText;
                        textConfirmationActive = true;
                        vibrate();
                        guidanceText.setText("📖 Text Detected: \"" + sample + "\"\nSay 'Read' or 'Yes' to hear it, or tap here.");
                        speechGuide.forceSpeak("Text detected: " + sample + ". Would you like me to read it? Say read or yes.", true);

                        // 8-second confirmation window
                        mainHandler.postDelayed(() -> {
                            if (textConfirmationActive) {
                                textConfirmationActive = false;
                                pendingDetectedText = null;
                            }
                        }, 8000);
                    }
                }));

                // ── Active OCR Read (when commanded) ───────────────────────
                ocrProcessor.process(bitmap, now, recognizedText -> runOnUiThread(() -> {
                    guidanceText.setText(recognizedText);
                    speechGuide.forceSpeak(recognizedText, true);
                    ocrButton.setText("📖 Read Text");
                }));
            }

            // ── Guidance Evaluation (Incorporating Dual Camera Depth) ───────
            Guidance guidance = guidanceEngine.evaluate(merged, width, height, now, settings, depthEstimator);
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
                guidanceEngine, depthEstimator, width, height);

        // Only override guidance text if not in active text confirmation
        if (guidance != null && !textConfirmationActive) {
            guidanceText.setText(guidance.message);
            speechGuide.speak(guidance.message, guidance.emergency, guidance.priority);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Walking Navigation
    // ─────────────────────────────────────────────────────────────────────

    private void showDestinationDialog() {
        EditText input = new EditText(this);
        input.setHint("Enter destination (e.g. Shaheed Sukhdev College, Pharmacy)");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF607080);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AlertDialog.Builder(this)
                .setTitle("Walking Navigation")
                .setMessage("Enter destination, or say 'Take me to [place]'.")
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
        openMapsButton.setVisibility(View.VISIBLE);
        statusPill.setText("● Routing...");
        guidanceText.setText("Calculating walking route to " + destination + "...");
        speechGuide.forceSpeak("Finding route to " + destination, true);

        navigationGuide.start(destination, settings.mapsApiKey, (instruction, isError) -> {
            guidanceText.setText(instruction);
            speechGuide.speak(instruction, false, Guidance.PRIORITY_INFO);
            if (isError) {
                navButton.setText("🧭 Navigate");
                openMapsButton.setVisibility(View.GONE);
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
            vibrate();
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

    private Button button(String text, int bgColor, int strokeColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14f);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(card(bgColor, dp(16), strokeColor));
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1f);
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
