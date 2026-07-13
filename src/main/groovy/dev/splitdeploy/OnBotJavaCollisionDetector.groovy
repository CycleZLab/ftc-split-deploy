package dev.splitdeploy

/** Maps repo and OnBot Java source files to top-level fully qualified class names. */
class OnBotJavaCollisionDetector {

    static Set<String> localClasses(Collection<File> sources) {
        return sources.findAll { it.isFile() && (it.name.endsWith('.java') || it.name.endsWith('.kt')) }
            .collect { source ->
                def packageMatch = source.getText('UTF-8') =~ /(?m)^\s*package\s+([A-Za-z0-9_$.]+)/
                def simpleName = source.name.substring(0, source.name.lastIndexOf('.'))
                packageMatch.find() ? packageMatch.group(1) + '.' + simpleName : simpleName
            }
            .toSet()
    }

    static Set<String> remoteClasses(String findOutput) {
        return findOutput.readLines()
            .collect { it.trim().replace('\\', '/') }
            .findAll { it.endsWith('.java') && it.contains('/java/src/') }
            .collect { path ->
                def relative = path.substring(path.indexOf('/java/src/') + '/java/src/'.length())
                relative.substring(0, relative.length() - '.java'.length()).replace('/', '.')
            }
            .toSet()
    }

    static Set<String> collisions(Collection<File> localSources, String remoteFindOutput) {
        def local = localClasses(localSources)
        local.retainAll(remoteClasses(remoteFindOutput))
        return local
    }
}
