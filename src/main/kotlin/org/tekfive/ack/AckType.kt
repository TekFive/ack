package org.tekfive.ack

/** The value type of an [Ack] property. */
enum class AckType {
    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,

    /** A string value that holds a secret (key, password, token); callers should mask it by default. */
    SECRET,
}
