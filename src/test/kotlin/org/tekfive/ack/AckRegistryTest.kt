package org.tekfive.ack

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AckRegistryTest {

    @BeforeEach
    fun setUp() {
        AckRegistry.clear()
    }

    @AfterEach
    fun tearDown() {
        AckRegistry.clear()
    }

    @Test
    fun addSourceRegistersSourcesInPriorityOrder() {
        val first = MapSource(mapOf("A" to "1"))
        val second = MapSource(mapOf("B" to "2"))

        AckRegistry.addSource(first, second)

        assertEquals(listOf(first, second), AckRegistry.sources)
    }

    @Test
    fun earlierSourcesTakePrecedence() {
        AckRegistry.addSource(MapSource(mapOf("A" to "first")))
        AckRegistry.addSource(MapSource(mapOf("A" to "second")))

        assertEquals("first", Ack.string("A")())
    }

    @Test
    fun clearRemovesAllSources() {
        AckRegistry.addSource(MapSource(mapOf("A" to "1")))

        AckRegistry.clear()

        assertTrue(AckRegistry.sources.isEmpty())
    }
}
