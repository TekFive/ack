package org.tekfive.ack.sources

import org.tekfive.ack.configuration.AckSource
import org.tekfive.ack.configuration.ackKey

/**
 * ACK source backed by an in-memory map. A namespaced lookup resolves to the concatenated
 * `namespace_name` key.
 *
 * @property properties raw property values keyed by (namespaced) property name.
 */
class MapSource(val properties: Map<String, String>) : AckSource {
    /** Returns the map value for the (namespaced) key, or null when absent. */
    override fun get(name: String, namespace: String?): String? {
        return properties[ackKey(name, namespace)]
    }

    override fun reload() {}
}
