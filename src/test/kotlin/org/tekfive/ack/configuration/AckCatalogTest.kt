package org.tekfive.ack.configuration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.tekfive.ack.Ack
import org.tekfive.ack.AckType
import org.tekfive.ack.sources.MapSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AckCatalogTest {

    @BeforeEach
    fun setUp() {
        AckCatalog.clear()
        AckRegistry.clear()
    }

    @AfterEach
    fun tearDown() {
        AckCatalog.clear()
        AckRegistry.clear()
    }

    @Test
    fun constructingAnAckSelfRegistersWithoutScanning() {
        Ack.int("SELF_REGISTERED_PROP", default = 1, description = "self-reg")

        val descriptor = AckCatalog.all().firstOrNull { it.qualifiedName == "SELF_REGISTERED_PROP" }
        assertNotNull(descriptor)
        assertEquals(AckType.INT, descriptor.type)
        assertEquals("self-reg", descriptor.description)
    }

    @Test
    fun descriptorCarriesCurrentValueAndSource() {
        val source = MapSource(mapOf("CATALOG_VALUE_PROP" to "42"))
        AckRegistry.addSource(source)
        Ack.int("CATALOG_VALUE_PROP")

        val descriptor = AckCatalog.all().first { it.qualifiedName == "CATALOG_VALUE_PROP" }
        assertEquals(42, descriptor.value)
        assertSame(source, descriptor.source)
    }

    @Test
    fun secretFactoryTagsTheSecretType() {
        Ack.secret("CATALOG_SECRET_PROP", description = "an API key")

        val descriptor = AckCatalog.all().first { it.qualifiedName == "CATALOG_SECRET_PROP" }
        assertEquals(AckType.SECRET, descriptor.type)
    }

    @Test
    fun descriptorValueComesFromDefaultWithNullSource() {
        Ack.int("CATALOG_DEFAULT_PROP", default = 7)

        val descriptor = AckCatalog.all().first { it.qualifiedName == "CATALOG_DEFAULT_PROP" }
        assertEquals(7, descriptor.value)
        assertNull(descriptor.source)
    }

    @Test
    fun undefinedPropertyHasNullValueAndSource() {
        Ack.string("CATALOG_UNDEFINED_PROP")

        val descriptor = AckCatalog.all().first { it.qualifiedName == "CATALOG_UNDEFINED_PROP" }
        assertNull(descriptor.value)
        assertNull(descriptor.source)
    }

    @Test
    fun scanDiscoversDeclaredPropertiesWithMetadata() {
        val descriptors = AckCatalog.scan("org.tekfive.ack.configuration")

        val flag = descriptors.firstOrNull { it.qualifiedName == "CATALOG_TEST_FLAG" }
        assertNotNull(flag)
        assertEquals(AckType.BOOLEAN, flag.type)
        assertTrue(flag.hasDefault)
        assertEquals("Enables the catalog test feature", flag.description)

        val namespaced = descriptors.firstOrNull { it.qualifiedName == "CATALOG_POLL" }
        assertNotNull(namespaced)
        assertEquals("CATALOG", namespaced.namespace)
        assertEquals("POLL", namespaced.name)
    }

    /** Fixture: a config-holder object the scan should discover via field reflection. */
    object Fixture {
        val flag = Ack.boolean("CATALOG_TEST_FLAG", default = false, description = "Enables the catalog test feature")
        val poll = Ack.int("POLL", default = 5, namespace = "CATALOG")
        val secret = Ack.string("CATALOG_TEST_SECRET")
    }
}
