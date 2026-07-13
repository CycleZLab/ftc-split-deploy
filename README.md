# ftc-split-deploy

**Deploy your FTC team code to the robot in seconds, not minutes.**

This Gradle plugin packages your `TeamCode` module as a *split APK* (usually
under 100 KB) and installs **only that split** on top of the already-installed
Robot Controller app. An edit → robot-running cycle takes a few seconds
instead of rebuilding and pushing the whole ~70 MB app.

Compared to classloader-based hot-reload tools, this uses Android's native
split APK and PackageInstaller mechanisms:

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
workspace using Android Gradle Plugin 8.x. Installation is **two edits**:

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
    id 'ftc.splitdeploy' version '0.2.5'
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
./gradlew splitDeployDoctor  # verifies this device is safe for fast deploy
./gradlew deployTeamCode     # every edit afterwards: seconds instead of minutes
```

---

## Daily usage

| Action | Task / dropdown entry | When |
|---|---|---|
| **Fast deploy** | `deployTeamCode` / *“TeamCode fast deploy”* | After every code edit. Builds + installs only your split, restarts the RC app. |
| **Full install** | `installFullApp` / *“TeamCode”* | First time; after changing `build.dependencies.gradle`, the FTC SDK version, or anything outside TeamCode. |
| **Preflight** | `splitDeployDoctor` | Read-only check of the device, split layout, duplicate OnBot classes, and base compatibility. |
| **Rollback** | `rollbackTeamCode` | Restores the split backed up immediately before the most recent changed fast deploy. |
| Generate run configs | `initSplitDeploy` | Once per project (writes `.run/*.xml`, shared with the whole team via git). |

In Android Studio: pick **TeamCode fast deploy** in the run-configuration
dropdown once, then just press Run after each edit. A normal warm deploy waits
until the FTC SDK reports `Robot Status: running`; expect roughly 4–8 seconds,
not merely the APK transfer time.

### What v0.2 protects

- Selects exactly one authorized adb device. With multiple devices, set
  `ANDROID_SERIAL=<serial>` or `-PftcSplitDeploySerial=<serial>`.
- Fingerprints base-affecting source, resources, build scripts, plugin version,
  and resolved Maven artifacts. A base change blocks fast deploy and requests a
  full install instead of risking a runtime linkage crash.
- Verifies the installed base APK has not been replaced outside this plugin.
- Backs up the working split before replacement; failed PackageInstaller
  sessions are abandoned and remote temporary files are always removed.
- Detects matching top-level fully-qualified classes between repo TeamCode and
  `/sdcard/FIRST/java/src` (OnBot Java). Delete/rename one copy before deploy so
  stale or duplicate OpModes cannot win by classloader order.
- Full install uses `-g` so Android grants the RC runtime permissions. It first
  tries a non-destructive replacement and **never silently uninstalls** the RC
  app.

If Android cannot replace an incompatible existing install, connect by USB and
explicitly run `./gradlew installFullApp --allow-uninstall`. This is deliberately
blocked over network adb because uninstalling can reset Control Hub app/network
state and cut the only connection.

Upgrading from v0.1 requires one `installFullApp`; v0.2 creates a trusted,
per-device compatibility record under the git-ignored `.splitdeploy/state/`.
The hardware identity is used for this record, so switching the same robot
between USB and Wi-Fi adb does not invalidate it.

### Troubleshooting

- **`FULL INSTALL REQUIRED`** → run `installFullApp`. The doctor tells you
  whether the app is monolithic, the base changed, or v0.2 has no trusted record.
- **No authorized adb device** → run `adb devices -l`, attach USB or connect adb,
  unlock the device, and accept its authorization prompt.
- **Duplicate TeamCode / OnBot class** → remove one copy. Keeping the same class
  in both systems is not a supported source-sync strategy.
- **Library version changed but robot behaves old** → libraries live in the
  base APK; the compatibility guard should require `installFullApp`.
- **New deploy starts but fails validation** → run `rollbackTeamCode`, then
  inspect the Robot Controller log before another deploy.
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

`deployTeamCode` backs up the installed split, pushes the new one, and commits it with an *inheriting*
package-installer session (`pm install-create -r -t -p`), so Android replaces
just that one split of the installed app. Every transaction stage is checked;
failures abandon the session. The plugin then restarts the app. On restart, the
FTC SDK's `ClassManager` re-scans OpMode annotations — including split dexes,
via `ApplicationInfo.splitSourceDirs` — so new/renamed OpModes appear on the
Driver Station with zero glue code.

The deploy is a clean app restart (USB hardware re-enumerates). OnBot Java and
Blocks remain installed and operational; only exact repo/OnBot class collisions
are blocked.

### Optional properties

```properties
# gradle.properties (examples)
ftcSplitDeploySerial=192.168.43.1:5555
ftcSplitDeployReadyTimeoutSeconds=20
```

`ftcSplitDeployAllowOnBotDuplicates=true` exists only as an explicit emergency
override and is not recommended for field use. The destructive full-install
fallback can also be enabled with `-PftcSplitDeployAllowUninstall=true`, though
the task option `--allow-uninstall` is clearer.

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
├── AbstractAdbTask.groovy             # isolated, configuration-cache-safe adb execution
├── DeployTeamCodeTask.groovy          # transactional fast deploy + backup
├── RollbackTeamCodeTask.groovy        # last-known-good split restore
└── BaseFingerprintTask.groovy         # binary-compatibility fingerprint
```

## License

MIT — see [LICENSE](LICENSE).
