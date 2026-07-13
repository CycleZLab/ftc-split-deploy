package dev.splitdeploy

import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SplitDeployState {
    static Properties load(File file) {
        def properties = new Properties()
        if (file.exists()) {
            file.withInputStream { properties.load(it) }
        }
        return properties
    }

    static void save(File file, Properties properties) {
        file.parentFile.mkdirs()
        def temporary = new File(file.parentFile, "${file.name}.tmp")
        temporary.withOutputStream { properties.store(it, 'Managed by ftc-split-deploy') }
        try {
            Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
