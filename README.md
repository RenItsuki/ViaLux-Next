# Via Lux (ViaLux-Next)

**Via Lux** is an Android mobile vision and voice-guided scene awareness assistant designed for visual accessibility. Developed after completing the Clever Harvey AI program, it combines multiple on-device AI models, computer vision heuristics, spoken guidance, proactive OCR scene reading, turn-by-turn walking navigation, and NFC medicine identification.

> ⚠️ **Important Disclaimer:** Via Lux is an educational and exploratory prototype. It is **not** a certified medical device, safety aid, or mobility replacement, and does **not** guarantee obstacle or collision avoidance.

---

## 🌟 Key Features

### 1. Hands-Free Voice Control & App Opening Spoken Options
Operate the app entirely without looking at or touching the screen:
- **Spoken Onboarding on Launch:** As soon as you open the app, Via Lux speaks:
  > *"Welcome to Via Lux. You can say: 'Start camera' for obstacle detection, 'Take me to' followed by a destination to navigate, 'Read text' to read signs, or 'Check NFC' for medicine. Say 'Start' anytime to begin."*
- **"Take me to [destination]" / "Navigate to [place]"** (e.g. *"Take me to Shaheed Sukhdev College"*, *"Walk to Central Metro"*) — Resolves destinations using Android's native Geocoder, calculates walking directions/compass bearing, and speaks step-by-step guidance.
- **"Open Google Maps" / "Show map"** — Seamlessly launches Google Maps in turn-by-turn walking mode.
- **"Start" / "Start camera" / "Open camera"** — Starts live detection and spatial tracking.
- **"Stop" / "Stop camera" / "Pause"** — Stops the camera and freezes guidance.
- **"Read" / "Read text" / "Yes" / "Read it"** — Reads visible text aloud.
- **"Check NFC" / "Scan medicine"** — Activates NFC scanning and guides you to hold the tag to the back of the phone.
- **"Repeat"** — Repeats the last spoken announcement.
- **"Help" / "Options"** — Speaks all available voice commands.
- **Tap Anywhere to Speak:** Tapping the main guidance card or waving your hand over the proximity sensor immediately opens the microphone for your command.

### 2. Proactive OCR Scene Reading ("Asks when it detects text")
- **Automatic Text Presence Detection:** When you point the camera towards signs, documents, notices, or medicine packaging, Via Lux senses the text blocks and asks:
  > *"Text detected: [words]. Would you like me to read it? Say read or yes."*
- **Hands-Free Confirmation:** Simply say *"read"*, *"yes"*, or tap the screen to hear the text read out loud.
- **Spoken Completion:** Once all text has been read aloud, Via Lux cleanly concludes:
  > *"... Finished reading text."*

### 3. Multi-Tier Walking Navigation (Resolved for Landmarks & College Campuses)
- **Native Android Geocoder:** Resolves destinations (such as *"Shaheed Sukhdev College"*, hospitals, transit stations) with automatic phonetic typo tolerance without hitting rate-limiting servers.
- **Spoken Walking Guidance:** Announces remaining distance and cardinal compass heading (e.g. *"Walk North towards Shaheed Sukhdev College for 450 metres"*).
- **Direct Google Maps Walking Launcher:** Tap the prominent *"Open in Google Maps"* button or say *"Open Google Maps"* to launch the official Google Maps app in walking turn-by-turn navigation mode.

### 4. Dual On-Device AI Models (Running Concurrently)
- **General Object Detection (YOLO26n ONNX)**: Runs locally on CPU via ONNX Runtime Android to detect people, vehicles, bikes, chairs, and obstacles.
- **Dedicated Stair Detection (StairVision YOLOv8n TFLite)**: Integrated directly from the StairVision project (trained on 400+ RGB-D stair images with 97.2% mAP@50). Classifies ascending stairs, descending stairs, and side-view stairs.
- **Multi-Threaded Parallel Execution**: Both models run concurrently across background worker threads on every camera frame, minimizing latency.
- **Intersection-over-Union (IoU) Deduplication**: Merges predictions and prunes redundant boxes while preserving objects located near stairs.
- **Calmed Detection Pacing:** Paced at 750ms with spatial hysteresis to eliminate rapid-fire chatter and battery drain.

### 5. NFC Medicine Identification ("Check NFC")
- Say *"Check NFC"* or tap the *"Check NFC"* button:
  > *"NFC is active. Hold any medicine package, NFC card, or sticker against the upper back of your phone."*
- When an NFC tag is detected, the phone vibrates and speaks the medicine details:
  - Registered tag: Speaks the medicine name and strength.
  - Unregistered tag: Prompts you to register the medicine details in the app.
- Stored securely on-device with `EncryptedSharedPreferences`.

### 6. Modern Accessible UI
- High-contrast AMOLED dark palette (`#080C12`) with bold text and WCAG AAA compliance.
- Giant guidance hero card that acts as a primary touch target for voice control.
- Live microphone status indicator with animated listening/speaking pills.
- Dynamic visual overlay:
  - 🟢 **Teal** bounding boxes for still objects
  - 🟠 **Orange** bounding boxes for moving objects
  - 🔴 **Coral Red with ⚠️ icon** for stairs and immediate hazards

---

## 🏗️ Architecture & Pipeline

```
           Live Camera Feed (CameraX RGBA_8888)
                             │
            ┌────────────────┴────────────────┐
            ▼                                 ▼
   YOLO26n (ONNX Runtime)            StairVision (TFLite)
  General Objects & People          Ascending/Descending Stairs
            │                                 │
            └────────────────┬────────────────┘
                             ▼
         DetectionMerger.java (IoU Deduplication)
                             │
            ┌────────────────┼────────────────┐
            ▼                ▼                ▼
     GuidanceEngine   OcrProcessor    NavigationGuide
   (Spatial 3x3 Grid)  (Scene Text)   (Native Geocoder)
            │                │                │
            └────────────────┴────────────────┘
                             ▼
                     SpeechGuide.java
            (Anti-Feedback + Utterance Locks)
```

---

## 🛠️ Building & Installing

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- Android Device running Android 8.0+ (API 26+)
- Rear camera and Microphone permissions

### Build via Gradle
```bash
# Clone the repository
git clone https://github.com/RenItsuki/ViaLux-Next.git
cd ViaLux-Next

# Build debug APK
./gradlew assembleDebug

# Output APK location:
# app/build/outputs/apk/debug/app-debug.apk
```