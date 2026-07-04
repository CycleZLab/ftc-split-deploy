package dev.splitdeploy

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Shared helpers: FTC signing config, version sync with the FtcRobotController
 * manifest, and adb plumbing for the deploy tasks.
 */
class SplitDeployShared {

    static final String PACKAGE = 'com.qualcomm.ftcrobotcontroller'
    static final String REMOTE_TMP = '/data/local/tmp/teamcode.split.apk'

    /** Mirrors the signing setup of the stock build.common.gradle. */
    static void configureSigning(Project p, Object android) {
        def debugKeystore = p.rootProject.file('libs/ftc.debug.keystore')

        android.signingConfigs.getByName('debug') { cfg ->
            cfg.keyAlias = 'androiddebugkey'
            cfg.keyPassword = 'android'
            cfg.storeFile = debugKeystore
            cfg.storePassword = 'android'
        }

        def release = android.signingConfigs.maybeCreate('release')
        def envStore = System.getenv('APK_SIGNING_STORE_FILE')
        if (envStore != null) {
            release.keyAlias = System.getenv('APK_SIGNING_KEY_ALIAS')
            release.keyPassword = System.getenv('APK_SIGNING_KEY_PASSWORD')
            release.storeFile = p.file(envStore)
            release.storePassword = System.getenv('APK_SIGNING_STORE_PASSWORD')
        } else {
            release.keyAlias = 'androiddebugkey'
            release.keyPassword = 'android'
            release.storeFile = debugKeystore
            release.storePassword = 'android'
        }
    }

    /**
     * Reads versionCode/versionName from the FtcRobotController manifest, the
     * same way the stock build.common.gradle keeps app version in sync with
     * the SDK version.
     */
    static Map readSdkVersion(Project p) {
        def manifest = p.rootProject.file('FtcRobotController/src/main/AndroidManifest.xml')
        if (!manifest.exists()) {
            throw new GradleException(
                'ftc.splitdeploy: FtcRobotController/src/main/AndroidManifest.xml not found. ' +
                'This plugin must be applied to a standard FtcRobotController project.')
        }
        def text = manifest.getText()
        def code = (text =~ /versionCode="(\d+)"/)
        def name = (text =~ /versionName="(.*)"/)
        if (!code.find() || !name.find()) {
            throw new GradleException('ftc.splitdeploy: could not parse versionCode/versionName from FtcRobotController manifest')
        }
        return [code: Integer.parseInt(code.group(1)), name: name.group(1)]
    }

    /** Common android {} settings shared by the base and the feature module. */
    static void configureCommon(Project p, Object android) {
        android.compileSdk = 34
        android.defaultConfig.minSdk = 24
        android.defaultConfig.targetSdk = 28
        android.defaultConfig.signingConfig = android.signingConfigs.getByName('debug')
        android.buildTypes.getByName('release').signingConfig = android.signingConfigs.getByName('release')
        android.compileOptions.sourceCompatibility = org.gradle.api.JavaVersion.VERSION_1_8
        android.compileOptions.targetCompatibility = org.gradle.api.JavaVersion.VERSION_1_8
        android.packagingOptions.jniLibs.useLegacyPackaging = true
        android.packagingOptions.jniLibs.pickFirsts.add('**/*.so')
    }

    static String adbPath(Project p) {
        def android = p.extensions.getByName('android')
        def isWindows = System.getProperty('os.name').toLowerCase().contains('windows')
        return new File(android.sdkDirectory, "platform-tools/adb${isWindows ? '.exe' : ''}").absolutePath
    }

    /** Runs adb with the given args, returns combined stdout+stderr, throws on failure. */
    static String adb(Project p, String adbExe, List<String> args, boolean ignoreExit = false) {
        def out = new ByteArrayOutputStream()
        def result = p.exec {
            commandLine([adbExe] + args)
            standardOutput = out
            errorOutput = out
            ignoreExitValue = true
        }
        def text = out.toString().trim()
        if (!ignoreExit && result.exitValue != 0) {
            throw new GradleException("adb ${args.join(' ')} failed (exit ${result.exitValue}):\n$text")
        }
        return text
    }

    static boolean hasSplitInstall(Project p, String adbExe) {
        def dump = adb(p, adbExe, ['shell', 'dumpsys', 'package', PACKAGE], true)
        return dump.contains('splits=[base, TeamCode]')
    }

    static void restartApp(Project p, String adbExe) {
        adb(p, adbExe, ['shell', 'am', 'force-stop', PACKAGE])
        adb(p, adbExe, ['shell', 'monkey', '-p', PACKAGE, '-c', 'android.intent.category.LAUNCHER', '1'], true)
    }
}
