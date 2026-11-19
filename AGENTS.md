# Repository Guidelines

## Project Structure

TerraMoor is a single-module Android app for ColorOS 17. Under `app/src/main/java/shop/terramoor/github/io/`, `settings/` owns COUI, provider discovery, preview, and backup. `lyrics/` parses playback metadata and schedules line changes. `systemui/` resolves and draws into the native media capsule. `app/src/main/java/io/github/libxposed/service/` verifies the API 102 service caller. Xposed metadata lives in `app/src/main/resources/META-INF/xposed/`, and Android strings and drawables live in `app/src/main/res/`.

## Build and Test

Use Android SDK 37 and the wrapper’s JDK 25 toolchain. Run `./gradlew.bat` on Windows and `./gradlew` elsewhere.

- `:app:assembleLocalDebug :app:testLocalDebugUnitTest :app:lintLocalDebug` builds and checks the local APK.
- `:app:connectedLocalDebugAndroidTest` runs AndroidX tests. For device testing, confirm that PKX110 is unlocked and uses the exact firmware and plugin versions in `README.md`.
- `:app:assemblePublicRelease` builds the unsigned `io.github.luckyumimi.terramoor` APK. Sign outside Gradle using the release procedure in `docs/lsposed/submission.md`.

Use JUnit 4 with descriptive `*Test` classes. Test fresh defaults and old backup imports separately. Track actual hardware verification in `docs/acceptance.md`. Reuse successful checks when inputs are unchanged.

## Implementation

Use four-space Kotlin indentation, `PascalCase` types, `camelCase` members, and `lower_snake_case` resources. Reuse COUI components, color surfaces, and haptic feedback. Keep `CapsuleSettings` as the source of defaults for the app and hook. Route writes through `TerraMoorApp` so SystemUI receives changes in order. Keep the SystemUI scope fixed and verify view ownership before drawing. Restore native presentation when disabled or detached. Keep lyric parsing, provider detection, and setting writes local. The manual update button is the only in-app network request path. Keep it in `settings/GitHubUpdates.kt`, with bounded timeouts and no automatic retries or model calls.

## Changes and Releases

Use short imperative commit subjects. Include what changed, how it was verified, and before/after screenshots for visible changes. Keep APKs out of source commits, and keep signing keys, credentials, local settings, logs, and reference APKs out of Git. Update `README.md` for user-facing setup changes and reuse the same release signing key for upgrades.
