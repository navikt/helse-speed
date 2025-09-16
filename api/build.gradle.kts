val tbdLibsVersion: String by project
val jedisVersion = "5.1.0"
val logbackClassicVersion = "1.5.18"
val logbackEncoderVersion = "8.0"
val jacksonVersion = "2.18.3"
val ktorVersion = "3.2.3" // bør være samme som i <com.github.navikt.tbd-libs:naisful-app>
val mockKVersion = "1.13.17"

dependencies {
    api("ch.qos.logback:logback-classic:$logbackClassicVersion")
    api("net.logstash.logback:logstash-logback-encoder:$logbackEncoderVersion")

    api("redis.clients:jedis:$jedisVersion")

    api("io.ktor:ktor-server-auth:$ktorVersion")
    api("io.ktor:ktor-server-auth-jwt:$ktorVersion") {
        exclude(group = "junit")
    }

    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    api("com.github.navikt.tbd-libs:naisful-app:$tbdLibsVersion")
    api("com.github.navikt.tbd-libs:azure-token-client-default:$tbdLibsVersion")

    testImplementation("com.github.navikt.tbd-libs:naisful-test-app:$tbdLibsVersion")
    testImplementation("com.github.navikt.tbd-libs:mock-http-client:$tbdLibsVersion")
    testImplementation("io.mockk:mockk:$mockKVersion")

    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
}

tasks {
    withType<Test> {
        systemProperty("junit.jupiter.execution.parallel.enabled", "true")
        systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
        systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
        systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", "4")
    }
}
