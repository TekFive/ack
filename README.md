# ACK - Application Configuration for Kotlin

ACK is a small Kotlin library for reading application configuration from one or more sources with:

- typed access (`String`, `Boolean`, `Int`, `Long`, `Double`)
- required vs optional properties
- source priority (first source wins)
- named configuration groups (for multi-context apps)

## Why use ACK

ACK lets you define strongly-typed configuration values in code and resolve them from a shared registry of property sources.

- Keep config lookups centralized and consistent
- Avoid repetitive parsing and null-checking
- Define defaults close to where properties are used

## Installation

Maven dependency:

```xml
<dependency>
  <groupId>org.tekfive</groupId>
  <artifactId>ack</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Core Concepts

### 1. Register configuration sources

ACK resolves properties from `AckSource` implementations registered in `AckRegistry`.

```kotlin
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.configuration.AckSourceGroup
import org.tekfive.ack.sources.EnvironmentVariableSource
import org.tekfive.ack.sources.MapSource

// Default group (used when no configurationName is specified)
AckRegistry.addDefaultGroup(
    EnvironmentVariableSource,
    MapSource(mapOf("APP_NAME" to "demo-app"))
)
```

Order matters: sources are checked in order, and the first valid value is used.

### 2. Define properties

Construct properties with the typed factories on `Ack`: `Ack.string`, `Ack.int`,
`Ack.long`, `Ack.double`, `Ack.boolean` (plus `Ack.secret` for masked strings). Each takes an
optional `default`, `namespace`, and `description`. Optionality is expressed by the accessor you
call, not by the type:

- `invoke()` (i.e. `prop()`) returns a non-null value or throws — use for required config.
- `orNull()` returns the value or `null` — use for optional config.

```kotlin
import org.tekfive.ack.Ack

val appName = Ack.string("APP_NAME")          // required
val port = Ack.int("PORT", default = 8080)    // with a default
val debug = Ack.boolean("DEBUG")              // read as optional via orNull()

println(appName())       // String — throws if no source/default provides it
println(port())          // Int
println(debug.orNull())  // Boolean?
```

If a required property cannot be resolved (no source value and no default), `invoke()` throws:

- `IllegalStateException("Application property '<qualifiedName>' is not defined.")`

#### Numeric bounds (`min` / `max`)

`Ack.int`, `Ack.long`, and `Ack.double` accept optional `min` and `max` bounds. **Both** the
resolved source value **and** the `default` are clamped into `[min, max]`, so the property can
never return a value outside the allowed range:

```kotlin
// Defaults to 6; any configured value is clamped to 4..9.
val codeDigits = Ack.int("MFA_CODE_DIGITS", default = 6, min = 4, max = 9)

codeDigits()  // a source value of "12" resolves to 9; "2" resolves to 4
```

The bounds apply to every variant of the numeric factories — the constant `default`, the
`fallback`-`Ack` form, and the closure-default form.

### 3. Use named configuration groups

Named groups allow separate config contexts (for example, different service clients).

```kotlin
import org.tekfive.ack.AckString
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.configuration.AckSourceGroup
import org.tekfive.ack.sources.MapSource

AckRegistry.addSourceGroup(
    AckSourceGroup(
        name = "payments",
        sources = listOf(MapSource(mapOf("BASE_URL" to "https://pay.example.com")))
    )
)

val paymentsBaseUrl = AckString("BASE_URL", configurationName = "payments")
println(paymentsBaseUrl())
```

## Available Sources

ACK ships with several `AckSource` implementations. Register them in priority
order; the first source that returns a parseable value wins.

### `EnvironmentVariableSource`

Reads values from `System.getenv(name)`.

```kotlin
AckRegistry.addDefaultGroup(EnvironmentVariableSource)
```

### `MapSource`

Reads values from an in-memory `Map<String, String>`. Useful for tests and local overrides.

```kotlin
AckRegistry.addDefaultGroup(
    MapSource(
        mapOf(
            "APP_NAME" to "demo-app",
            "PORT" to "8080",
        )
    )
)
```

### `SinglePropertySource`

Exposes exactly one property from a constant string or lazy supplier.

```kotlin
import org.tekfive.ack.sources.single.SinglePropertySource

