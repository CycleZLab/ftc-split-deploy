package dev.splitdeploy

import org.gradle.api.GradleException
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.TaskAction

@DisableCachingByDefault(because = 'Installs and starts an application on an external Android device')
abstract class RollbackTeamCodeTask extends AbstractAdbTask {

    @TaskAction
    void rollback() {
        def serial = requireDevice()
        def file = stateFile(serial)
        def state = SplitDeployState.load(file)
        def backupPath = state.getProperty('lastBackup')
        if (!backupPath) {
            throw new GradleException(
                "There is no ${splitName.get()} backup recorded for $serial. " +
                'A backup is created immediately before every changed fast deploy.')
        }

        def paths = installedPackagePaths(serial)
        def basePath = SplitDeployParsers.basePath(paths)
        if (basePath == null || SplitDeployParsers.splitPath(paths, splitName.get()) == null) {
            throw new GradleException('The base + TeamCode split installation is missing; run installFullApp instead.')
        }
        def expectedBase = state.getProperty('remoteBaseSha256')
        def currentBase = remoteSha256(serial, basePath)
        if (expectedBase && currentBase && expectedBase != currentBase) {
            throw new GradleException(
                'The installed base has changed since this backup was made; refusing a potentially incompatible rollback.')
        }

        def backup = new File(backupPath)
        if (!backup.isFile() || backup.length() == 0) {
            throw new GradleException("The recorded rollback APK no longer exists: $backup")
        }

        logger.lifecycle("Rolling back ${splitName.get()} on $serial from ${backup.name}...")
        partialInstall(serial, backup)
        state.setProperty('splitApkSha256', FileHashing.sha256(backup))
        state.setProperty('lastRolledBackAt', new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"))
        SplitDeployState.save(file, state)

        def ready = restartAndWait(serial)
        logger.lifecycle(ready ?
            "${splitName.get()} rollback complete; Robot Controller and FTC runtime are ready." :
            "${splitName.get()} rollback installed; check the readiness warning above.")
    }
}
