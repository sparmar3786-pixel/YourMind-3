# YourMind Trade Android Build

## CI
GitHub Actions installs Android API 34, Java 17 and Gradle 8.7, then builds:

`gradle assembleDebug`

The generated APK is uploaded as **YourMind-Trade-debug**.

## Local
Open the project in Android Studio with JDK 17, sync Gradle, then run the debug build.

## Important
The analyzer HTML is bundled in the APK, so the core analyzer UI/engine remains available offline.
