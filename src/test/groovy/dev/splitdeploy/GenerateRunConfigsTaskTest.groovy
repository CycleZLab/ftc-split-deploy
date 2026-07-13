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
        assertEquals(['Robot full install.run.xml', 'TeamCode fast deploy.run.xml'],
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
        task.runDirectory.set(output)

        task.generate()

        def names = output.listFiles()*.name.sort()
        assertEquals(['Robot full install.run.xml', 'TeamCode fast deploy.run.xml'], names)
    }
}
