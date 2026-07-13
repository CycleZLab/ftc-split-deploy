package dev.splitdeploy

import groovy.xml.XmlSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

class GenerateRunConfigsTaskTest {

    @TempDir
    Path directory

    @Test
    void writesTwoValidAndroidStudioGradleConfigurations() {
        def project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        def task = project.tasks.register('generateConfigs', GenerateRunConfigsTask).get()
        def output = directory.resolve('.run').toFile()
        task.runDirectory.set(output)

        task.generate()

        def files = output.listFiles().findAll { it.name.endsWith('.run.xml') }
        assertEquals(2, files.size())
        assertEquals(['TeamCode fast deploy.run.xml', 'TeamCode.run.xml'],
            files*.name.sort())
        files.each { file ->
            def xml = new XmlSlurper().parse(file)
            assertEquals('ProjectRunConfigurationManager', xml.@name.text())
            def systemId = xml.configuration.ExternalSystemSettings.option.find {
                it.@name.text() == 'externalSystemIdString'
            }
            assertEquals('GRADLE', systemId.@value.text())
            assertTrue(xml.configuration.ExternalSystemSettings.option.find {
                it.@name.text() == 'taskNames'
            }.list.option.@value.text().startsWith(':'))
        }
    }

    @Test
    void removesStaleConfigurationsFromOlderVersions() {
        def project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        def task = project.tasks.register('generateConfigs', GenerateRunConfigsTask).get()
        def output = directory.resolve('.run').toFile()
        output.mkdirs()
        new File(output, 'Split deploy doctor.run.xml').text = '<component />'
        new File(output, 'TeamCode rollback.run.xml').text = '<component />'
        new File(output, 'Robot full install.run.xml').text = '<component />'
        task.runDirectory.set(output)

        task.generate()

        def names = output.listFiles()*.name.sort()
        assertEquals(['TeamCode fast deploy.run.xml', 'TeamCode.run.xml'], names)
    }

    @Test
    void scrubsAndroidStudioAutoCreatedConfigsFromWorkspaceXml() {
        def project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        def task = project.tasks.register('generateConfigs', GenerateRunConfigsTask).get()
        task.runDirectory.set(directory.resolve('.run').toFile())
        def workspace = directory.resolve('.idea/workspace.xml').toFile()
        workspace.parentFile.mkdirs()
        workspace.text = '''<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="RunManager" selected="Android App.TeamCode">
    <configuration name="TeamCode" type="AndroidRunConfigurationType" factoryName="Android App">
      <module name="Project.TeamCode" />
      <method v="2" />
    </configuration>
    <configuration name="FtcBase" type="AndroidRunConfigurationType" factoryName="Android App">
      <module name="Project.FtcBase" />
    </configuration>
    <list>
      <item itemvalue="Android App.TeamCode" />
      <item itemvalue="Gradle.TeamCode fast deploy" />
    </list>
  </component>
  <component name="TaskManager">
    <task active="true" id="Default" summary="Default task" />
  </component>
</project>
'''
        task.ideaWorkspaceXml.set(workspace)

        task.generate()

        def xml = new XmlSlurper().parse(workspace)
        def runManager = xml.component.find { it.@name == 'RunManager' }
        assertEquals(0, runManager.configuration.size())
        assertEquals('Gradle.TeamCode fast deploy', runManager.@selected.text())
        def items = runManager.list.item*.@itemvalue*.text()
        assertEquals(['Gradle.TeamCode fast deploy'], items)
        // Unrelated components survive untouched.
        assertEquals(1, xml.component.findAll { it.@name == 'TaskManager' }.size())
    }

    @Test
    void leavesWorkspaceXmlAloneWhenNothingToScrub() {
        def project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        def task = project.tasks.register('generateConfigs', GenerateRunConfigsTask).get()
        task.runDirectory.set(directory.resolve('.run').toFile())
        def workspace = directory.resolve('.idea/workspace.xml').toFile()
        workspace.parentFile.mkdirs()
        def original = '''<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="RunManager" selected="Gradle.TeamCode fast deploy">
    <list>
      <item itemvalue="Gradle.TeamCode fast deploy" />
    </list>
  </component>
</project>
'''
        workspace.text = original
        task.ideaWorkspaceXml.set(workspace)

        task.generate()

        assertEquals(original, workspace.text)
    }
}
