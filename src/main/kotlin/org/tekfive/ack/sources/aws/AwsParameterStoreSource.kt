package org.tekfive.ack.sources.aws

import org.tekfive.ack.configuration.AckSource
import org.tekfive.ack.configuration.ackKey
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest

/**
 * ACK source that reads values from AWS Systems Manager Parameter Store.
 *
 * @param prefix optional parameter path prefix prepended before property names.
 * @param decryptSecureStrings whether secure string parameters should be decrypted.
 * @param client SSM client used to fetch parameters.
 */
class AwsParameterStoreSource(
    private val prefix: String = "",
    private val decryptSecureStrings: Boolean = true,
    private val client: SsmClient = SsmClient.create(),
) : AckSource {

    /** Returns the Parameter Store value for the (namespaced) key, or null when the parameter is not found. */
    override fun get(name: String, namespace: String?): String? {
        val key = ackKey(name, namespace)
        val parameterName = if (prefix.isEmpty()) key else "$prefix/$key"
        return try {
            val request = GetParameterRequest.builder()
                .name(parameterName)
                .withDecryption(decryptSecureStrings)
                .build()
            client.getParameter(request).parameter().value()
        } catch (e: software.amazon.awssdk.services.ssm.model.ParameterNotFoundException) {
            null
        }
    }

    override fun reload() {}
}
