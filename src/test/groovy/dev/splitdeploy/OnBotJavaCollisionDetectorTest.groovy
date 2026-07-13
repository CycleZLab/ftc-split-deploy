package dev.splitdeploy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

class OnBotJavaCollisionDetectorTest {

    @TempDir
    Path directory

    @Test
    void findsExactFullyQualifiedClassCollisionsOnly() {
        def duplicate = source('RepoDrive.java', 'package org.firstinspires.ftc.teamcode;\nclass RepoDrive {}')
        def unique = source('OnlyInRepo.kt', 'package org.firstinspires.ftc.teamcode\nclass OnlyInRepo')
        def remote = '''\
/sdcard/FIRST/java/src/org/firstinspires/ftc/teamcode/RepoDrive.java
/sdcard/FIRST/java/src/other/package/OnlyInRepo.java
/sdcard/FIRST/java/build/classes.jar
'''

        assertEquals(['org.firstinspires.ftc.teamcode.RepoDrive',
            'org.firstinspires.ftc.teamcode.OnlyInRepo'] as Set,
            OnBotJavaCollisionDetector.localClasses([duplicate, unique]))
        assertEquals(['org.firstinspires.ftc.teamcode.RepoDrive',
            'other.package.OnlyInRepo'] as Set,
            OnBotJavaCollisionDetector.remoteClasses(remote))
        assertEquals(['org.firstinspires.ftc.teamcode.RepoDrive'] as Set,
            OnBotJavaCollisionDetector.collisions([duplicate, unique], remote))
    }

    @Test
    void handlesDefaultPackageAndWindowsStyleListings() {
        def source = source('Simple.java', 'public class Simple {}')
        def remote = 'C:\\sdcard\\FIRST\\java\\src\\Simple.java\n'
        assertEquals(['Simple'] as Set,
            OnBotJavaCollisionDetector.collisions([source], remote))
    }

    private File source(String name, String body) {
        def file = directory.resolve(name).toFile()
        file.text = body
        return file
    }
}
