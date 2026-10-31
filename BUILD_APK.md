# Build the APK

This machine does not currently expose command-line Android SDK or Gradle, so build the APK in Android Studio:

1. Open Android Studio.
2. Open this folder:

   `C:\Users\JOY KARMAKAR\Documents\AI project\VisionGuidePhone`

3. Wait for Gradle sync to finish.
4. Connect your phone with USB debugging enabled.
5. Press **Run** to test on the phone.
6. To create an installable APK, use:

   **Build** → **Build App Bundle(s) / APK(s)** → **Build APK(s)**

The APK will usually appear under:

`VisionGuidePhone\app\build\outputs\apk\debug\app-debug.apk`

## Gradle wrapper

This project uses Gradle wrapper `9.4.1`:

`gradle/wrapper/gradle-wrapper.properties`

## If Gradle asks for a JDK or says Java 8 is too old

Gradle 9.4.1 needs Java 17 or newer. Use Android Studio's bundled JDK, normally called **jbr** or **Embedded JDK** in Android Studio settings:

**File** → **Settings** → **Build, Execution, Deployment** → **Build Tools** → **Gradle** → **Gradle JDK**

Select Android Studio's embedded JDK, not `Java 1.8`.

For this machine, the project also pins Gradle to Android Studio's embedded JDK in:

`gradle.properties`
