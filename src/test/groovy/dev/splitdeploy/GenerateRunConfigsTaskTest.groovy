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
    void writesOnlyTheFastDeployGradleConfiguration() {
        def project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        def task = project.tasks.register('generateConfigs', GenerateRunConfigsTask).get()
        def output = directory.resolve('.run').toFile()
        task.runDirectory.set(output)

        task.generate()

        def files = output.listFiles().findAll { it.name.endsWith('.run.xml') }
        assertEquals(['TeamCode fast deploy.run.xml'], files*.name)

        def xml = new XmlSlurper().parse(files[0])
        assertEquals('ProjectRunConfigurationManager', xml.@name.text())
        def systemId = xml.configuration.ExternalSystemSettings.option.find {
            it.@name.text() == 'externalSystemIdString'
        }
        assertEquals('GRADLE', systemId.@value.text())
        assertEquals(':TeamCode:deployTeamCode', xml.configuration.ExternalSystemSettings.option.find {
            it.@name.text() == 'taskNames'
        }.list.option.@value.text())
    }
}
