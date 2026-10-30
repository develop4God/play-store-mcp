package io.devexpert.playstore

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServiceAccountKeyFileTest {
    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = tempFiles.forEach { it.delete() }

    private fun keyFile(permissions: String): File =
        Files.createTempFile("key", ".json").toFile().also {
            tempFiles += it
            Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString(permissions))
        }

    @Test
    fun `owner only key file is accepted`() {
        val file = keyFile("rw-------")

        assertEquals(file.path, ServiceAccountKeyFile.validate(file.path))
    }

    @Test
    fun `missing key file is rejected`() {
        assertFailsWith<IllegalStateException> { ServiceAccountKeyFile.validate("/nonexistent/key.json") }
    }

    @Test
    fun `group or world readable key file is rejected`() {
        listOf("rw-r-----", "rw-r--r--", "rw----r--").forEach { permissions ->
            assertFailsWith<IllegalStateException>(permissions) {
                ServiceAccountKeyFile.validate(keyFile(permissions).path)
            }
        }
    }
}
