package dev.splitdeploy

import groovy.xml.XmlNodePrinter
import groovy.xml.XmlParser
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateRunConfigsTask extends DefaultTask {

    /**
     * Run-configuration list entries Android Studio auto-creates for every
     * Android app module after each sync (there is no IDE setting to stop it).
     * With this plugin the only correct entry points are the two Gradle
     * configurations, so these are scrubbed from the workspace file.
     */
    private static final List<String> IDE_AUTO_CONFIG_TYPES =
        ['AndroidRunConfigurationType', 'AndroidTestRunConfigurationType']
    private static final List<String> IDE_AUTO_ITEM_PREFIXES =
        ['Android App.', 'Android Tests.']
    private static final String SELECTED_CONFIG = 'Gradle.TeamCode fast deploy'

    @OutputDirectory
    abstract DirectoryProperty getRunDirectory()

    @Internal
    abstract RegularFileProperty getIdeaWorkspaceXml()

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
        scrubIdeAutoCreatedConfigs()
    }

    private void scrubIdeAutoCreatedConfigs() {
        def file = ideaWorkspaceXml.getOrNull()?.asFile
        if (file == null || !file.isFile()) {
            return
        }
        try {
            def workspace = new XmlParser().parse(file)
            def runManager = workspace.component.find { it.@name == 'RunManager' }
            if (runManager == null) {
                return
            }
            def staleConfigs = runManager.configuration.findAll {
                it.@type in IDE_AUTO_CONFIG_TYPES
            }
            def staleItems = runManager.depthFirst().findAll { node ->
                node instanceof groovy.util.Node && node.name() == 'item' &&
                    IDE_AUTO_ITEM_PREFIXES.any { node.@itemvalue?.toString()?.startsWith(it) }
            }
            def selected = runManager.@selected?.toString()
            def selectStale = selected != null &&
                IDE_AUTO_ITEM_PREFIXES.any { selected.startsWith(it) }
            if (!staleConfigs && !staleItems && !selectStale) {
                return
            }
            staleConfigs.each { runManager.remove(it) }
            staleItems.each { it.parent().remove(it) }
            if (selectStale || selected == null) {
                runManager.@selected = SELECTED_CONFIG
            }
            def writer = new StringWriter()
            def printer = new XmlNodePrinter(new PrintWriter(writer))
            printer.preserveWhitespace = true
            printer.print(workspace)
            file.text = '<?xml version="1.0" encoding="UTF-8"?>\n' + writer.toString()
            logger.lifecycle("Removed ${staleConfigs.size()} Android Studio auto-created app run configuration(s) from .idea/workspace.xml.")
            logger.lifecycle('If Android Studio is currently open, quit it and re-run initSplitDeploy: the IDE keeps run configurations in memory and writes the old list back on exit.')
        } catch (Exception e) {
            // Never fail the build over IDE bookkeeping.
            logger.warn("Could not clean Android Studio run configurations in ${file}: ${e.message}")
        }
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
