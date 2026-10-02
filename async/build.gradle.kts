plugins {
    id("no.nav.sykepenger.deployable")
}

sykepengerDeployable {
    mainClass = "no.nav.helse.speed.async.AppKt"
    imageName = "helse-speed-async"
}

dependencies {
    api(libs.rapidsAndRivers)
    api(libs.tbdLibs.azureTokenClientDefault)
    api(libs.tbdLibs.speedClient)

    api(libs.avro)

    testImplementation(libs.tbdLibs.rapidsAndRiversTest)
    testImplementation(libs.tbdLibs.mockHttpClient)
    testImplementation(libs.mockk)
}
