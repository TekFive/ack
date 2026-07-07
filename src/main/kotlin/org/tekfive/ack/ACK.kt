package org.tekfive.ack

import org.slf4j.LoggerFactory
import org.tekfive.ack.configuration.AckCatalog
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.configuration.AckSource
import kotlin.reflect.KClass

/**
 * Derives an ACK namespace from a class: the simple name converted to UPPER_SNAKE_CASE
 * (e.g. `PreviewFallbackJob` → `PREVIEW_FALLBACK_JOB`). Pass the result as `namespace =` to a factory.
 */
fun ackNamespace(type: KClass<*>): String {
    val simpleName = type.simpleName ?: return ""
    return simpleName.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_").uppercase()
}

/**
 * A single typed configuration property.
 *
 * Resolution walks the sources registered with [AckRegistry] in priority order. For each source the
 * [namespace]-qualified name (`namespace_name`) is tried first, then the bare [name]; the first source
 * value that coerces to a non-null [T] wins. When no source provides a value, [default] (if any) is used.
 *
 * Optionality is expressed by which accessor you call, not by the type:
 * - [invoke] returns a non-null [T] or throws (use for required config).
 * - [orNull] returns the value or null (use for optional config).
 *
 * Construct via the typed factories on the companion ([string], [int], [long], [double], [boolean]),
 * each of which accepts a default as a constant, another [Ack] (`fallback`), or a closure.
 *
 * @property name unqualified property name resolved from sources.
 * @property namespace optional prefix; the `namespace_name` form is tried before [name].
 * @property description optional human-readable description, surfaced by [org.tekfive.ack.configuration.AckCatalog].
 * @property type the value type of this property.
 */
