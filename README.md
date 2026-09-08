# HeatShield | FIT5046 A2

Jetpack Compose skeleton prototype. Open this **HeatShield** directory in Android Studio and run the **app** configuration on an emulator. Begin at Login and select **Explore demo**.

## Build

Android Studio 2026.1.4, AGP 9.4.0, Gradle 9.6.0, Kotlin/Compose compiler 2.3.21, Compose BOM 2025.10.01 and Navigation Compose 2.9.5.

Install SDK platform **Android 36.1** and build tools **36.0.0** if missing.
Minimum device API 26, target API 36.
The verified build uses Android Studio's bundled JBR 25.0.3, with Java source/target compatibility set to 17.
Wrapper scripts and JAR are included. First Gradle sync downloads dependencies from official repositories.
Android Studio creates your machine's `local.properties`; the submission ZIP excludes the author's local file.

To retain the twenty evidence screenshots, build and install both APKs, run the instrumentation directly, and pull the app's external-files directory before uninstalling. The recorded Gradle connected-test run passed, but its UTP cleanup uninstalled the app and removed that directory. With an emulator running, execute the following in PowerShell from this project directory. Replace `emulator-5554` with the serial shown by `adb devices` if necessary.

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
$heatShieldAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $heatShieldAdb devices
& $heatShieldAdb -s emulator-5554 install -r '.\app\build\outputs\apk\debug\app-debug.apk'
& $heatShieldAdb -s emulator-5554 install -r '.\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
& $heatShieldAdb -s emulator-5554 shell am force-stop au.edu.monash.heatshield
& $heatShieldAdb -s emulator-5554 shell am instrument -w -r -e class heatshield.PrototypeJourneyTest au.edu.monash.heatshield.test/androidx.test.runner.AndroidJUnitRunner
& $heatShieldAdb -s emulator-5554 pull /sdcard/Android/data/au.edu.monash.heatshield/files/screenshots .\captured-screenshots
```

## Scope and data

Implemented: eleven Compose destinations, four-tab bottom navigation, real Material3 controls, password feedback, temporary record CRUD, combined search filters, chart aggregation and context-response preview.

Room, Retrofit, Firebase, sensor streaming and notifications are **proposed A4 integrations**, not implemented here. No INTERNET or notification permission is requested. The real public CSV is staged for A4. It is not the source of the authored UI fixtures. The interface depicts a fictional January12 shift; the sensor file records January7–9. The September forecast snapshot is a separate API availability probe.

Demo records last for the process and survive activity recreation through a ViewModel. Records and Trends use the same state. The work-window fixture is limited to January12; other dates/time combinations show a no-comparison state. A break response updates the active demonstration record without inventing elapsed minutes. Profile controls illustrate future preferences.

Source files are under `app/src/main/java/heatshield/`, with Kotlin packages `heatshield` and `heatshield.ui`. The Gradle namespace is `heatshield`; the installation ID remains `au.edu.monash.heatshield` so this build updates the existing app. The launcher class is `heatshield.MainActivity`.

`MainActivity.kt` launches Compose. `ui/HeatShieldApp.kt` owns navigation and fixture state. `Theme.kt` and `Components.kt` define the visual system. `AuthScreens.kt`, `ShiftScreens.kt` and `RecordsScreens.kt` implement the flows. `assets/` holds the future sensor input and attribution. `androidTest/` holds the interaction walkthrough.

## Attribution

Environmental sensor data: City of Melbourne, *Microclimate sensors data*

[Dataset](https://data.melbourne.vic.gov.au/explore/dataset/microclimate-sensors-data/information/)

[Licence](https://creativecommons.org/licenses/by/4.0/)

Launcher artwork: the group-supplied PNG, preserved as `res/drawable-nodpi/heatshield_logo.png` and referenced by `heatshield_icon.xml`. In-app control icons: Android Material Icons via AndroidX (Apache2.0). Fonts: Android system sans-serif. Charts and layouts are original Compose code. All report screens are captured from the running emulator.

The supplied FIT5046 weekly teaching materials were reviewed for alignment after the prototype was authored. The app aligns with the taught Compose layouts, observable state, lists, dropdowns, date pickers, shared ViewModel and navigation patterns. The proposed A4 Room layers align with the Week 7 architecture. These are reference alignments, not claims that teaching code or assets were copied into the project.
