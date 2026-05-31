package org.tekfive.ack.sources

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.tekfive.ack.sources.single.FileSingleSource
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileSingleSourceTest {

    @Test
    fun returnsContentForMatchingPropertyName() {
        val file = Files.createTempFile("ack", ".txt").toFile()
        file.writeText("secret-value")
        file.deleteOnExit()

        val source = FileSingleSource("SECRET", file.absolutePath)

        assertEquals("secret-value", source["SECRET"])
    }

    @Test
    fun returnsNullForNonMatchingPropertyName() {
        val file = Files.createTempFile("ack", ".txt").toFile()
        file.writeText("secret-value")
        file.deleteOnExit()

        val source = FileSingleSource("SECRET", file.absolutePath)

        assertNull(source["OTHER"])
    }

    @Test
    fun throwsIfPathIsNotAFile() {
        val missingPath = "${System.getProperty("java.io.tmpdir")}/ack-missing-${System.nanoTime()}"

        assertThrows<IllegalArgumentException> {
            FileSingleSource("SECRET", missingPath)
        }
    }
}
