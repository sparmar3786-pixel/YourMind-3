# YourMind Trade — Android

Performance-first Android build of the offline Option Chain Signal Analyzer.

## Stack
- Kotlin 1.9.24 + Android Gradle Plugin 8.5.2
- Java 17
- AndroidX WebKit WebViewAssetLoader
- Offline HTML analyzer bundled inside the APK
- Hardware acceleration enabled
- LocalStorage retained for snapshots/settings/journal

## Performance
The app serves the bundled HTML through `https://appassets.androidplatform.net/assets/` rather than unrestricted `file://` loading. WebView file/content access is disabled, zoom and overscroll are disabled, and the Activity uses hardware acceleration.

## Build
`gradle assembleDebug`

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Design
The launcher mark is a lightweight native vector inspired by the generated YourMind Trade visual: dark trading background, cyan/blue rising chart and clean finance styling.
