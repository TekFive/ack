package org.tekfive.ack.sources.aws

import com.beust.klaxon.Klaxon
import org.tekfive.ack.configuration.AckSource
import org.tekfive.ack.configuration.ackKey
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest

/**
 * ACK source that reads a JSON object from AWS Secrets Manager.
 *
 * @param secretName secret identifier to read.
 * @param client Secrets Manager client used to fetch the secret.
 */
class AwsSecretsSource(
    private val secretName: String,
    private val client: SecretsManagerClient = SecretsManagerClient.create(),
) : AckSource {

    private val properties: Map<String, String> by lazy {
        val request = GetSecretValueRequest.builder()
            .secretId(secretName)
            .build()
        val response = client.getSecretValue(request)
        val json = Klaxon().parseJsonObject(response.secretString().reader())
        json.mapValues { it.value.toString() }
    }

    /** Returns the secret JSON field value for the (namespaced) key, or null when absent. */
    override fun get(name: String, namespace: String?): String? {
        return properties[ackKey(name, namespace)]
    }

    override fun reload() {}
}
