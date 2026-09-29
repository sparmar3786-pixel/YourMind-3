# YourMind Trade — Android

Performance-first Android build of **NSE Algo Signal**.

## Current app
- 18 main screens: Dashboard, Market, Commodity, Signals, OI Lab, Watchlist, Search, Charts, Option Chain, News, Market Details, Angel API, NSE, NSE MCP, Data, Instruments, Settings, More.
- Angel One SmartAPI native bridge with Client ID, MPIN, current TOTP, API key, profile/feed/live option-chain hooks.
- NSE reachability check and NSE MCP connector.
- CSV/XLS/XLSX/PDF/image file picker.
- 8/13 EMA and requested analysis timeframes are retained for the analytics layer.
- Real API data only; no fabricated market values and no automatic order placement.

## Stack
- Android Gradle Plugin 8.5.2
- Java 17
- AndroidX WebKit WebViewAssetLoader
- OkHttp 4.12
- Bundled offline-first HTML UI with native Android bridges

## Build
`gradle :app:assembleDebug`

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Security
Broker credentials are entered in the app and are not hard-coded into the project. Do not commit API keys, MPINs, TOTP secrets, JWTs or feed tokens.

## Status
The repository is the GitHub-native build target; Floot is not required for the Android build.
