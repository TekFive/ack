package org.tekfive.ack.configuration

/** Global, ordered registry of the [AckSource]s used to resolve configuration properties. */
object AckRegistry {

    @Volatile
    private var sourceList: List<AckSource> = emptyList()

    /** Registered sources in lookup priority order. */
    val sources: List<AckSource>
        get() = sourceList

    /** Appends [source] (and any [additionalSources]) to the registry in lookup priority order. */
    @Synchronized
    fun addSource(source: AckSource, vararg additionalSources: AckSource) {
        sourceList = sourceList + source + additionalSources.toList()
    }

    /** Clears all registered sources. Typically used in unit-test setup/teardown. */
    @Synchronized
    fun clear(): AckRegistry {
        sourceList = emptyList()
        return this
    }

    @Synchronized
    fun reload() {
        sources.forEach { it.reload() }
    }
}
