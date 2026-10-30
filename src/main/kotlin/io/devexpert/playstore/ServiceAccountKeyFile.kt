package io.devexpert.playstore

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Startup check for the service account key: it must exist and be readable by its owner only.
 */
object ServiceAccountKeyFile {
    private val LOOSE_PERMISSIONS = setOf(
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE
    )

    fun validate(path: String): String {
        val file = File(path)
        check(file.isFile) { "Service account key file not found" }
        try {
            val permissions = Files.getPosixFilePermissions(file.toPath())
            check(permissions.none { it in LOOSE_PERMISSIONS }) {
                "Service account key file must not be accessible by group or others (run chmod 600)"
            }
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX file system: nothing to check.
        }
        return path
    }
}
