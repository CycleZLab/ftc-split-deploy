package dev.splitdeploy

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class VersionConsistencyTest {

    @Test
    void codeDocumentationAndPublishedVersionStayAligned() {
        def version = SplitDeployPluginVersion.CURRENT
        assertEquals(version, System.getProperty('splitDeployBuildVersion'))

        def root = new File(System.getProperty('user.dir'))
        assertTrue(new File(root, 'README.md').text.contains("id 'ftc.splitdeploy' version '$version'"))
        assertTrue(new File(root, 'pages/index.html').text.contains("id 'ftc.splitdeploy' version '$version'"))
        assertTrue(new File(root, 'CHANGELOG.md').text.contains("## $version"))
    }
}
