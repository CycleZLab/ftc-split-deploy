package dev.splitdeploy

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

class AdbTaskSafetyTest {

    @TempDir
    Path directory

    @Test
    void abandonsFailedPartialInstallSessionAndRemovesRemoteApk() {
        def log = directory.resolve('adb.log').toFile()
        def adb = fakeAdb(log, 'USB123', '''\
  *"install-create"*) echo "Success: created install session [42]" ;;
  *"install-write"*) echo "Failure [INSTALL_FAILED_INVALID_APK]" ;;
''')
        def apk = directory.resolve('TeamCode.apk').toFile()
        apk.text = 'split bytes'

        def project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        def task = project.tasks.register('exercisePartialInstall', ExposedAdbTask).get()
        configure(task, adb)

        def error = assertThrows(GradleException) { task.installSplit('USB123', apk) }
        assertTrue(error.message.contains('install-write failed'))
        def commands = log.readLines()
        assertTrue(commands.any { it.contains('install-abandon 42') })
        assertTrue(commands.any { it.contains('shell rm -f /data/local/tmp/teamcode.') })
    }

    @Test
    void failedFullInstallNeverUninstallsWithoutExplicitPermission() {
        def log = directory.resolve('adb.log').toFile()
        def adb = fakeAdb(log, 'USB123', '''\
  *"shell pm path"*) echo "package:/data/app/pkg/base.apk" ;;
  *"install-multiple"*) echo "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]" ;;
''')
        def task = fullInstallTask(adb, false)

        def error = assertThrows(GradleException) { task.install() }
        assertTrue(error.message.contains('plugin did NOT uninstall'))
        assertFalse(log.readLines().any { it.contains(' uninstall ') })
    }

    @Test
    void explicitFallbackStillRefusesToUninstallOverNetworkAdb() {
        def log = directory.resolve('adb.log').toFile()
        def adb = fakeAdb(log, '192.168.43.1:5555', '''\
  *"shell pm path"*) echo "package:/data/app/pkg/base.apk" ;;
  *"install-multiple"*) echo "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]" ;;
''')
        def task = fullInstallTask(adb, true)

        def error = assertThrows(GradleException) { task.install() }
        assertTrue(error.message.contains('Refusing to uninstall'))
        assertFalse(log.readLines().any { it.contains(' uninstall ') })
    }

    @Test
    void fullInstallAlsoBlocksRepoAndOnBotJavaDuplicates() {
        def log = directory.resolve('adb.log').toFile()
        def adb = fakeAdb(log, 'USB123', '', '''\
  *"/sdcard/FIRST/java/src"*)
    echo "/sdcard/FIRST/java/src/org/firstinspires/ftc/teamcode/SplitDeployTest.java"
    ;;
''')
        def source = directory.resolve('SplitDeployTest.java').toFile()
        source.text = 'package org.firstinspires.ftc.teamcode; class SplitDeployTest {}'
        def task = fullInstallTask(adb, false)
        task.teamCodeSources.from(source)

        def error = assertThrows(GradleException) { task.install() }
        assertTrue(error.message.contains('TeamCode and OnBot Java both define'))
        assertFalse(log.readLines().any { it.contains('install-multiple') })
    }

    private InstallFullAppTask fullInstallTask(File adb, boolean allowUninstall) {
        def project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        def task = project.tasks.register('fullInstall' + UUID.randomUUID(), InstallFullAppTask).get()
        configure(task, adb)

        def base = directory.resolve('base.apk').toFile()
        def split = directory.resolve('split.apk').toFile()
        def fingerprint = directory.resolve('fingerprint.txt').toFile()
        base.text = 'base'
        split.text = 'split'
        fingerprint.text = 'fingerprint'
        task.baseApk.set(base)
        task.splitApk.set(split)
        task.baseFingerprintFile.set(fingerprint)
        task.allowUninstall.set(allowUninstall)
        task.allowOnBotDuplicates.set(false)
        task.teamCodeSources.from([])
        return task
    }

    private void configure(AbstractAdbTask task, File adb) {
        task.adbExecutable.set(adb)
        task.packageName.set('com.qualcomm.ftcrobotcontroller')
        task.splitName.set('TeamCode')
        task.readyTimeoutSeconds.set(5)
        task.stateDirectory.set(directory.resolve('state').toFile())
    }

    private File fakeAdb(
        File log,
        String serial,
        String cases,
        String onBotCase = '''\
  *"/sdcard/FIRST/java/src"*)
    echo "find: /sdcard/FIRST/java/src: No such file or directory"
    exit 1
    ;;
'''
    ) {
        def escapedLog = log.absolutePath.replace("'", "'\\''")
        def script = directory.resolve('fake-adb-' + UUID.randomUUID() + '.sh').toFile()
        script.text = """\
#!/bin/sh
printf '%s\\n' "\$*" >> '$escapedLog'
case "\$*" in
  "devices -l")
    echo "List of devices attached"
    echo "$serial device product:test model:test"
    ;;
$onBotCase
$cases
  *) echo "Success" ;;
esac
"""
        assertTrue(script.setExecutable(true))
        return script
    }
}

abstract class ExposedAdbTask extends AbstractAdbTask {
    void installSplit(String serial, File apk) {
        partialInstall(serial, apk)
    }
}
