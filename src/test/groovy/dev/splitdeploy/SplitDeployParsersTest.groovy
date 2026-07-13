package dev.splitdeploy

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class SplitDeployParsersTest {

    @Test
    void selectsTheOnlyAuthorizedDevice() {
        def output = '''\
List of devices attached
192.168.43.1:5555 device product:ControlHub model:REV_Control_Hub
'''
        assertEquals('192.168.43.1:5555', SplitDeployParsers.selectDevice(output, null))
    }

    @Test
    void reportsUnauthorizedDeviceInsteadOfPretendingTheAppIsMissing() {
        def error = assertThrows(GradleException) {
            SplitDeployParsers.selectDevice('List of devices attached\nABC unauthorized\n', null)
        }
        assertTrue(error.message.contains('No authorized adb device'))
        assertTrue(error.message.contains('ABC (unauthorized)'))
    }

    @Test
    void requiresExplicitSelectionWhenMoreThanOneDeviceIsReady() {
        def output = 'List of devices attached\nUSB123 device\nemulator-5554 device\n'
        def error = assertThrows(GradleException) {
            SplitDeployParsers.selectDevice(output, null)
        }
        assertTrue(error.message.contains('Multiple adb devices'))
        assertEquals('USB123', SplitDeployParsers.selectDevice(output, 'USB123'))
    }

    @Test
    void identifiesBaseAndFeatureFromPmPathWithoutDependingOnDumpsysFormatting() {
        def paths = SplitDeployParsers.packagePaths('''\
package:/data/app/pkg/split_config.en.apk
package:/data/app/pkg/base.apk
package:/data/app/pkg/split_TeamCode.apk
''')
        assertEquals('/data/app/pkg/base.apk', SplitDeployParsers.basePath(paths))
        assertEquals('/data/app/pkg/split_TeamCode.apk',
            SplitDeployParsers.splitPath(paths, 'TeamCode'))
    }

    @Test
    void parsesPackageInstallerSessionsAndSuccess() {
        assertEquals('42', SplitDeployParsers.sessionId('Success: created install session [42]'))
        assertEquals('7', SplitDeployParsers.sessionId('Created session 7'))
        assertTrue(SplitDeployParsers.packageManagerSuccess(
            new CommandResult(0, 'Success: streamed 123 bytes')))
        assertFalse(SplitDeployParsers.packageManagerSuccess(
            new CommandResult(0, 'Failure [INSTALL_FAILED_INVALID_APK]')))
        assertFalse(SplitDeployParsers.packageManagerSuccess(
            new CommandResult(0, 'Successfully copied but not installed')))
    }

    @Test
    void sanitizesSerialForLocalStatePaths() {
        assertEquals('192.168.43.1_5555', SplitDeployParsers.safeFileName('192.168.43.1:5555'))
    }
}
