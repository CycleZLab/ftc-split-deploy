package dev.splitdeploy

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class UnsafeDeviceTaskGuardTest {

    @Test
    void classifiesAgpDeviceTasksAsUnsafe() {
        ['installDebug', 'installRelease', 'installDebugAndroidTest', 'uninstallAll',
         'uninstallDebug'].each {
            assertTrue(UnsafeDeviceTaskGuard.isUnsafeDeviceTask(it), it)
        }
        ['installFullApp', 'deployTeamCode', 'assembleDebug', 'splitDeployDoctor',
         'rollbackTeamCode', 'initSplitDeploy'].each {
            assertFalse(UnsafeDeviceTaskGuard.isUnsafeDeviceTask(it), it)
        }
    }

    @Test
    void guardedInstallTaskFailsWithSupportedAlternative() {
        def project = ProjectBuilder.builder().build()
        UnsafeDeviceTaskGuard.guard(project)
        def task = project.tasks.register('installDebug').get()

        def failure = assertThrows(GradleException) {
            task.actions.each { it.execute(task) }
        }
        assertTrue(failure.message.contains('installFullApp'))
        assertNull(task.group)
    }

    @Test
    void pluginTasksAreLeftUntouched() {
        def project = ProjectBuilder.builder().build()
        UnsafeDeviceTaskGuard.guard(project)
        def task = project.tasks.register('installFullApp').get()

        assertTrue(task.actions.isEmpty())
    }
}
