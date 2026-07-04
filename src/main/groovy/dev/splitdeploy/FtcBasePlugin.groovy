package dev.splitdeploy

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Configures the synthetic ':FtcBase' module: a thin com.android.application
 * that contains the FtcRobotController library, the FTC SDK and all libraries
 * from build.dependencies.gradle — everything except team code. Declares
 * ':TeamCode' as a dynamic feature.
 *
 * Also registers:
 *   installFullApp — builds base + split and installs both (first-time / after
 *                    base changes), replacing a stock monolithic install if found.
 */
class FtcBasePlugin implements Plugin<Project> {

    @Override
    void apply(Project p) {
        p.pluginManager.apply('com.android.application')
        def android = p.extensions.getByName('android')

        android.namespace = 'org.firstinspires.ftc.splitdeploy.base'

        SplitDeployShared.configureSigning(p, android)
        SplitDeployShared.configureCommon(p, android)

        def version = SplitDeployShared.readSdkVersion(p)
        android.defaultConfig.applicationId = SplitDeployShared.PACKAGE
        android.defaultConfig.versionCode = version.code
        android.defaultConfig.versionName = version.name

        android.dynamicFeatures.add(':TeamCode')

        // Optional folder for resources that must live in the BASE apk rather
        // than the TeamCode split (e.g. res/xml/teamwebcamcalibrations.xml,
        // which the SDK looks up by name). Create <root>/base-res/xml/... to use.
        def baseRes = p.rootProject.file('base-res')
        if (baseRes.exists()) {
            android.sourceSets.getByName('main').res.srcDir(baseRes)
        }

        p.dependencies.add('implementation', p.project(':FtcRobotController'))

        def depsFile = p.rootProject.file('build.dependencies.gradle')
        if (depsFile.exists()) {
            p.apply(from: depsFile)
        }

        registerInstallFullApp(p)
    }

    private static void registerInstallFullApp(Project p) {
        p.tasks.register('installFullApp') { t ->
            t.group = 'ftc'
            t.description = 'Full install: builds and installs the base app AND the TeamCode split. Needed once, and after SDK/library/base changes.'
            t.dependsOn('assembleDebug', ':TeamCode:assembleDebug')
            t.doLast {
                def adbExe = SplitDeployShared.adbPath(p)
                def pkg = SplitDeployShared.PACKAGE
                def baseApk = p.layout.buildDirectory.file('outputs/apk/debug/FtcBase-debug.apk').get().asFile
                def splitApk = p.rootProject.project(':TeamCode')
                        .layout.buildDirectory.file('outputs/apk/debug/TeamCode-debug.apk').get().asFile

                // A stock (monolithic) RC install can't accept splits — remove it first.
                def path = SplitDeployShared.adb(p, adbExe, ['shell', 'pm', 'path', pkg], true)
                if (path.contains('package:') && !SplitDeployShared.hasSplitInstall(p, adbExe)) {
                    p.logger.lifecycle('Removing existing non-split install of the RC app...')
                    SplitDeployShared.adb(p, adbExe, ['uninstall', pkg], true)
                }

                def out = SplitDeployShared.adb(p, adbExe,
                        ['install-multiple', '-r', '-t', baseApk.absolutePath, splitApk.absolutePath])
                if (!out.contains('Success')) {
                    throw new GradleException("install-multiple failed: $out")
                }
                SplitDeployShared.restartApp(p, adbExe)
                p.logger.lifecycle('Full app (base + TeamCode split) installed and started.')
                p.logger.lifecycle('From now on, use deployTeamCode for fast iteration.')
            }
        }
    }
}
