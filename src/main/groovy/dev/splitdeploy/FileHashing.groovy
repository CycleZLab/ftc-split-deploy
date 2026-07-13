package dev.splitdeploy

import java.security.MessageDigest

class FileHashing {
    static String sha256(File file) {
        def digest = MessageDigest.getInstance('SHA-256')
        file.withInputStream { input ->
            byte[] buffer = new byte[64 * 1024]
            for (int count = input.read(buffer); count >= 0; count = input.read(buffer)) {
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().encodeHex().toString()
    }
}
