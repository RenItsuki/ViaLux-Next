# Via Lux (ViaLux-Next)

**Via Lux** is an Android mobile vision and voice-guided scene awareness assistant designed for visual accessibility. Developed after completing the Clever Harvey AI program, it combines on-device YOLO26n object detection, dual-camera geometric depth estimation, a conversational natural-language visual scene assistant, proactive OCR text reading, turn-by-turn walking navigation, and NFC medicine identification.

> ⚠️ **Important Disclaimer:** Via Lux is an educational and exploratory prototype. It is **not** a certified medical device, safety aid, or mobility replacement, and does **not** guarantee obstacle or collision avoidance.

---

## 🌟 Key Features

### 1. Conversational Visual Scene Voice Assistant
Ask questions in natural English and hear direct, immediate answers based on what your camera sees:
- **"Is there any car in front of me?"**
  > *"Yes, there is a car directly in front of you, approximately 2.8 metres away."* or *"No, there are no cars in front of you. The path directly ahead is clear."*
- **"Is there anyone near me?"**
  > *"Yes, there is a person on your right, about 2.1 metres away."*
- **"What is in front of me?" / "What do you see?"**
  > *"In front of you, I see a chair at 1.4 metres and a person at 3.2 metres."*
- **"Is the path clear?" / "Can I walk forward?"**
  > *"Yes, your path directly ahead is clear for at least 3 metres."* or *"Caution: There is a chair directly ahead, 1.2 metres away. Step slightly right to walk around it."*
- **"How far is the car?"**
  > *"The car is approximately 3.1 metres away on your left."*
- **"Is anything moving?"**
  > *"Yes, a moving car is detected 4.2 metres ahead."* or *"Nothing is currently moving in front of you."*
- **"What does the text say?"**
  > Reads visible signs, packaging, or documents aloud.

### 2. Dual-Camera Metric Depth Estimation
Replaces arbitrary 2D bounding-box heuristics with real physical optics:
- **Camera2 Multi-Camera API:** Automatically detects multi-camera rear setups (`LOGICAL_MULTI_CAMERA`, physical wide + ultra-wide lenses).
- **Physical Baseline Calibration:** Extracts physical sensor dimensions and translation vectors (`LENS_POSE_TRANSLATION`) to establish stereo baseline $B$ (typically 18–24mm).
- **Metric Distance Guidance:** Uses pinhole optical geometry ($D = \frac{f \cdot H_{real}}{h_{px}}$) to announce true distance in metres (e.g. *"car ahead, 2.5 metres"*).
- Displays exact metric distances directly on visual bounding boxes.

### 3. Clean, Simple, Human-First UI
- Free of distracting technical clutter and AI buzzwords.
- 4 large, high-contrast, easy-to-tap accessible action cards:
  - `[▶ Start Camera]` (toggles to `[⏹ Stop Camera]`)
  - `[📖 Read Text]`
  - `[🧭 Navigate]`
  - `[🏷️ Medicine (NFC)]`
- Large primary guidance text card that responds immediately when tapped.
- Clear status bar: *"Ready · Dual Camera Active (20mm baseline)"*.

### 4. Proactive OCR Scene Reading ("Asks when it detects text")
- **Automatic Text Presence Detection:** When pointed at signs, labels, or medicine packaging, Via Lux senses text blocks and asks:
  > *"Text detected: [words]. Would you like me to read it? Say read or yes."*
- **Zero-Touch Confirmation:** Say *"read"*, *"yes"*, or tap the screen to hear the text read out loud.
- **Spoken Completion:** Concludes with: *"... Finished reading text."*

### 5. Multi-Tier Walking Navigation (With Direct Google Maps Launcher)
- **Native Android Geocoder:** Resolves landmarks (such as *"Shaheed Sukhdev College"*, stations, hospitals) without hitting external rate limits.
- **Compass & Pedestrian Guidance:** Speaks remaining distance and cardinal heading (*"Walk North towards Shaheed Sukhdev College for 450 metres"*).
- **Direct Google Maps Launcher:** Tap *"Open in Google Maps"* or say *"Open Google Maps"* to launch turn-by-turn walking directly inside Google Maps!

### 6. NFC Medicine Identification
- Say *"Check NFC"* or tap the *"Medicine (NFC)"* button:
  > *"NFC active. Hold any medicine box or tag against the upper back of your phone."*
- Tap any registered tag to hear its name and strength (e.g. *"Paracetamol, 500 mg"*).
- Encrypted locally via Jetpack `EncryptedSharedPreferences`.

---

## 🏗️ Architecture & Pipeline

```
           Live Camera Feed (CameraX RGBA_8888)
                             │
                             ▼
                  YOLO26n (ONNX Runtime)
             Fast, Low-Power Object Detection
                             │
            ┌────────────────┼────────────────┐
            ▼                ▼                ▼
   DualCameraDepth    OcrProcessor    NavigationGuide
   (Optical Optics)   (Scene Text)   (Native Geocoder)
            │                │                │
            └────────────────┬────────────────┘
                             ▼
                    SceneVoiceAssistant
         (Conversational Natural Language Q&A)
                             │
                             ▼
                      SpeechGuide.java
            (Anti-Feedback + Utterance Locks)
```

---

## 🛠️ Building & Installing

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- Android Device running Android 8.0+ (API 26+)
- Rear camera (Dual rear cameras supported for enhanced depth)

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