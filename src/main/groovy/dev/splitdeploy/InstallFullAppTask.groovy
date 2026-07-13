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
import org.gradle.api.tasks.options.Option

@DisableCachingByDefault(because = 'Installs and starts an application on an external Android device')
abstract class InstallFullAppTask extends AbstractAdbTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getBaseApk()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getSplitApk()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getBaseFingerprintFile()

    @Input
    abstract Property<Boolean> getAllowUninstall()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getTeamCodeSources()

    @Input
    abstract Property<Boolean> getAllowOnBotDuplicates()

    @Option(option = 'allow-uninstall', description = 'Allow a USB-only destructive fallback if Android cannot replace the current app')
    void allowUninstall() {
        allowUninstall.set(true)
    }

    @TaskAction
    void install() {
        def serial = requireDevice()
        rejectOnBotJavaCollisions(serial, teamCodeSources.files, allowOnBotDuplicates.get())
        def before = installedPackagePaths(serial)
        logger.lifecycle("Installing full Robot Controller base + ${splitName.get()} split on $serial...")

        def args = ['install-multiple', '-r', '-t', '-g',
            baseApk.get().asFile.absolutePath, splitApk.get().asFile.absolutePath]
        def install = runAdb(serial, args)

        if (!SplitDeployParsers.packageManagerSuccess(install)) {
            if (!allowUninstall.get()) {
                throw new GradleException(
                    "Android could not non-destructively replace the existing Robot Controller app:\n${install.output}\n\n" +
                    'The plugin did NOT uninstall it. If you understand that uninstalling can reset Control Hub ' +
                    'network/app data, connect by USB and retry with `installFullApp --allow-uninstall`.')
            }
            if (isNetworkSerial(serial)) {
                throw new GradleException(
                    "Refusing to uninstall the Robot Controller over network adb ($serial). " +
                    'An uninstall can sever the only connection. Connect by USB and retry.')
            }

            logger.warn('Explicit destructive fallback enabled: uninstalling the existing Robot Controller app.')
            def uninstall = runAdb(serial, ['uninstall', packageName.get()])
            if (!SplitDeployParsers.packageManagerSuccess(uninstall)) {
                throw new GradleException("Could not uninstall the incompatible app:\n${uninstall.output}")
            }
            install = runAdb(serial, args)
            if (!SplitDeployParsers.packageManagerSuccess(install)) {
                throw new GradleException(
                    "The app was uninstalled, but the full split install failed:\n${install.output}\n" +
                    'Keep the USB connection attached and install the stock Robot Controller APK if needed.')
            }
        }

        def installed = installedPackagePaths(serial)
        def basePath = SplitDeployParsers.basePath(installed)
        def installedSplit = SplitDeployParsers.splitPath(installed, splitName.get())
        if (basePath == null || installedSplit == null) {
            throw new GradleException(
                "Android reported install success, but the expected base + ${splitName.get()} paths were not present:\n" +
                installed.join('\n'))
        }

        def state = new Properties()
        state.setProperty('serial', serial)
        state.setProperty('deviceIdentity', deviceIdentity(serial))
        state.setProperty('packageName', packageName.get())
        state.setProperty('splitName', splitName.get())
        state.setProperty('baseFingerprint', baseFingerprintFile.get().asFile.text.trim())
        state.setProperty('baseApkSha256', FileHashing.sha256(baseApk.get().asFile))
        state.setProperty('splitApkSha256', FileHashing.sha256(splitApk.get().asFile))
        state.setProperty('installedAt', new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"))
        def remoteBaseHash = remoteSha256(serial, basePath)
        if (remoteBaseHash != null) state.setProperty('remoteBaseSha256', remoteBaseHash)
        SplitDeployState.save(stateFile(serial), state)

        def ready = restartAndWait(serial)
        logger.lifecycle(
            ready
                ? 'Full app installed; Robot Controller and FTC runtime are ready.'
                : 'Full app installed and Robot Controller started; check the warning above before field use.')
        if (before && SplitDeployParsers.splitPath(before, splitName.get()) == null) {
            logger.lifecycle('The previous monolithic install was upgraded without an automatic uninstall.')
        }
    }

    private static boolean isNetworkSerial(String serial) {
        return serial.contains(':') || serial.contains('._adb-tls-connect._tcp')
    }
}
