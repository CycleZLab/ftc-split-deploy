package dev.splitdeploy

import org.gradle.api.GradleException

/** Small, deterministic parsers kept separate so the dangerous adb paths are testable. */
class SplitDeployParsers {

    static List<Map<String, String>> devices(String output) {
        return output.readLines()
            .collect { it.trim() }
            .findAll { it && !it.startsWith('List of devices') && !it.startsWith('* daemon') }
            .collect { line ->
                def fields = line.split(/\s+/, 3)
                [serial: fields[0], state: fields.length > 1 ? fields[1] : 'unknown', detail: line]
            }
    }

    static String selectDevice(String output, String requestedSerial) {
        def all = devices(output)
        if (requestedSerial) {
            def match = all.find { it.serial == requestedSerial }
            if (match == null) {
                throw new GradleException("Requested adb device '$requestedSerial' is not connected.\n${deviceHelp(all)}")
            }
            if (match.state != 'device') {
                throw new GradleException("adb device '$requestedSerial' is ${match.state}; unlock/authorize it and retry.")
            }
            return requestedSerial
        }

        def ready = all.findAll { it.state == 'device' }
        if (ready.size() == 1) return ready[0].serial
        if (ready.isEmpty()) {
            throw new GradleException("No authorized adb device is connected.\n${deviceHelp(all)}")
        }
        throw new GradleException(
            "Multiple adb devices are connected (${ready*.serial.join(', ')}). " +
            'Set ANDROID_SERIAL or pass -PftcSplitDeploySerial=<serial>.')
    }

    static List<String> packagePaths(String output) {
        return output.readLines()
            .collect { it.trim() }
            .findAll { it.startsWith('package:') }
            .collect { it.substring('package:'.length()) }
    }

    static String basePath(List<String> paths) {
        return paths.find { new File(it).name == 'base.apk' } ?: paths.find { !new File(it).name.startsWith('split_') }
    }

    static String splitPath(List<String> paths, String splitName) {
        def expected = "split_${splitName}.apk"
        return paths.find { new File(it).name == expected } ?:
            paths.find { new File(it).name.toLowerCase().contains(splitName.toLowerCase()) }
    }

    static String sessionId(String output) {
        def match = output =~ /\[(\d+)\]/
        if (match.find()) return match.group(1)
        match = output =~ /(?i)session\s+(\d+)/
        return match.find() ? match.group(1) : null
    }

    static boolean packageManagerSuccess(CommandResult result) {
        return result.exitCode == 0 && result.output.readLines().any {
            def line = it.trim()
            line == 'Success' || line.startsWith('Success:')
        }
    }

    static String safeFileName(String value) {
        return value.replaceAll(/[^A-Za-z0-9._-]/, '_')
    }

    private static String deviceHelp(List<Map<String, String>> devices) {
        if (devices.isEmpty()) return 'Run `adb devices -l`, connect by USB, and authorize this computer.'
        return 'Detected: ' + devices.collect { "${it.serial} (${it.state})" }.join(', ')
    }
}
