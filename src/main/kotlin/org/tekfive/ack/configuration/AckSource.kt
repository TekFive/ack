package org.tekfive.ack.configuration

/** Source of raw string configuration values. */
interface AckSource {
    /**
     * Returns the raw value for the [name] within [namespace], or null when the source does not provide it.
     *
     * Implementations match exactly: a non-null [namespace] requests the namespaced entry only, a null
     * [namespace] requests the bare entry only. The namespaced-then-bare fallback is orchestrated by
     * [org.tekfive.ack.Ack], which calls this twice. Flat key/value sources interpret a namespaced lookup
     * as the concatenated key produced by [ackKey].
     */
    operator fun get(name: String, namespace: String? = null): String?

    /** Short, secret-safe label identifying this source for diagnostics (never includes values). */
    fun describe(): String {
        return javaClass.simpleName
    }

    fun reload()
}

/** The flat key a namespaced property resolves to in a key/value source: `namespace_name`, or `name` when unprefixed. */
internal fun ackKey(name: String, namespace: String?): String {
    return if (namespace.isNullOrBlank()) name else "${namespace}_$name"
}
