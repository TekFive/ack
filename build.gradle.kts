plugins {
    kotlin("jvm") version "2.3.20"
    `java-library`
    `maven-publish`
}

group = "org.tekfive"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    api(kotlin("stdlib"))
    api(kotlin("reflect"))
    api("software.amazon.awssdk:secretsmanager:2.29.0")
    api("software.amazon.awssdk:ssm:2.29.0")
    api("io.github.jopenlibs:vault-java-driver:5.4.0")

    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("com.beust:klaxon:5.6")
    // Optional: only needed by callers of AckCatalog.scan(). compileOnly keeps it off consumers'
    // runtime classpath so self-registration users don't pull in ClassGraph.
    compileOnly("io.github.classgraph:classgraph:4.8.179")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.github.classgraph:classgraph:4.8.179")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "ack"
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri(
                System.getenv("GITHUB_REPOSITORY")?.let { "https://maven.pkg.github.com/$it" }
                    ?: "https://maven.pkg.github.com/TekFive/ack",
            )
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key") as String?
            }
        }
    }
}
