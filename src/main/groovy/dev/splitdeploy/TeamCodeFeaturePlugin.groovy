package dev.splitdeploy

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Configures TeamCode as the dynamic feature and registers field-safe deploy tasks. */
class TeamCodeFeaturePlugin implements Plugin<Project> {

    @Override
    void apply(Project p) {
        p.pluginManager.apply('com.android.dynamic-feature')
        def android = p.extensions.getByName('android')

        android.namespace = 'org.firstinspires.ftc.teamcode'
        // Signing is inherited from :FtcBase, as required for dynamic features.
        SplitDeployShared.configureCommon(p, android)
        p.dependencies.add('implementation', p.project(':FtcBase'))

        registerDeployTasks(p)
        registerInitSplitDeploy(p)
    }

    private static void registerDeployTasks(Project p) {
        def fingerprintFile = p.rootProject.layout.projectDirectory
            .file('.splitdeploy/state/local-base-fingerprint.txt')

        p.tasks.register('deployTeamCode', DeployTeamCodeTask) { t ->
            t.group = 'ftc'
            t.description = 'Backs up and transactionally replaces only TeamCode, then waits for FTC runtime readiness.'
            t.dependsOn('assembleDebug', ':FtcBase:generateSplitDeployBaseFingerprint')
            FtcBasePlugin.configureAdbTask(p, t)
            t.splitApk.set(p.layout.buildDirectory.file('outputs/apk/debug/TeamCode-debug.apk'))
            t.baseFingerprintFile.set(fingerprintFile)
            t.teamCodeSources.from(p.fileTree('src/main/java'), p.fileTree('src/main/kotlin'))
            t.allowOnBotDuplicates.convention(p.providers
                .gradleProperty('ftcSplitDeployAllowOnBotDuplicates')
                .map { Boolean.parseBoolean(it) }.orElse(false))
        }

        p.tasks.register('rollbackTeamCode', RollbackTeamCodeTask) { t ->
            t.group = 'ftc'
            t.description = 'Reinstalls the TeamCode split backed up before the most recent changed fast deploy.'
            FtcBasePlugin.configureAdbTask(p, t)
        }

        p.tasks.register('splitDeployDoctor', SplitDeployDoctorTask) { t ->
            t.group = 'ftc'
            t.description = 'Checks device selection, split installation, and base compatibility without changing the device.'
            t.dependsOn(':FtcBase:generateSplitDeployBaseFingerprint')
            FtcBasePlugin.configureAdbTask(p, t)
            t.baseFingerprintFile.set(fingerprintFile)
            t.teamCodeSources.from(p.fileTree('src/main/java'), p.fileTree('src/main/kotlin'))
        }
    }

    private static void registerInitSplitDeploy(Project p) {
        p.tasks.register('initSplitDeploy', GenerateRunConfigsTask) { t ->
            t.group = 'ftc'
            t.description = 'Writes Android Studio run configurations (.run/) for the split-deploy workflow.'
            t.runDirectory.set(p.rootProject.layout.projectDirectory.dir('.run'))
        }
    }
}
