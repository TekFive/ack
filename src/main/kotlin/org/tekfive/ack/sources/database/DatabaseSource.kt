package org.tekfive.ack.sources.database

import org.slf4j.LoggerFactory
import org.tekfive.ack.configuration.AckSource
import java.sql.Connection

/**
 * An [AckSource] that reads configuration properties from a database table.
 *
 * All rows are loaded in a single query and cached. When [ttlSeconds] is set,
 * the cache expires after that duration and is reloaded on the next [get] call.
 * When [ttlSeconds] is null, the cache is permanent (loaded once).
 *
 * Namespaces are first-class: when [namespaceColumn] is set (the default), rows are keyed by the
 * `(namespace, name)` pair, where a SQL `NULL` namespace is the un-namespaced entry, and [get] matches
 * exactly. Set [namespaceColumn] to `null` for legacy tables without a namespace column — the source
 * then operates in flat mode (every row is un-namespaced; namespaced lookups miss and fall back).
 *
 * Connections are obtained from [connectionProvider] and closed after each load.
 *
 * @param connectionProvider supplies a database connection for each reload.
 * @param tableName table containing configuration properties.
 * @param nameColumn column containing property names.
 * @param valueColumn column containing property values.
 * @param namespaceColumn nullable column containing property namespaces, or null for a table without one.
 * @param ttlSeconds optional cache lifetime in seconds, or null to cache permanently.
 */
class DatabaseSource(
    private val connectionProvider: () -> Connection,
    private val tableName: String = "ack_properties",
    private val nameColumn: String = "name",
    private val valueColumn: String = "value",
    private val namespaceColumn: String? = "namespace",
    private val ttlSeconds: Long? = null,
) : AckSource {

    init {
        requireValidIdentifier(tableName, "tableName")
        requireValidIdentifier(nameColumn, "nameColumn")
        requireValidIdentifier(valueColumn, "valueColumn")
        if (namespaceColumn != null) {
            requireValidIdentifier(namespaceColumn, "namespaceColumn")
        }

        // Reload at initialization to avoid scenario
        reload()
    }

    @Volatile
    private var cache: Map<Key, String>? = null

    @Volatile
    private var loadedAtMillis: Long = 0

    private val lock = Any()

    /** Returns the cached value for the `(namespace, name)` key, reloading the cache when needed. */
    override fun get(name: String, namespace: String?): String? {
        val current = cache
        if (current == null || isStale()) {
            synchronized(lock) {
                if (cache == null || isStale()) {
                    reload()
                }
            }
        }
        return cache?.get(Key(namespace, name))
    }

    override fun describe(): String {
        return "DatabaseSource($tableName)"
    }

    /** Clears the cached database values so the next [get] call reloads them. */
    fun clearCache() {
        synchronized(lock) {
            cache = null
            loadedAtMillis = 0
        }
    }
    @Synchronized
    override fun reload() {
        try {
            connectionProvider().use { connection ->
                // The namespace column is queried last so a legacy table/result set without it simply
                // yields a null namespace (the un-namespaced entry) rather than shifting the columns.
                val sql = if (namespaceColumn == null) {
                    "SELECT $nameColumn, $valueColumn FROM $tableName"
                } else {
                    "SELECT $nameColumn, $valueColumn, $namespaceColumn FROM $tableName"
                }
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rs ->
                        val properties = mutableMapOf<Key, String>()
                        while (rs.next()) {
                            val name = rs.getString(1) ?: continue
                            val value = rs.getString(2) ?: continue
                            val namespace = if (namespaceColumn == null) null else rs.getString(3)
                            properties[Key(namespace, name)] = value
                        }
                        cache = properties
                        loadedAtMillis = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to load properties from table $tableName: ${e.message}")
        }
    }


    private fun isStale(): Boolean {
        if (ttlSeconds == null) return false
        return System.currentTimeMillis() - loadedAtMillis > ttlSeconds * 1000
    }

    private data class Key(val namespace: String?, val name: String)

    /** Validation and logging support shared by all database sources. */
    companion object {
        private val log = LoggerFactory.getLogger(DatabaseSource::class.java)
        private val IDENTIFIER_PATTERN = Regex("[a-zA-Z_][a-zA-Z0-9_]*")

        private fun requireValidIdentifier(value: String, paramName: String) {
            require(IDENTIFIER_PATTERN.matches(value)) {
                "$paramName must be a valid SQL identifier (letters, digits, underscores): '$value'"
            }
        }
    }
}
