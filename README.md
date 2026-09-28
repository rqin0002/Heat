# HeatShield | FIT5046 A4

Native Kotlin / Jetpack Compose app for outdoor work planning, recorded work and break responses.
Open this directory in Android Studio and run the `app` configuration. Android SDK 36.1, minimum API 26, target API 36. Gradle 9.6.0, AGP 9.4.1 and Kotlin 2.3.21 are pinned in the existing build files. The local build uses Android Studio's bundled JBR 25 with Java source/target 17.

## Run

Log in with a configured Firebase account, or choose **Continue on this device**. Device mode stores independent local records; it does not simulate cloud authentication. First entry opens work preferences. Create today's work record to record a pause response or measure an actual break. The timer survives process recreation on the same boot and discards unrecoverable timing after reboot. Whole elapsed minutes are added only when finishing the timer.

Plan an upcoming date and compare work windows. A complete two-hour window requires all three hourly boundary/intermediate values for the selected site, and a forecast fetched less than 60 minutes ago. Windows must fit after the shift start and finish by 15:00. Past, stale, missing or mismatched forecasts cannot be saved. The relative comparison is an application policy, not a medically validated exposure limit.

## Source structure

- `MainActivity.kt` starts the app.
- `ui/` retains the existing screens, navigation, theme and shared controls. `HeatShieldViewModel.kt` coordinates user actions and lifecycle state.
- `data/` contains the Room models/DAO, sensor CSV loader, weather, authentication and shared-site repositories.
- `domain/ContextEngine.kt` contains the deterministic context rules and forecast comparison.
- `background/` contains the periodic forecast worker and version-checked reminder receiver.
- `assets/microclimate_replay.csv` is the original observed environmental dataset. No example work records are automatically inserted.
- `app/src/test/` contains JVM tests for decision rules, weather/replay data validation and reminder eligibility. These run on the computer without an Android device.
- `app/src/androidTest/` contains Compose interactions, the real app lifecycle, Room persistence and local Firebase integration tests. These require an Android device or emulator.
- `app/src/debug/` contains only the manifest and network configuration for local Firebase emulator HTTP access. This is a debug build overlay, not a third test suite. The exception is absent from release builds. Both test directories use Gradle's standard locations; test classes are not packaged in the app.

The root `build.gradle.kts` declares build plugin versions. `app/build.gradle.kts` configures the app's Android SDK, package, libraries and source locations. Both are intentional. `settings.gradle.kts` connects the `app` module and repositories. The `gradle/` directory and `gradlew` scripts are the shared build wrapper; `.gradle/`, `.kotlin/`, `.idea/` and `build/` are local cache, IDE or generated directories excluded from Git.

Private records, plans and preferences are stored in Room and scoped by account ID. Device-mode records stay separate from cloud accounts. Signing out cancels that account's alarms; no private records are uploaded. Firestore holds only the shared, non-sensitive worksite catalogue. A valid downloaded catalogue is cached in Room for offline use.

## Firebase

The Android application ID is `au.edu.monash.heatshield`. Place this app's downloaded `google-services.json` in `app/`; it is excluded from Git. Builds without that file support device mode and clearly disable cloud account requests. Each teammate must obtain the matching configuration before using cloud accounts.

For the registered project, enable **Authentication → Email/Password**. The source `firestore.rules` allows authenticated reads of `worksites` only; all mobile-client writes and other paths are denied. Deploying these rules and adding catalogue documents are separate cloud operations. A valid document uses its stable worksite ID as the document ID and the fields `name` (string), `latitude` and `longitude` (numbers). Names and IDs must remain stable once records refer to them. Bundled site IDs are `birrarung-marr`, `carlton-gardens`, and `royal-park`; their coordinates are defined in `data/Models.kt`.

```powershell
firebase deploy --only firestore:rules --project fit5046-52a4a
```

Only run the deployment command when ready to update that project's rules. Adding the configuration file alone does not enable a provider or deploy rules.

## Verification

```powershell
.\gradlew.bat :app:testDebugUnitTest
# With an Android device/emulator connected:
.\gradlew.bat :app:connectedDebugAndroidTest
```

Tests are grouped by the behavior and environment they protect:

| Class | Location | Coverage |
| --- | --- | --- |
| `DecisionRulesTest` | `test` | Context thresholds, replay freshness/provenance, forecast joining/ranking and reminder ownership/timing |
| `UserInteractionTest` | `androidTest` | Authentication forms and busy states, planning, record editing/deletion, search and trends |
| `AppJourneyTest` | `androidTest` | Real Activity/ViewModel/Room flow, measured break restoration, duplicate completion and local sign-out/re-entry |
| `PersistenceTest` | `androidTest` | Account isolation, rejected mutations, database reopening and site/cache persistence |
| `FirebaseIntegrationTest` | `androidTest` | Local account lifecycle and authenticated read-only Firestore rules |

For a selected device test class, assemble/install both debug APKs and use `adb shell am instrument -w -r -e class heatshield.<ClassName> au.edu.monash.heatshield.test/androidx.test.runner.AndroidJUnitRunner`. These tests do not capture screenshots. Room tests use disposable databases; the app journey restores the existing local profile after execution.

Firebase tests use a separate named app and the **demo-heatshield** local project. They cannot fall back to the production project. Start local services with `firebase emulators:start --only auth,firestore --project demo-heatshield`, then run `heatshield.FirebaseIntegrationTest` with the additional instrumentation argument `-e firebaseEmulators true`, or run Gradle with `-Pandroid.testInstrumentationRunnerArguments.firebaseEmulators=true`. Without that argument only the Firebase cases are skipped. Each case creates and cleans up its own local accounts and catalogue fixtures, including after an assertion failure; no manual seeding is needed. Fixture administration is fixed to the local Firestore emulator, while permission assertions use the normal authenticated/unauthenticated client SDK. Debug network configuration allows local-emulator HTTP only; release cleartext remains disabled. Tests never create real accounts or send reset emails.

## Data and scheduling

The historical [City of Melbourne microclimate sensor dataset](https://data.melbourne.vic.gov.au/explore/dataset/microclimate-sensors-data/information/) is used under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The packaged 288 observed temperature/humidity pairs cover 7–9 January 2026 at Birrarung Marr. Playback emits a row every 20 seconds while the app is visible; observation and emission timestamps are distinct. Playback can be paused, stepped or restarted at shift time. Samples not emitted within 40 seconds cannot drive a new context decision; an unresolved recorded response remains open.

Live planning forecasts use [Open-Meteo weather](https://open-meteo.com/en/docs) and [air-quality UV](https://open-meteo.com/en/docs/air-quality-api), joined by timestamp in Australia/Melbourne time. Failed requests preserve the previous successful payload and timestamp, with a visible failure message.

WorkManager schedules one network-constrained 30-minute periodic refresh. Android can defer it. AlarmManager schedules one inexact reminder for the saved window per active account. Plan changes, cancellation, opt-out and sign-out cancel or replace it. Delivery rechecks the owner, stored preference, revision and due time. Notifications require system permission; the app remains usable without it. Force-stop, reboot and OS scheduling restrictions can prevent delivery; reopening restores eligible future plans. This app does not measure body temperature or certify recovery.
