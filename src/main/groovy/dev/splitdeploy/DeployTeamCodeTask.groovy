package dev.splitdeploy

import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@DisableCachingByDefault(because = 'Installs and starts an application on an external Android device')
abstract class DeployTeamCodeTask extends AbstractAdbTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getSplitApk()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getBaseFingerprintFile()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getTeamCodeSources()

    @Input
    abstract Property<Boolean> getAllowOnBotDuplicates()

    @TaskAction
    void deploy() {
        def serial = requireDevice()
        rejectOnBotJavaCollisions(serial, teamCodeSources.files, allowOnBotDuplicates.get())
        def stateFile = stateFile(serial)
        def state = SplitDeployState.load(stateFile)
        requireCompatibleBase(state, serial)

        def paths = installedPackagePaths(serial)
        def basePath = SplitDeployParsers.basePath(paths)
        def oldSplitPath = SplitDeployParsers.splitPath(paths, splitName.get())
        if (basePath == null || oldSplitPath == null) {
            throw new GradleException(
                "${packageName.get()} is not installed as a base + ${splitName.get()} split on $serial.\n" +
                'Run `installFullApp` once first.')
        }

        verifyRemoteBase(state, serial, basePath)

        def apk = splitApk.get().asFile
        def newHash = FileHashing.sha256(apk)
        def installedHash = remoteSha256(serial, oldSplitPath)
        if (installedHash != null && installedHash == newHash) {
            logger.lifecycle("${splitName.get()} split is already current; skipping APK transfer.")
            def ready = restartAndWait(serial)
            logger.lifecycle(ready ? 'Robot Controller and FTC runtime are ready.' :
                'Robot Controller started; check the readiness warning above.')
            return
        }

        def backup = backupInstalledSplit(serial, oldSplitPath, installedHash)
        state.setProperty('lastBackup', backup.absolutePath)
        state.setProperty('lastBackupCreatedAt', new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"))
        SplitDeployState.save(stateFile, state)

        logger.lifecycle("Deploying ${apk.length()} byte ${splitName.get()} split to $serial...")
        partialInstall(serial, apk)

        state.setProperty('splitApkSha256', newHash)
        state.setProperty('lastDeployedAt', new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"))
        SplitDeployState.save(stateFile, state)

        def ready = restartAndWait(serial)
        logger.lifecycle(ready ?
            "${splitName.get()} deployed; Robot Controller and FTC runtime are ready." :
            "${splitName.get()} deployed and Robot Controller started; use rollbackTeamCode if validation fails.")
    }

    private void requireCompatibleBase(Properties state, String serial) {
        if (state.isEmpty() || !state.getProperty('baseFingerprint')) {
            throw new GradleException(
                "No trusted split-deploy base record exists for $serial. " +
                'Run `installFullApp` once; v0.2+ records the exact base compatibility fingerprint.')
        }
        def current = baseFingerprintFile.get().asFile.text.trim()
        if (state.getProperty('baseFingerprint') != current) {
            throw new GradleException(
                'Base-affecting SDK, dependency, resource, build-script, or plugin inputs changed. ' +
                'A TeamCode-only deploy could be binary-incompatible; run `installFullApp` first.')
        }
    }

    private void verifyRemoteBase(Properties state, String serial, String basePath) {
        def expected = state.getProperty('remoteBaseSha256')
        if (!expected) return
        def actual = remoteSha256(serial, basePath)
        if (actual == null) {
            logger.warn('The device does not provide sha256sum; relying on the recorded local base fingerprint.')
        } else if (actual != expected) {
            throw new GradleException(
                'The installed base APK was replaced outside ftc-split-deploy. ' +
                'Run `installFullApp` to re-establish a compatible base before deploying TeamCode.')
        }
    }

    private File backupInstalledSplit(String serial, String remotePath, String knownHash) {
        def dir = new File(stateDirectory.get().asFile,
            "backups/${SplitDeployParsers.safeFileName(serial)}")
        dir.mkdirs()
        def suffix = knownHash ? knownHash.substring(0, 12) : new Date().format('yyyyMMdd-HHmmss-SSS')
        def backup = new File(dir, "${splitName.get()}-${suffix}.apk")
        if (backup.exists() && knownHash && FileHashing.sha256(backup) != knownHash) {
            logger.warn("Discarding corrupt cached rollback APK ${backup.name}.")
            backup.delete()
        }
        if (!backup.exists()) {
            def pull = runAdb(serial, ['pull', remotePath, backup.absolutePath])
            if (!pull.success || !backup.isFile() || backup.length() == 0) {
                backup.delete()
                throw new GradleException(
                    "Could not preserve the currently working ${splitName.get()} split; deployment was aborted:\n" +
                    pull.output)
            }
            if (knownHash && FileHashing.sha256(backup) != knownHash) {
                backup.delete()
                throw new GradleException(
                    "The preserved ${splitName.get()} split failed its SHA-256 integrity check; deployment was aborted.")
            }
        }
        backup.setLastModified(System.currentTimeMillis())
        pruneBackups(dir, backup)
        return backup
    }

    private static void pruneBackups(File directory, File keep) {
        def old = directory.listFiles()?.findAll { it.isFile() && it.name.endsWith('.apk') }
            ?.sort { a, b -> b.lastModified() <=> a.lastModified() } ?: []
        old.drop(5).findAll { it != keep }.each { it.delete() }
    }
}
