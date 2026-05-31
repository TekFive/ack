package org.tekfive.ack.configuration

import org.tekfive.ack.AckType

/**
 * A snapshot of a declared [org.tekfive.ack.Ack] property, built on demand by [AckCatalog] from the live
 * property — so [value] and [source] reflect the configuration **at the moment the catalog is read**.
 *
 * **Security:** unlike the property's metadata, [value] is the *resolved value* and may be a secret
 * (signing keys, database passwords, API keys all flow through ACK). Redact secret-bearing properties
 * before logging, serializing, or displaying a catalog listing.
 *
 * @property qualifiedName the namespaced name (`namespace_name`) or bare name.
 * @property name unqualified property name.
 * @property namespace optional namespace prefix.
 * @property description optional human-readable description.
 * @property type the value type of the property.
 * @property hasDefault whether a fallback default is configured.
 * @property value the currently resolved value (from a source or the default); null when undefined. May be sensitive.
 * @property source the [AckSource] that supplied [value], or null when the value came from the default or is undefined.
 */
data class AckDescriptor(
    val qualifiedName: String,
    val name: String,
    val namespace: String?,
    val description: String?,
    val type: AckType,
    val hasDefault: Boolean,
    val value: Any?,
    val source: AckSource?,
)
