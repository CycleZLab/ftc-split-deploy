package dev.splitdeploy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

class SplitDeployStateTest {

    @TempDir
    Path temporaryDirectory

    @Test
    void roundTripsDeviceStateAndReplacesOldValues() {
        def file = temporaryDirectory.resolve('nested/device.properties').toFile()
        def first = new Properties()
        first.setProperty('baseFingerprint', 'one')
        SplitDeployState.save(file, first)

        assertEquals('one', SplitDeployState.load(file).getProperty('baseFingerprint'))

        def second = new Properties()
        second.setProperty('baseFingerprint', 'two')
        SplitDeployState.save(file, second)
        assertEquals('two', SplitDeployState.load(file).getProperty('baseFingerprint'))
        assertFalse(new File(file.parentFile, file.name + '.tmp').exists())
    }

    @Test
    void hashesFilesDeterministically() {
        def file = temporaryDirectory.resolve('value.txt').toFile()
        file.text = 'abc'
        assertEquals('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
            FileHashing.sha256(file))
    }
}
