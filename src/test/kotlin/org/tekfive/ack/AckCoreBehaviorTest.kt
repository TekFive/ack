package org.tekfive.ack

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.configuration.AckSource
import org.tekfive.ack.sources.MapSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AckCoreBehaviorTest {

    @BeforeEach
    fun setUp() {
        AckRegistry.clear()
    }

    @AfterEach
    fun tearDown() {
        AckRegistry.clear()
    }

    @Test
    fun optionalPropertyReturnsSourceValueWhenPresent() {
        AckRegistry.addSource(MapSource(mapOf("FEATURE_FLAG" to "true")))

        val property = Ack.boolean("FEATURE_FLAG")

        assertEquals(true, property.orNull())
        assertTrue(property.isDefined)
    }

    @Test
    fun orNullReturnsNullWhenUndefinedAndNoDefault() {
        AckRegistry.addSource(MapSource(emptyMap()))

        assertNull(Ack.string("SERVICE_NAME").orNull())
    }

    @Test
    fun defaultValueIsUsedWhenSourceCannotBeCoerced() {
        AckRegistry.addSource(MapSource(mapOf("RETRY_COUNT" to "NaN")))

        val property = Ack.int("RETRY_COUNT", default = 3)

        assertEquals(3, property())
        assertFalse(property.isDefined)
    }

    @Test
    fun requiredAccessThrowsWhenMissingAndNoDefault() {
        AckRegistry.addSource(MapSource(emptyMap()))

        assertThrows<IllegalStateException> { Ack.boolean("FEATURE_ENABLED")() }
        assertThrows<IllegalStateException> { Ack.double("RATE_LIMIT")() }
        assertThrows<IllegalStateException> { Ack.int("PORT")() }
        assertThrows<IllegalStateException> { Ack.long("TIMEOUT_MS")() }
        assertThrows<IllegalStateException> { Ack.string("SERVICE_NAME")() }
    }

    @Test
    fun closureDefaultIsComputed() {
        AckRegistry.addSource(MapSource(emptyMap()))

        assertEquals("computed", Ack.string("SERVICE_NAME") { "computed" }())
    }

    @Test
    fun fallbackToAnotherAckIsUsedWhenOwnSourcesMiss() {
        AckRegistry.addSource(MapSource(mapOf("FALLBACK_PORT" to "7000")))

        val fallback = Ack.int("FALLBACK_PORT")
        val property = Ack.int("PRIMARY_PORT", fallback = fallback)

        assertEquals(7000, property())
        assertFalse(property.isDefined)
    }

    @Test
    fun minAndMaxClampTheResolvedValue() {
        AckRegistry.addSource(MapSource(mapOf("LOW" to "2", "HIGH" to "20")))

        assertEquals(4, Ack.int("LOW", min = 4)())   // 2 clamped up to the floor
        assertEquals(8, Ack.int("HIGH", max = 8)())  // 20 clamped down to the ceiling
    }

    @Test
    fun sourceFailureFallsBackToNextSource() {
        val failingSource = object : AckSource {
            override fun get(name: String, namespace: String?): String {
                throw IllegalStateException("source unavailable")
            }

            override fun reload() {}
        }

        AckRegistry.addSource(failingSource, MapSource(mapOf("APP_NAME" to "fallback-app")))

        assertEquals("fallback-app", Ack.string("APP_NAME")())
    }

    @Test
    fun sourceReportsWhichSourceProvidedTheValue() {
        val primary = MapSource(emptyMap())
        val secondary = MapSource(mapOf("APP_NAME" to "from-secondary"))
        AckRegistry.addSource(primary, secondary)

        val property = Ack.string("APP_NAME")

        assertEquals("from-secondary", property())
        assertSame(secondary, property.source())
    }

    @Test
    fun sourceIsNullWhenValueComesFromDefault() {
        AckRegistry.addSource(MapSource(emptyMap()))

        val property = Ack.int("PORT", default = 8080)

        assertEquals(8080, property())
        assertNull(property.source())
    }

    @Test
    fun namespacedPropertyResolvesNamespacedValueFirst() {
        AckRegistry.addSource(MapSource(mapOf("MYAPP_PORT" to "9090", "PORT" to "8080")))

        val property = Ack.int("PORT", default = 3000, namespace = "MYAPP")

        assertEquals(9090, property())
        assertEquals("MYAPP_PORT", property.qualifiedName)
    }

    @Test
    fun namespacedPropertyFallsBackToUnprefixedName() {
        AckRegistry.addSource(MapSource(mapOf("PORT" to "8080")))

        assertEquals(8080, Ack.int("PORT", default = 3000, namespace = "MYAPP")())
    }

    @Test
    fun namespacedPropertyFallsBackToDefault() {
        AckRegistry.addSource(MapSource(emptyMap()))

        assertEquals(3000, Ack.int("PORT", default = 3000, namespace = "MYAPP")())
    }

    @Test
    fun noNamespaceResolvesDirectly() {
        AckRegistry.addSource(MapSource(mapOf("PORT" to "8080")))

        assertEquals(8080, Ack.int("PORT", default = 3000)())
    }
}
