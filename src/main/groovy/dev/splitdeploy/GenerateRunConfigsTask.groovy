package dev.splitdeploy

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateRunConfigsTask extends DefaultTask {

    @OutputDirectory
    abstract DirectoryProperty getRunDirectory()

    @TaskAction
    void generate() {
        def directory = runDirectory.get().asFile
        directory.mkdirs()
        new File(directory, 'TeamCode fast deploy.run.xml').text =
            gradleRunConfig('TeamCode fast deploy', ':TeamCode:deployTeamCode')
        new File(directory, 'Robot full install.run.xml').text =
            gradleRunConfig('Robot full install', ':FtcBase:installFullApp')
        // Stale configs from older plugin versions (doctor/rollback are still
        // available as Gradle tasks, just not as dropdown entries).
        ['Split deploy doctor.run.xml', 'TeamCode rollback.run.xml'].each {
            new File(directory, it).delete()
        }
        logger.lifecycle('Wrote .run/ configurations. Reload the project to see them in Android Studio.')
    }

    private static String gradleRunConfig(String name, String task) {
        return """\
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="${name}" type="GradleRunConfiguration" factoryName="Gradle">
    <ExternalSystemSettings>
      <option name="executionName" />
      <option name="externalProjectPath" value="\$PROJECT_DIR\$" />
      <option name="externalSystemIdString" value="GRADLE" />
      <option name="scriptParameters" value="" />
      <option name="taskDescriptions"><list /></option>
      <option name="taskNames"><list><option value="${task}" /></list></option>
      <option name="vmOptions" value="" />
    </ExternalSystemSettings>
    <ExternalSystemDebugServerProcess>false</ExternalSystemDebugServerProcess>
    <ExternalSystemReattachDebugProcess>true</ExternalSystemReattachDebugProcess>
    <DebugAllEnabled>false</DebugAllEnabled>
    <RunAsTest>false</RunAsTest>
    <method v="2" />
  </configuration>
</component>
"""
    }
}
