package dev.splitdeploy

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Configures ':TeamCode' as a dynamic-feature module so all team code builds
 * into a small split APK, and registers:
 *
 *   deployTeamCode  — build + partially install ONLY the split, restart the
 *                     RC app. This is the fast everyday deploy (~seconds).
 *   initSplitDeploy — writes shared Android Studio run configurations (.run/).
 *
 * Team code, OpMode annotations, assets etc. need no changes: the FTC SDK's
 * ClassManager scans split APK dexes natively (via ApplicationInfo.splitSourceDirs).
 */
class TeamCodeFeaturePlugin implements Plugin<Project> {

    @Override
    void apply(Project p) {
        p.pluginManager.apply('com.android.dynamic-feature')
        def android = p.extensions.getByName('android')

        // Default; a team's own android { namespace = ... } overrides this.
        android.namespace = 'org.firstinspires.ftc.teamcode'

        SplitDeployShared.configureSigning(p, android)
        SplitDeployShared.configureCommon(p, android)

        p.dependencies.add('implementation', p.project(':FtcBase'))

        registerDeployTeamCode(p)
        registerInitSplitDeploy(p)
    }

    private static void registerDeployTeamCode(Project p) {
        p.tasks.register('deployTeamCode') { t ->
            t.group = 'ftc'
            t.description = 'Fast deploy: builds and partially installs only the TeamCode split APK, then restarts the RC app.'
            t.dependsOn('assembleDebug')
            t.doLast {
                def adbExe = SplitDeployShared.adbPath(p)
                def pkg = SplitDeployShared.PACKAGE
                def apk = p.layout.buildDirectory.file('outputs/apk/debug/TeamCode-debug.apk').get().asFile

                if (!SplitDeployShared.hasSplitInstall(p, adbExe)) {
                    throw new GradleException(
                        "$pkg is not installed as base+TeamCode splits on the connected device.\n" +
                        "Run the installFullApp task once first (./gradlew installFullApp).")
                }

                SplitDeployShared.adb(p, adbExe, ['push', apk.absolutePath, SplitDeployShared.REMOTE_TMP])

                def created = SplitDeployShared.adb(p, adbExe,
                        ['shell', 'pm', 'install-create', '-r', '-t', '-p', pkg])
                def m = created =~ /\[(\d+)\]/
                if (!m.find()) throw new GradleException("pm install-create failed: $created")
                def session = m.group(1)

                SplitDeployShared.adb(p, adbExe,
                        ['shell', 'pm', 'install-write', session, 'TeamCode', SplitDeployShared.REMOTE_TMP])
                def commit = SplitDeployShared.adb(p, adbExe, ['shell', 'pm', 'install-commit', session])
                if (!commit.contains('Success')) {
                    throw new GradleException("pm install-commit failed: $commit")
                }

                SplitDeployShared.adb(p, adbExe, ['shell', 'rm', '-f', SplitDeployShared.REMOTE_TMP], true)
                SplitDeployShared.restartApp(p, adbExe)
                p.logger.lifecycle('TeamCode split deployed and Robot Controller restarted.')
            }
        }
    }

    private static void registerInitSplitDeploy(Project p) {
        p.tasks.register('initSplitDeploy') { t ->
            t.group = 'ftc'
            t.description = 'Writes Android Studio run configurations (.run/) for the split-deploy workflow.'
            t.doLast {
                def runDir = new File(p.rootProject.rootDir, '.run')
                runDir.mkdirs()
                new File(runDir, 'TeamCode fast deploy.run.xml').text =
                        gradleRunConfig('TeamCode fast deploy', ':TeamCode:deployTeamCode')
                new File(runDir, 'Robot full install.run.xml').text =
                        gradleRunConfig('Robot full install', ':FtcBase:installFullApp')
                p.logger.lifecycle('Wrote .run/ configurations. They appear in the Android Studio run dropdown after a project reload.')
            }
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
      <option name="taskDescriptions">
        <list />
      </option>
      <option name="taskNames">
        <list>
          <option value="${task}" />
        </list>
      </option>
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
