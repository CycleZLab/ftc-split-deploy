# ftc-split-deploy

**Deploy your FTC team code to the robot in seconds, not minutes.**

This Gradle plugin packages your `TeamCode` module as a *split APK* (usually
under 100 KB) and installs **only that split** on top of the already-installed
Robot Controller app. An edit → robot-running cycle takes a few seconds
instead of rebuilding and pushing the whole ~70 MB app.

Compared to classloader-based hot-reload tools, this uses only official
Android mechanisms (package-manager split-install sessions, Android 5+):

- ✅ Nothing custom runs inside the RC app — no reflection, no classloader
  swapping, nothing that can crash the robot
- ✅ Deployed code is **persistent** across robot power cycles
- ✅ OpModes are discovered normally (the FTC SDK scans split APKs natively)
- ✅ OnBotJava, Blocks, FTC Dashboard keep working
- ✅ Works on the REV Control Hub (Android 7.1) and phone RCs

---

## Quick start

Your project must be a standard
[FtcRobotController](https://github.com/FIRST-Tech-Challenge/FtcRobotController)
workspace (SDK 8.x+ / Android Gradle Plugin 8.x). Installation is **two edits**:

### 1. `settings.gradle` (project root) — replace the whole file with:

```groovy
pluginManagement {
    repositories {
        maven { url 'https://repo.cyclezlab.com' }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    id 'ftc.splitdeploy' version '0.1.0'
}

include ':FtcRobotController'
include ':TeamCode'
```

### 2. `TeamCode/build.gradle` — delete one line:

```groovy
apply from: '../build.common.gradle'   // <-- DELETE THIS LINE (the plugin replaces it)
```

Keep everything else (your `namespace`, `build.dependencies.gradle`,
dependencies, manifest, code) exactly as it is.

### 3. Sync and go

```bash
./gradlew initSplitDeploy    # optional: adds run configs to the Android Studio dropdown
./gradlew installFullApp     # once: full install of base + your code
./gradlew deployTeamCode     # every edit afterwards: seconds instead of minutes
```

---

## Daily usage

| Action | Task / dropdown entry | When |
|---|---|---|
| **Fast deploy** | `deployTeamCode` / *“TeamCode fast deploy”* | After every code edit. Builds + installs only your split, restarts the RC app. |
| **Full install** | `installFullApp` / *“Robot full install”* | First time; after changing `build.dependencies.gradle`, the FTC SDK version, or anything outside TeamCode. |
| Generate run configs | `initSplitDeploy` | Once per project (writes `.run/*.xml`, shared with the whole team via git). |

In Android Studio: pick **TeamCode fast deploy** in the run-configuration
dropdown once, then just press Run after each edit.

### Troubleshooting

- **"not installed as base+TeamCode splits"** → run `installFullApp` once.
  Also happens after a REV Hardware Client / stock-app reinstall replaced the app.
- **Multiple devices attached** → set `ANDROID_SERIAL=<serial>`.
- **Library version changed but robot behaves old** → libraries live in the
  base APK: run `installFullApp`.
- **Webcam calibration file**: the SDK reads `res/xml/teamwebcamcalibrations.xml`
  from the base APK. If your team uses it, move it to
  `<project root>/base-res/xml/teamwebcamcalibrations.xml` (picked up
  automatically). Assets in `TeamCode/src/main/assets` need no move — they
  deploy via the fast path.

---

## How it works

```
settings.gradle: id 'ftc.splitdeploy'
        │
        ├── generates .splitdeploy/FtcBase  ← synthetic base app module
        │     (FtcRobotController + FTC SDK + all libraries; git-ignored,
        │      you never edit it; appId/version/signing match stock)
        │
        └── configures :TeamCode as com.android.dynamic-feature
              → your code compiles into split_TeamCode.apk
```

`deployTeamCode` pushes the split and commits it with an *inheriting*
package-installer session (`pm install-create -r -t -p`), so Android replaces
just that one split of the installed app, then restarts it. On restart, the
FTC SDK's `ClassManager` re-scans OpMode annotations — including split dexes,
via `ApplicationInfo.splitSourceDirs` — so new/renamed OpModes appear on the
Driver Station with zero glue code.

The deploy is a clean app restart (USB hardware re-enumerates), so expect
~4–8 s end-to-end. That's the price of stock-app reliability.

---

## Alternative installation (offline / hacking on the plugin)

Clone this repo next to your robot project and use a composite build:

```groovy
// settings.gradle
pluginManagement {
    includeBuild('../ftc-split-deploy')
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}
plugins { id 'ftc.splitdeploy' }

include ':FtcRobotController'
include ':TeamCode'
```

Same two-edit rule applies (delete the `build.common.gradle` line in
`TeamCode/build.gradle`). This is handy for hacking on the plugin itself.

---

## Project layout

```
src/main/groovy/dev/splitdeploy/
├── SplitDeploySettingsPlugin.groovy  # entry point (settings plugin, id: ftc.splitdeploy)
├── FtcBasePlugin.groovy              # synthetic base module config + installFullApp
├── TeamCodeFeaturePlugin.groovy      # dynamic-feature config + deployTeamCode/initSplitDeploy
└── SplitDeployShared.groovy          # signing, version sync, adb helpers
```

## License

MIT — see [LICENSE](LICENSE).
