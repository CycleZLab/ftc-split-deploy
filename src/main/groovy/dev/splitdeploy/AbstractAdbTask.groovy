package dev.splitdeploy

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.ExecOperations

import javax.inject.Inject
import java.util.regex.Pattern

abstract class AbstractAdbTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getAdbExecutable()

    @Input
    abstract Property<String> getPackageName()

    @Input
    abstract Property<String> getSplitName()

    @Input
    @Optional
    abstract Property<String> getDeviceSerial()

    @Input
    abstract Property<Integer> getReadyTimeoutSeconds()

    @LocalState
    abstract DirectoryProperty getStateDirectory()

    @Inject
    protected abstract ExecOperations getExecOperations()

    protected String requireDevice() {
        def result = runAdbRaw(['devices', '-l'])
        if (!result.success) {
            throw new GradleException("Unable to query adb devices:\n${result.output}")
        }
        return SplitDeployParsers.selectDevice(result.output, deviceSerial.orNull)
    }

    protected CommandResult runAdbRaw(List<String> args) {
        def output = new ByteArrayOutputStream()
        def result = execOperations.exec { spec ->
            spec.commandLine(([adbExecutable.get().asFile.absolutePath] + args) as List<String>)
            spec.standardOutput = output
            spec.errorOutput = output
            spec.ignoreExitValue = true
        }
        return new CommandResult(result.exitValue, output.toString('UTF-8').trim())
    }

    protected CommandResult runAdb(String serial, List<String> args) {
        return runAdbRaw((['-s', serial] + args) as List<String>)
    }

    protected String requireAdb(String serial, List<String> args) {
        def result = runAdb(serial, args)
        if (!result.success) {
            throw new GradleException(
                "adb ${args.join(' ')} failed (exit ${result.exitCode}):\n${result.output}")
        }
        return result.output
    }

    protected List<String> installedPackagePaths(String serial) {
        def result = runAdb(serial, ['shell', 'pm', 'path', packageName.get()])
        return result.success ? SplitDeployParsers.packagePaths(result.output) : []
    }

    protected File stateFile(String serial) {
        def identity = deviceIdentity(serial)
        return new File(stateDirectory.get().asFile,
            "${SplitDeployParsers.safeFileName(identity)}.properties")
    }

    /** Stable across USB and Wi-Fi adb transports for the same robot. */
    protected String deviceIdentity(String serial) {
        for (String property : ['ro.serialno', 'ro.boot.serialno']) {
            def result = runAdb(serial, ['shell', 'getprop', property])
            def value = result.output.trim()
            if (result.success && value && !value.equalsIgnoreCase('unknown')) return value
        }
        return serial
    }

    protected String remoteSha256(String serial, String remotePath) {
        if (!remotePath) return null
        def result = runAdb(serial, ['shell', 'sha256sum', remotePath])
        if (!result.success) return null
        def match = result.output =~ /(?i)\b([0-9a-f]{64})\b/
        return match.find() ? match.group(1).toLowerCase() : null
    }

    protected Set<String> onBotJavaCollisions(String serial, Collection<File> localSources) {
        def remoteSources = runAdb(serial,
            ['shell', 'find', '/sdcard/FIRST/java/src', '-type', 'f'])
        if (!remoteSources.success) {
            if (remoteSources.output.contains('No such file or directory')) return [] as Set<String>
            throw new GradleException(
                "Could not inspect OnBot Java sources before deployment:\n${remoteSources.output}")
        }
        return OnBotJavaCollisionDetector.collisions(localSources, remoteSources.output)
    }

    protected void rejectOnBotJavaCollisions(
        String serial,
        Collection<File> localSources,
        boolean allowDuplicates
    ) {
        def collisions = onBotJavaCollisions(serial, localSources)
        if (collisions.isEmpty()) return
        def names = collisions.sort().join(', ')
        if (!allowDuplicates) {
            throw new GradleException(
                "TeamCode and OnBot Java both define: $names\n" +
                'Delete or rename the OnBot copies before deploying. To deploy despite the risk, ' +
                'use -PftcSplitDeployAllowOnBotDuplicates=true.')
        }
        logger.warn("Proceeding with duplicate repo/OnBot Java classes by explicit override: $names")
    }

    /**
     * Replaces one split using a PackageInstaller session. The session is
     * abandoned and the temporary APK removed on every failure path.
     */
    protected void partialInstall(String serial, File apk) {
        def apkHash = FileHashing.sha256(apk)
        def remote = "/data/local/tmp/teamcode.${apkHash.substring(0, 12)}.${System.currentTimeMillis()}.apk"
        String session = null
        boolean committed = false

        try {
            requireAdb(serial, ['push', apk.absolutePath, remote])

            def create = runAdb(serial,
                ['shell', 'pm', 'install-create', '-r', '-t', '-p', packageName.get()])
            session = SplitDeployParsers.sessionId(create.output)
            if (!create.success || session == null) {
                throw new GradleException("pm install-create failed:\n${create.output}")
            }

            def write = runAdb(serial,
                ['shell', 'pm', 'install-write', session, splitName.get(), remote])
            if (!SplitDeployParsers.packageManagerSuccess(write)) {
                throw new GradleException("pm install-write failed for session $session:\n${write.output}")
            }

            def commit = runAdb(serial, ['shell', 'pm', 'install-commit', session])
            if (!SplitDeployParsers.packageManagerSuccess(commit)) {
                throw new GradleException("pm install-commit failed for session $session:\n${commit.output}")
            }
            committed = true
        } finally {
            if (session != null && !committed) {
                runAdb(serial, ['shell', 'pm', 'install-abandon', session])
            }
            runAdb(serial, ['shell', 'rm', '-f', remote])
        }
    }

    /** Returns true only when the FTC SDK reports Robot Status: running. */
    protected boolean restartAndWait(String serial) {
        if (readyTimeoutSeconds.get() < 1) {
            throw new GradleException('ftcSplitDeployReadyTimeoutSeconds must be at least 1.')
        }
        requireAdb(serial, ['shell', 'am', 'force-stop', packageName.get()])
        def launch = runAdb(serial,
            ['shell', 'monkey', '-p', packageName.get(), '-c', 'android.intent.category.LAUNCHER', '1'])
        if (!launch.success) {
            throw new GradleException("Robot Controller could not be launched:\n${launch.output}")
        }

        long deadline = System.nanoTime() + readyTimeoutSeconds.get() * 1_000_000_000L
        String lastPid = null
        while (System.nanoTime() < deadline) {
            lastPid = runningPid(serial)
            if (lastPid != null && robotLogIsReady(serial, lastPid)) {
                logger.lifecycle("Robot Controller is ready (pid $lastPid).")
                return true
            }
            Thread.sleep(500)
        }

        if (lastPid == null) {
            throw new GradleException(
                "Robot Controller did not start within ${readyTimeoutSeconds.get()} seconds. " +
                'Inspect `adb logcat` and the robot controller log before retrying.')
        }
        logger.warn(
            "Robot Controller process $lastPid started, but no current 'Robot Status: running' marker " +
            "appeared within ${readyTimeoutSeconds.get()} seconds.")
        return false
    }

    private String runningPid(String serial) {
        def pidof = runAdb(serial, ['shell', 'pidof', packageName.get()])
        def pid = pidof.output.trim().split(/\s+/).find { it ==~ /\d+/ }
        if (pidof.success && pid) return pid

        def ps = runAdb(serial, ['shell', 'ps'])
        def line = ps.output.readLines().find { it.trim().endsWith(packageName.get()) }
        if (line == null) return null
        return line.trim().split(/\s+/).find { it ==~ /\d+/ }
    }

    private boolean robotLogIsReady(String serial, String pid) {
        def log = runAdb(serial, ['shell', 'tail', '-n', '250', '/sdcard/robotControllerLog.txt'])
        if (!log.success) {
            log = runAdb(serial, ['shell', 'tail', '-n', '250', '/sdcard/RobotControllerLog.txt'])
        }
        if (!log.success) return false
        def pidPattern = Pattern.compile('(^|\\s)' + Pattern.quote(pid) + '(\\s|$)')
        return log.output.readLines().any {
            it.contains('Robot Status: running') && pidPattern.matcher(it).find()
        }
    }
}
