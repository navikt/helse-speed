plugins {
    id("no.nav.sykepenger.deployable")
}

sykepengerDeployable {
    mainClass = "no.nav.helse.speed.api.AppKt"
    imageName = "helse-speed-api"
}

dependencies {
    api(libs.logback.classic)
    api(libs.logstash.logback.encoder)

    api(libs.jedis)

    api(libs.ktor.server.auth)
    api(libs.ktor.server.auth.jwt) {
        exclude(group = "junit")
    }

    api(libs.tbdLibs.naisfulApp)
    api(libs.tbdLibs.azureTokenClientDefault)

    testImplementation(libs.tbdLibs.naisfulTestApp)
    testImplementation(libs.tbdLibs.mockHttpClient)
    testImplementation(libs.mockk)

    testImplementation(libs.ktor.client.contentNegotiation)
}

tasks {
    withType<Test> {
        systemProperty("junit.jupiter.execution.parallel.enabled", "true")
        systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
        systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
        systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", "4")
    }
}
