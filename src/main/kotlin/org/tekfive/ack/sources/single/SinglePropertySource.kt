package org.tekfive.ack.sources.single

import org.tekfive.ack.configuration.AckSource
import org.tekfive.ack.configuration.ackKey

/**
 * ACK source that exposes exactly one property.
 *
 * @property propertyName property name exposed by this source.
 * @property propertyValueSource supplier used to lazily load the property value.
 */
open class SinglePropertySource(
    val propertyName: String,
    val propertyValueSource: () -> String
) : AckSource {

    /** Lazily resolved property value. */
    val content: String by lazy { propertyValueSource() }

    /** Creates a single-property source with a constant [propertyValue]. */
    constructor(propertyName: String, propertyValue: String) : this(propertyName, { propertyValue })

    /** Returns [content] when the (namespaced) key matches [propertyName], otherwise null. */
    override fun get(name: String, namespace: String?): String? {
        return if (ackKey(name, namespace) == propertyName) {
            content
        } else {
            null
        }
    }

    override fun reload() {}
}
