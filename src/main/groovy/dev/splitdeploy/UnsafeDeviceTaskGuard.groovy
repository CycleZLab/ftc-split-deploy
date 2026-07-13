package dev.splitdeploy

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Blocks AGP's raw device tasks (installDebug, installRelease, uninstallAll,
 * ...) on every module. They bypass all split-deploy safety: installDebug
 * replaces the whole app with a base APK that has no TeamCode split, and
 * uninstall tasks can cut the only adb connection to a Control Hub. IDEs and
 * the Gradle tool window list these as runnable entry points, so instead of
 * relying on nobody clicking them, they fail fast with the supported
 * alternative.
 */
class UnsafeDeviceTaskGuard {

    static final String MESSAGE =
        'is disabled by ftc.splitdeploy: it would install/uninstall on the ' +
        'robot without split-deploy safety (no TeamCode split, no ' +
        'compatibility record, no backup). Use ./gradlew installFullApp for ' +
        'a full install or ./gradlew deployTeamCode for a fast deploy.'

    static boolean isUnsafeDeviceTask(String name) {
        if (name == 'installFullApp') {
            return false
        }
        return name.startsWith('install') || name.startsWith('uninstall')
    }

    static void guard(Project project) {
        project.tasks.configureEach { t ->
            if (isUnsafeDeviceTask(t.name)) {
                t.doFirst {
                    throw new GradleException("Task '${t.name}' ${MESSAGE}")
                }
            }
        }
        // AGP assigns the "install" group after our configureEach action runs,
        // so demote these from the grouped `gradlew tasks` listing once every
        // project is fully configured (last configuration action wins).
        project.gradle.projectsEvaluated {
            project.tasks.names.findAll { isUnsafeDeviceTask(it) }.each { name ->
                project.tasks.named(name) { t ->
                    t.group = null
                    t.description = 'Disabled by ftc.splitdeploy - use installFullApp / deployTeamCode.'
                }
            }
        }
    }
}
