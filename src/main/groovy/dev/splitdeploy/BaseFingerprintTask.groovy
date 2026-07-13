package dev.splitdeploy

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import java.security.MessageDigest

/** Fingerprints everything that determines base/split binary compatibility. */
abstract class BaseFingerprintTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getBaseInputs()

    @Input
    abstract Property<String> getPluginVersion()

    @Internal
    abstract DirectoryProperty getRootDirectory()

    @OutputFile
    abstract RegularFileProperty getFingerprintFile()

    @TaskAction
    void generate() {
        def root = rootDirectory.get().asFile.toPath()
        def digest = MessageDigest.getInstance('SHA-256')
        digest.update("ftc-split-deploy:${pluginVersion.get()}\n".getBytes('UTF-8'))

        baseInputs.files.findAll { it.isFile() }.sort { a, b ->
            BaseFingerprintTask.normalizedPath(root, a) <=> BaseFingerprintTask.normalizedPath(root, b)
        }.each { file ->
            digest.update(BaseFingerprintTask.normalizedPath(root, file).getBytes('UTF-8'))
            digest.update((byte) 0)
            file.withInputStream { input ->
                byte[] buffer = new byte[64 * 1024]
                for (int count = input.read(buffer); count >= 0; count = input.read(buffer)) {
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            digest.update((byte) 0)
        }

        def output = fingerprintFile.get().asFile
        output.parentFile.mkdirs()
        output.text = digest.digest().encodeHex().toString() + '\n'
    }

    static String normalizedPath(java.nio.file.Path root, File file) {
        def path = file.toPath().toAbsolutePath().normalize()
        try {
            return root.toAbsolutePath().normalize().relativize(path).toString().replace(File.separatorChar, '/' as char)
        } catch (IllegalArgumentException ignored) {
            // External Maven artifacts are outside the checkout. Their stable
            // cache-relative tail plus their content is sufficient here.
            return file.name
        }
    }
}
