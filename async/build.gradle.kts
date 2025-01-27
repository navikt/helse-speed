val rapidsAndRiversVersion = "2025012712551737978926.de930d8e0feb"
val tbdLibsVersion: String by project
val mockkVersion = "1.13.9"
val avroVersion = "1.12.0"

dependencies {
    api("com.github.navikt:rapids-and-rivers:$rapidsAndRiversVersion")
    api("com.github.navikt.tbd-libs:azure-token-client-default:$tbdLibsVersion")
    api("com.github.navikt.tbd-libs:speed-client:$tbdLibsVersion")

    api("org.apache.avro:avro:$avroVersion")

    testImplementation("com.github.navikt.tbd-libs:rapids-and-rivers-test:$tbdLibsVersion")
    testImplementation("com.github.navikt.tbd-libs:mock-http-client:$tbdLibsVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
}
