val tbdLibsVersion: String by project
val logbackClassicVersion = "1.5.12"
val logbackEncoderVersion = "8.0"
val jacksonVersion = "2.18.3"
val ktorVersion = "3.1.2" // bør være samme som i <com.github.navikt.tbd-libs:naisful-app>

dependencies {
    api("ch.qos.logback:logback-classic:$logbackClassicVersion")
    api("net.logstash.logback:logstash-logback-encoder:$logbackEncoderVersion")

    api("io.ktor:ktor-client-cio:$ktorVersion")
    api("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    api("io.ktor:ktor-client-logging:$ktorVersion")

    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    api("com.github.navikt.tbd-libs:naisful-app:$tbdLibsVersion")
    api("com.github.navikt.tbd-libs:azure-token-client-default:$tbdLibsVersion")
}

tasks {
    withType<Test> {
        systemProperty("junit.jupiter.execution.parallel.enabled", "true")
        systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
        systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
        systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", "4")
    }
}
