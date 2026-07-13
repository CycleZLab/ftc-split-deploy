package dev.splitdeploy

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

/** Configures the generated base application and its safe full-install task. */
class FtcBasePlugin implements Plugin<Project> {

    @Override
    void apply(Project p) {
        p.pluginManager.apply('com.android.application')
        def android = p.extensions.getByName('android')

        android.namespace = 'org.firstinspires.ftc.splitdeploy.base'
        SplitDeployShared.configureSigning(p, android)
        SplitDeployShared.configureCommon(p, android)

        def sdkVersion = SplitDeployShared.readSdkVersion(p)
        android.defaultConfig.applicationId = SplitDeployShared.PACKAGE
        android.defaultConfig.versionCode = sdkVersion.code
        android.defaultConfig.versionName = sdkVersion.name
        android.dynamicFeatures.add(':TeamCode')

        def baseRes = p.rootProject.file('base-res')
        if (baseRes.exists()) {
            android.sourceSets.getByName('main').res.srcDir(baseRes)
        }

        p.dependencies.add('implementation', p.project(':FtcRobotController'))
        def dependenciesScript = p.rootProject.file('build.dependencies.gradle')
        if (dependenciesScript.exists()) p.apply(from: dependenciesScript)

        def fingerprint = registerBaseFingerprint(p)
        registerInstallFullApp(p, fingerprint)
    }

    private static def registerBaseFingerprint(Project p) {
        def root = p.rootProject
        def task = p.tasks.register('generateSplitDeployBaseFingerprint', BaseFingerprintTask) { t ->
            t.group = 'ftc'
            t.description = 'Fingerprints every base-affecting input used by the fast-deploy compatibility guard.'
            t.pluginVersion.set(SplitDeployPluginVersion.CURRENT)
            t.rootDirectory.set(root.layout.projectDirectory)
            t.fingerprintFile.set(root.layout.projectDirectory.file('.splitdeploy/state/local-base-fingerprint.txt'))
            t.baseInputs.from(
                root.file('settings.gradle'),
                root.file('settings.gradle.kts'),
                root.file('build.gradle'),
                root.file('build.gradle.kts'),
                root.file('build.common.gradle'),
                root.file('build.dependencies.gradle'),
                root.file('gradle.properties'),
                root.file('gradle/libs.versions.toml'),
                root.file('FtcRobotController/build.gradle'),
                root.file('FtcRobotController/build.gradle.kts'),
                root.fileTree('FtcRobotController/src') { files ->
                    files.exclude('**/.DS_Store')
                },
                root.fileTree('FtcRobotController/libs') { files ->
                    files.include('**/*.aar', '**/*.jar')
                },
                root.fileTree('base-res') { files ->
                    files.exclude('**/.DS_Store')
                },
                root.fileTree('libs') { files ->
                    files.include('**/*.aar', '**/*.jar', '**/*.keystore')
                }
            )
        }

        // Maven artifact bytes are base inputs too. Project outputs are
        // intentionally excluded because the FTC SDK generates APP_BUILD_TIME
        // on each configuration and would otherwise force a full install every run.
        p.configurations.matching { it.name == 'debugRuntimeClasspath' }.all { configuration ->
            def externalArtifacts = configuration.incoming.artifactView { view ->
                view.componentFilter { id -> id instanceof ModuleComponentIdentifier }
            }.files
            task.configure { it.baseInputs.from(externalArtifacts) }
        }
        return task
    }

    private static void registerInstallFullApp(Project p, def fingerprint) {
        p.tasks.register('installFullApp', InstallFullAppTask) { t ->
            t.group = 'ftc'
            t.description = 'Safely installs base + TeamCode, grants permissions, records compatibility, and waits for FTC readiness.'
            t.dependsOn('assembleDebug', ':TeamCode:assembleDebug', fingerprint)
            configureAdbTask(p, t)
            t.baseApk.set(p.layout.buildDirectory.file('outputs/apk/debug/FtcBase-debug.apk'))
            t.splitApk.set(p.rootProject.project(':TeamCode')
                .layout.buildDirectory.file('outputs/apk/debug/TeamCode-debug.apk'))
            t.baseFingerprintFile.set(p.rootProject.layout.projectDirectory
                .file('.splitdeploy/state/local-base-fingerprint.txt'))
            t.allowUninstall.convention(p.providers.gradleProperty('ftcSplitDeployAllowUninstall')
                .map { Boolean.parseBoolean(it) }.orElse(false))
            t.teamCodeSources.from(p.rootProject.fileTree('TeamCode/src/main/java'),
                p.rootProject.fileTree('TeamCode/src/main/kotlin'))
            t.allowOnBotDuplicates.convention(p.providers
                .gradleProperty('ftcSplitDeployAllowOnBotDuplicates')
                .map { Boolean.parseBoolean(it) }.orElse(false))
        }
    }

    static void configureAdbTask(Project p, AbstractAdbTask task) {
        task.adbExecutable.set(SplitDeployShared.adbFile(p))
        task.packageName.set(SplitDeployShared.PACKAGE)
        task.splitName.set(SplitDeployShared.SPLIT_NAME)
        task.deviceSerial.convention(p.providers.gradleProperty('ftcSplitDeploySerial')
            .orElse(p.providers.environmentVariable('ANDROID_SERIAL')))
        task.readyTimeoutSeconds.convention(p.providers.gradleProperty('ftcSplitDeployReadyTimeoutSeconds')
            .map { Integer.parseInt(it) }.orElse(15))
        task.stateDirectory.set(p.rootProject.layout.projectDirectory.dir('.splitdeploy/state'))
    }
}
