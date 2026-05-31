package org.tekfive.ack.sources.vault

import io.github.jopenlibs.vault.Vault
import org.tekfive.ack.configuration.AckSource
import org.tekfive.ack.configuration.ackKey

/**
 * ACK source that reads key-value data from HashiCorp Vault.
 *
 * @param path Vault logical path to read.
 * @param vault Vault client used to fetch data.
 */
class VaultSource(
    private val path: String,
    private val vault: Vault,
) : AckSource {

    private val properties: Map<String, String> by lazy {
        vault.logical().read(path).data
    }

    /** Returns the Vault field value for the (namespaced) key, or null when absent. */
    override fun get(name: String, namespace: String?): String? {
        return properties[ackKey(name, namespace)]
    }

    override fun reload() {}
}
