package org.tekfive.ack.configuration

import org.tekfive.ack.Ack
import java.util.concurrent.ConcurrentHashMap

/**
 * Catalog of declared [Ack] properties.
 *
 * **Default — self-registration:** every [Ack] registers itself here when constructed, so the catalog
 * reflects all properties that have been instantiated, with no classpath scanning and no extra
 * dependency.
 *
 * **Optional — [scan]:** force-discovers declared properties whose holder class has not been loaded yet
 * (so they have not self-registered). It uses ClassGraph, which this library declares as a `compileOnly`
 * dependency; a consumer that calls [scan] must put ClassGraph on its own classpath. Consumers that rely
 * solely on self-registration never load ClassGraph.
 *
 * The catalog holds the **live** property instances and builds an [AckDescriptor] on demand, so each
 * descriptor's [AckDescriptor.value] / [AckDescriptor.source] reflect the configuration at the time of
 * the read. Because `value` may be a secret, callers must redact secret-bearing properties before
 * displaying a listing.
 */
object AckCatalog {

    private val byKey = ConcurrentHashMap<String, Ack<*>>()

    /** Records [ack], keyed (and deduplicated) by qualified name. Called from [Ack]'s init. */
    internal fun register(ack: Ack<*>) {
        byKey[ack.qualifiedName] = ack
    }

    /**
     * Force-discovers declared [Ack] fields in [packages] via ClassGraph and records them, then returns
     * the full catalog. Requires ClassGraph on the classpath (see the class doc). Note this initializes
     * the config-holding classes it finds, which in turn self-register.
     */
    fun scan(vararg packages: String): List<AckDescriptor> {
        for (ack in AckClassGraphScanner.findDeclaredAcks(packages)) {
            register(ack)
        }
        return all()
    }

    /**
     * All recorded properties as descriptors, sorted by qualified name. Resolves each property's current
     * value and source — which may touch configured sources (including remote ones).
     */
    fun all(): List<AckDescriptor> {
        return byKey.values.map { describe(it) }.sortedBy { it.qualifiedName }
    }

    /** Clears the catalog. Typically used in unit-test setup/teardown. */
    fun clear() {
        byKey.clear()
    }

    private fun describe(ack: Ack<*>): AckDescriptor {
        // Resolution can fail (e.g. a throwing closure default or an unavailable remote source); never
        // let one property break the listing.
        val value = try {
            ack.orNull()
        } catch (e: Throwable) {
            null
        }
        val source = try {
            ack.source()
        } catch (e: Throwable) {
            null
        }
        return AckDescriptor(
            qualifiedName = ack.qualifiedName,
            name = ack.name,
            namespace = ack.namespace,
            description = ack.description,
            type = ack.type,
            hasDefault = ack.hasDefault,
            value = value,
            source = source,
        )
    }
}