AckRegistry.addDefaultGroup(
    SinglePropertySource("BUILD_ID", { System.getProperty("build.id") ?: "dev" })
)
```

### `FileSingleSource`

Maps one property name to the full UTF-8 contents of a file.

```kotlin
import org.tekfive.ack.AckString
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.single.FileSingleSource

AckRegistry.addDefaultGroup(
    FileSingleSource("TLS_CERT", "/path/to/cert.pem")
)

val tlsCert = AckString("TLS_CERT")
println(tlsCert()) // entire file contents
```

### `StreamContentSource`

Returns the UTF-8 contents of a file through the same single-property source
pattern as `FileSingleSource`.

```kotlin
import org.tekfive.ack.sources.single.StreamContentSource

AckRegistry.addDefaultGroup(
    StreamContentSource("PRIVATE_KEY", "/run/secrets/private-key.pem")
)
```

### `DatabaseSource`

Loads configuration rows from a database table into an in-memory cache. By
default it expects:

```sql
ack_properties(name text, value text)
```

You can override the table and column names. Identifiers are validated to
letters, digits, and underscores before SQL is generated.

```kotlin
import org.tekfive.ack.sources.database.DatabaseSource
import java.sql.DriverManager

AckRegistry.addDefaultGroup(
    DatabaseSource(
        connectionProvider = {
            DriverManager.getConnection("jdbc:postgresql://localhost/app", "app", "secret")
        },
        tableName = "application_settings",
        nameColumn = "setting_name",
        valueColumn = "setting_value",
        ttlSeconds = 60,
    )
)
```

When `ttlSeconds` is null, values are loaded once and cached permanently. Call
`clearCache()` to force the next lookup to reload.

### `AwsParameterStoreSource`

Reads values from AWS Systems Manager Parameter Store. When `prefix` is set,
ACK resolves `NAME` as `<prefix>/NAME`.

```kotlin
import org.tekfive.ack.sources.aws.AwsParameterStoreSource

AckRegistry.addDefaultGroup(
    AwsParameterStoreSource(
        prefix = "/prod/aideway",
        decryptSecureStrings = true,
    )
)
```

### `AwsSecretsSource`

Reads a JSON object from AWS Secrets Manager and exposes each JSON field as a
property.

```kotlin
import org.tekfive.ack.sources.aws.AwsSecretsSource

AckRegistry.addDefaultGroup(
    AwsSecretsSource(secretName = "prod/aideway/app")
)
```

For a secret value like:

```json
{"DB_USER":"aideway","DB_PASSWORD":"secret"}
```

ACK can resolve `AckString("DB_USER")` and `AckString("DB_PASSWORD")`.

### `VaultSource`

Reads key-value data from HashiCorp Vault at a logical path and exposes each
field as a property.

```kotlin
import io.github.jopenlibs.vault.Vault
import org.tekfive.ack.sources.vault.VaultSource

AckRegistry.addDefaultGroup(
    VaultSource(
        path = "secret/data/aideway",
        vault = Vault(/* vault config */),
    )
)
```

## Property name prefixes

ACK supports combining prefixes and names:

- `combine(StringPrefix, "NAME")` -> `StringPrefix_NAME`
- `combine(KClass, "NAME")` -> `qualified.class.Name_NAME`

Convenience constructors in property classes support this directly.

## Custom sources

Implement `AckSource` to add your own provider:

```kotlin
import org.tekfive.ack.configuration.AckSource

class MySource : AckSource {
    override fun get(name: String): String? {
        // lookup logic
        return null
    }
}
```

## Testing tips

Clear global registry state between tests:

```kotlin
import org.tekfive.ack.configuration.AckRegistry

AckRegistry.clear()
```

## License

See `LICENSE`.