class Ack<T> private constructor(
    val name: String,
    val namespace: String?,
    val description: String?,
    val type: AckType,
    private val coerce: (String) -> T?,
    private val default: (() -> T?)?,
) {
    init {
        // Self-register so [AckCatalog] knows every property that has been constructed, without any
        // classpath scanning. Catalog use is opt-in; registration is cheap and deduplicated by name.
        AckCatalog.register(this)
    }

    /** Property name after applying the optional [namespace] prefix. */
    val qualifiedName: String
        get() {
            return if (namespace.isNullOrBlank()) name else "${namespace}_$name"
        }

    /** Whether a fallback default was configured (constant, another [Ack], or a closure). */
    val hasDefault: Boolean
        get() = default != null

    /** Resolves the value, or throws when neither a source nor a default provides one. */
    operator fun invoke(): T {
        return orNull() ?: throw IllegalStateException("Application property '$qualifiedName' is not defined.")
    }

    /** Resolves the value from sources, falling back to the default; may return null. */
    fun orNull(): T? {
        return resolve()?.value ?: default?.invoke()
    }

    /**
     * The [AckSource] that provided the resolved value, or null when the value came from the default
     * or could not be resolved at all. Does not apply the default — a default-supplied value has no source.
     */
    fun source(): AckSource? {
        return resolve()?.source
    }

    /** Whether a non-null value can be resolved from a source (ignores the default). */
    val isDefined: Boolean
        get() {
            return resolve() != null
        }

    /** Throws when this property cannot be resolved from a source (ignores the default). */
    fun checkDefined() {
        check(isDefined) { "Application property '$qualifiedName' is not defined." }
    }

    private fun resolve(): Resolution<T>? {
        for (source in AckRegistry.sources) {
            if (!namespace.isNullOrBlank()) {
                val namespaced = read(source, namespace)
                if (namespaced != null) {
                    return Resolution(namespaced, source)
                }
            }
            val bare = read(source, null)
            if (bare != null) {
                return Resolution(bare, source)
            }
        }
        return null
    }

    private fun read(source: AckSource, ns: String?): T? {
        val raw = try {
            source.get(name, ns)
        } catch (e: Exception) {
            log.warn("ACK source {} failed to resolve {}: {}", source.describe(), name, e.message)
            return null
        }
        // Blank counts as unset: compose files commonly pass `${VAR:-}` through to
        // the container, which yields an empty string for unset host config. An
        // empty string must fall back to the default, not reach coerce() (where
        // "".toDouble() and friends would throw).
        if (raw == null || raw.isBlank()) {
            return null
        }
        return coerce(raw)
    }

    override fun toString(): String {
        return qualifiedName
    }

    private class Resolution<T>(val value: T, val source: AckSource)

    companion object {
        private val log = LoggerFactory.getLogger(Ack::class.java)

        // ── String ──────────────────────────────────────────────────────
        /** String property with an optional constant default. */
        fun string(name: String, default: String? = null, namespace: String? = null, description: String? = null): Ack<String> {
            return Ack(name, namespace, description, AckType.STRING, { it }, constant(default))
        }

        /** String property whose default falls back to another [Ack]. */
        fun string(name: String, fallback: Ack<String>, namespace: String? = null, description: String? = null): Ack<String> {
            return Ack(name, namespace, description, AckType.STRING, { it }, { fallback.orNull() })
        }

        /** String property whose default is computed on each resolution. */
        fun string(name: String, namespace: String? = null, description: String? = null, default: () -> String?): Ack<String> {
            return Ack(name, namespace, description, AckType.STRING, { it }, default)
        }

        // ── Secret ──────────────────────────────────────────────────────
        // A secret is a string value tagged [AckType.SECRET] so callers (e.g. config UIs) mask it by default.
        /** Secret string property with an optional constant default. */
        fun secret(name: String, default: String? = null, namespace: String? = null, description: String? = null): Ack<String> {
            return Ack(name, namespace, description, AckType.SECRET, { it }, constant(default))
        }

        /** Secret string property whose default falls back to another [Ack]. */
        fun secret(name: String, fallback: Ack<String>, namespace: String? = null, description: String? = null): Ack<String> {
            return Ack(name, namespace, description, AckType.SECRET, { it }, { fallback.orNull() })
        }

        /** Secret string property whose default is computed on each resolution. */
        fun secret(name: String, namespace: String? = null, description: String? = null, default: () -> String?): Ack<String> {
            return Ack(name, namespace, description, AckType.SECRET, { it }, default)
        }

        // ── Int ─────────────────────────────────────────────────────────
        /** Int property with an optional constant default, clamped to [min]/[max]. */
        fun int(name: String, default: Int? = null, min: Int? = null, max: Int? = null, namespace: String? = null, description: String? = null): Ack<Int> {
            return Ack(name, namespace, description, AckType.INT, { clampInt(it.toIntOrNull(), min, max) }, constant(clampInt(default, min, max)))
        }

        /** Int property whose default falls back to another [Ack]. */
        fun int(name: String, fallback: Ack<Int>, min: Int? = null, max: Int? = null, namespace: String? = null, description: String? = null): Ack<Int> {
            return Ack(name, namespace, description, AckType.INT, { clampInt(it.toIntOrNull(), min, max) }, { clampInt(fallback.orNull(), min, max) })
        }

        /** Int property whose default is computed on each resolution. */
        fun int(name: String, min: Int? = null, max: Int? = null, namespace: String? = null, description: String? = null, default: () -> Int?): Ack<Int> {
            return Ack(name, namespace, description, AckType.INT, { clampInt(it.toIntOrNull(), min, max) }, { clampInt(default(), min, max) })
        }

        // ── Long ────────────────────────────────────────────────────────
        /** Long property with an optional constant default, clamped to [min]/[max]. */
        fun long(name: String, default: Long? = null, min: Long? = null, max: Long? = null, namespace: String? = null, description: String? = null): Ack<Long> {
            return Ack(name, namespace, description, AckType.LONG, { clampLong(it.toLongOrNull(), min, max) }, constant(clampLong(default, min, max)))
        }

        /** Long property whose default falls back to another [Ack]. */
        fun long(name: String, fallback: Ack<Long>, min: Long? = null, max: Long? = null, namespace: String? = null, description: String? = null): Ack<Long> {
            return Ack(name, namespace, description, AckType.LONG, { clampLong(it.toLongOrNull(), min, max) }, { clampLong(fallback.orNull(), min, max) })
        }

        /** Long property whose default is computed on each resolution. */
        fun long(name: String, min: Long? = null, max: Long? = null, namespace: String? = null, description: String? = null, default: () -> Long?): Ack<Long> {
            return Ack(name, namespace, description, AckType.LONG, { clampLong(it.toLongOrNull(), min, max) }, { clampLong(default(), min, max) })
        }

        // ── Double ──────────────────────────────────────────────────────
        /** Double property with an optional constant default, clamped to [min]/[max]. */
        fun double(name: String, default: Double? = null, min: Double? = null, max: Double? = null, namespace: String? = null, description: String? = null): Ack<Double> {
            return Ack(name, namespace, description, AckType.DOUBLE, { clampDouble(it.toDoubleOrNull(), min, max) }, constant(clampDouble(default, min, max)))
        }

        /** Double property whose default falls back to another [Ack]. */
        fun double(name: String, fallback: Ack<Double>, min: Double? = null, max: Double? = null, namespace: String? = null, description: String? = null): Ack<Double> {
            return Ack(name, namespace, description, AckType.DOUBLE, { clampDouble(it.toDoubleOrNull(), min, max) }, { clampDouble(fallback.orNull(), min, max) })
        }

        /** Double property whose default is computed on each resolution. */
        fun double(name: String, min: Double? = null, max: Double? = null, namespace: String? = null, description: String? = null, default: () -> Double?): Ack<Double> {
            return Ack(name, namespace, description, AckType.DOUBLE, { clampDouble(it.toDoubleOrNull(), min, max) }, { clampDouble(default(), min, max) })
        }

        // ── Boolean ─────────────────────────────────────────────────────
        /** Boolean property with an optional constant default. */
        fun boolean(name: String, default: Boolean? = null, namespace: String? = null, description: String? = null): Ack<Boolean> {
            return Ack(name, namespace, description, AckType.BOOLEAN, { it.toBooleanStrictOrNull() }, constant(default))
        }

        /** Boolean property whose default falls back to another [Ack]. */
        fun boolean(name: String, fallback: Ack<Boolean>, namespace: String? = null, description: String? = null): Ack<Boolean> {
            return Ack(name, namespace, description, AckType.BOOLEAN, { it.toBooleanStrictOrNull() }, { fallback.orNull() })
        }

        /** Boolean property whose default is computed on each resolution. */
        fun boolean(name: String, namespace: String? = null, description: String? = null, default: () -> Boolean?): Ack<Boolean> {
            return Ack(name, namespace, description, AckType.BOOLEAN, { it.toBooleanStrictOrNull() }, default)
        }

        private fun <T> constant(value: T?): (() -> T?)? {
            if (value == null) {
                return null
            }
            return { value }
        }

        private fun clampInt(value: Int?, min: Int?, max: Int?): Int? {
            if (value == null) {
                return null
            }
            var clamped = value
            if (min != null) {
                clamped = clamped.coerceAtLeast(min)
            }
            if (max != null) {
                clamped = clamped.coerceAtMost(max)
            }
            return clamped
        }

        private fun clampLong(value: Long?, min: Long?, max: Long?): Long? {
            if (value == null) {
                return null
            }
            var clamped = value
            if (min != null) {
                clamped = clamped.coerceAtLeast(min)
            }
            if (max != null) {
                clamped = clamped.coerceAtMost(max)
            }
            return clamped
        }

        private fun clampDouble(value: Double?, min: Double?, max: Double?): Double? {
            if (value == null) {
                return null
            }
            var clamped = value
            if (min != null) {
                clamped = clamped.coerceAtLeast(min)
            }
            if (max != null) {
                clamped = clamped.coerceAtMost(max)
            }
            return clamped
        }
    }
}
