package it.palsoftware.pastiera.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipHelperTest {

    private val smallLimits = ZipHelper.ExtractionLimits(
        maxEntries = 3,
        maxEntryBytes = 8,
        maxTotalBytes = 12
    )

    @Test
    fun unzip_writesNestedEntryInsideTargetDirectory() = withTempDirectory { tempDir ->
        val targetDir = File(tempDir, "restore")

        ZipHelper.unzip(
            ByteArrayInputStream(zipOf("prefs/nested/settings.json" to "restored")),
            targetDir
        )

        assertEquals("restored", File(targetDir, "prefs/nested/settings.json").readText())
    }

    @Test
    fun unzip_rejectsSiblingDirectoryWithSharedPathPrefix() = withTempDirectory { tempDir ->
        val targetDir = File(tempDir, "restore")
        val siblingFile = File(tempDir, "restore-evil/escaped.txt")

        assertThrows(IllegalStateException::class.java) {
            ZipHelper.unzip(
                ByteArrayInputStream(zipOf("../restore-evil/escaped.txt" to "escaped")),
                targetDir
            )
        }

        assertFalse(siblingFile.exists())
    }

    @Test
    fun unzip_rejectsAbsoluteEntry() = withTempDirectory { tempDir ->
        val targetDir = File(tempDir, "restore")
        val outsideFile = File(tempDir, "absolute-escaped.txt")

        assertThrows(IllegalStateException::class.java) {
            ZipHelper.unzip(
                ByteArrayInputStream(zipOf(outsideFile.absolutePath to "escaped")),
                targetDir
            )
        }

        assertFalse(outsideFile.exists())
    }

    @Test
    fun unzip_rejectsEntryAboveUncompressedSizeLimit() = withTempDirectory { tempDir ->
        assertThrows(IllegalStateException::class.java) {
            ZipHelper.unzipWithLimits(
                ByteArrayInputStream(zipOf("large.bin" to "123456789")),
                File(tempDir, "restore"),
                smallLimits
            )
        }
    }

    @Test
    fun unzip_rejectsArchiveAboveTotalUncompressedSizeLimit() = withTempDirectory { tempDir ->
        assertThrows(IllegalStateException::class.java) {
            ZipHelper.unzipWithLimits(
                ByteArrayInputStream(
                    zipOf(
                        "first.bin" to "1234567",
                        "second.bin" to "7654321"
                    )
                ),
                File(tempDir, "restore"),
                smallLimits
            )
        }
    }

    @Test
    fun unzip_rejectsArchiveAboveEntryCountLimit() = withTempDirectory { tempDir ->
        assertThrows(IllegalStateException::class.java) {
            ZipHelper.unzipWithLimits(
                ByteArrayInputStream(
                    zipOf(
                        "one" to "",
                        "two" to "",
                        "three" to "",
                        "four" to ""
                    )
                ),
                File(tempDir, "restore"),
                smallLimits
            )
        }
    }

    @Test
    fun unzip_acceptsNestedArchiveWithinAllLimits() = withTempDirectory { tempDir ->
        val targetDir = File(tempDir, "restore")

        ZipHelper.unzipWithLimits(
            ByteArrayInputStream(
                zipOf(
                    "prefs/settings.json" to "12345",
                    "files/layout.json" to "67890"
                )
            ),
            targetDir,
            smallLimits
        )

        assertEquals("12345", File(targetDir, "prefs/settings.json").readText())
        assertEquals("67890", File(targetDir, "files/layout.json").readText())
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val tempDir = Files.createTempDirectory("zip-helper-test").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
