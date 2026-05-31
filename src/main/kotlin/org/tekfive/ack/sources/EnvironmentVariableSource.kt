package org.tekfive.ack.sources

import org.tekfive.ack.configuration.AckSource
import org.tekfive.ack.configuration.ackKey

/** ACK source that resolves values from process environment variables, using `NAMESPACE_NAME` when namespaced. */
object EnvironmentVariableSource : AckSource {
    /** Returns the environment variable value for the (namespaced) key, or null when unset. */
    override fun get(name: String, namespace: String?): String? {
        return System.getenv(ackKey(name, namespace))
    }

    override fun reload() {}
}
