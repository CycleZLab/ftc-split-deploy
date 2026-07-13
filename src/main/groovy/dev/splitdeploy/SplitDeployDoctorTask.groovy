package dev.splitdeploy

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@DisableCachingByDefault(because = 'Inspects external adb device state')
abstract class SplitDeployDoctorTask extends AbstractAdbTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getBaseFingerprintFile()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getTeamCodeSources()

    @TaskAction
    void diagnose() {
        def serial = requireDevice()
        def paths = installedPackagePaths(serial)
        def base = SplitDeployParsers.basePath(paths)
        def split = SplitDeployParsers.splitPath(paths, splitName.get())
        def state = SplitDeployState.load(stateFile(serial))

        logger.lifecycle("ftc-split-deploy doctor — device $serial")
        def collisions = onBotJavaCollisions(serial, teamCodeSources.files)
        if (!collisions.isEmpty()) {
            logger.lifecycle('STATUS: BLOCKED — duplicate classes exist in TeamCode and OnBot Java.')
            logger.lifecycle('Duplicates: ' + collisions.sort().join(', '))
            return
        }
        if (base == null) {
            logger.lifecycle('STATUS: FULL INSTALL REQUIRED — Robot Controller package is not installed.')
            return
        }
        if (split == null) {
            logger.lifecycle('STATUS: FULL INSTALL REQUIRED — installed Robot Controller is monolithic.')
            return
        }
        if (!state.getProperty('baseFingerprint')) {
            logger.lifecycle('STATUS: FULL INSTALL REQUIRED — no trusted v0.2 base record exists for this device.')
            return
        }
        if (state.getProperty('baseFingerprint') != baseFingerprintFile.get().asFile.text.trim()) {
            logger.lifecycle('STATUS: FULL INSTALL REQUIRED — local base-affecting inputs changed.')
            return
        }

        def expectedRemote = state.getProperty('remoteBaseSha256')
        def actualRemote = remoteSha256(serial, base)
        if (expectedRemote && actualRemote && expectedRemote != actualRemote) {
            logger.lifecycle('STATUS: FULL INSTALL REQUIRED — installed base APK changed outside this plugin.')
            return
        }
        logger.lifecycle('STATUS: READY FOR FAST DEPLOY')
        logger.lifecycle("Base:  $base")
        logger.lifecycle("Split: $split")
    }
}
