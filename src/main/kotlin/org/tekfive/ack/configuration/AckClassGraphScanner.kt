package org.tekfive.ack.configuration

import io.github.classgraph.ClassGraph
import org.slf4j.LoggerFactory
import org.tekfive.ack.Ack
import java.lang.reflect.Modifier

/**
 * ClassGraph-backed discovery of declared [Ack] fields.
 *
 * Isolated in its own file so that the ClassGraph dependency is referenced *only* from
 * [AckCatalog.scan]. ClassGraph is a `compileOnly` dependency of this library: consumers that rely on
 * the default self-registration never load this class and therefore never need ClassGraph on the
 * classpath. A consumer that calls [AckCatalog.scan] must add ClassGraph themselves.
 */
internal object AckClassGraphScanner {

    private val log = LoggerFactory.getLogger(AckClassGraphScanner::class.java)
    private const val ACK_TYPE = "org.tekfive.ack.Ack"

    /** Finds and reads every declared [Ack] field in [packages], returning the live instances. */
    fun findDeclaredAcks(packages: Array<out String>): List<Ack<*>> {
        val acks = mutableListOf<Ack<*>>()
        ClassGraph()
            .enableClassInfo()
            .enableFieldInfo()
            .ignoreFieldVisibility() // Kotlin `val`s compile to private backing fields.
            .acceptPackages(*packages)
            .scan()
            .use { result ->
                for (classInfo in result.allClasses) {
                    val declaresAck = classInfo.fieldInfo.any { it.typeDescriptor.toString().contains(ACK_TYPE) }
                    if (!declaresAck) {
                        continue
                    }
                    acks.addAll(readAcks(classInfo.name))
                }
            }
        return acks
    }

    private fun readAcks(className: String): List<Ack<*>> {
        val clazz = try {
            Class.forName(className, true, Ack::class.java.classLoader)
        } catch (e: Throwable) {
            log.debug("AckCatalog could not initialize {}: {}", className, e.message)
            return emptyList()
        }

        // Kotlin `object`/companion singletons expose their fields on the INSTANCE; top-level and
        // @JvmStatic fields are static.
        val instance = try {
            clazz.getDeclaredField("INSTANCE").also { it.isAccessible = true }.get(null)
        } catch (e: Throwable) {
            null
        }

        val acks = mutableListOf<Ack<*>>()
        for (field in clazz.declaredFields) {
            if (!Ack::class.java.isAssignableFrom(field.type)) {
                continue
            }
            val isStatic = Modifier.isStatic(field.modifiers)
            val owner = if (isStatic) null else instance
            if (!isStatic && owner == null) {
                // An Ack instance field on a non-singleton class — no instance to read it from.
                continue
            }
            try {
                field.isAccessible = true
                val value = field.get(owner)
                if (value is Ack<*>) {
                    acks.add(value)
                }
            } catch (e: Throwable) {
                log.debug("AckCatalog could not read {}.{}: {}", className, field.name, e.message)
            }
        }
        return acks
    }
}
