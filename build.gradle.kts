import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

val junitJupiterVersion = "6.1.0"
val tbdLibsVersion = "20260702.1010"

plugins {
    kotlin("jvm") version "2.4.0" apply false
}

allprojects {
    // Sett opp repositories basert på om vi kjører i CI eller ikke
    // Jf. https://github.com/navikt/utvikling/blob/main/docs/teknisk/Konsumere%20biblioteker%20fra%20Github%20Package%20Registry.md
    repositories {
        mavenCentral()
        if (providers.environmentVariable("GITHUB_ACTIONS").orNull == "true") {
            maven {
                url = uri("https://maven.pkg.github.com/navikt/maven-release")
                credentials {
                    username = "token"
                    password = providers.environmentVariable("GITHUB_TOKEN").orNull!!
                }
            }
        } else {
            maven("https://repo.adeo.no/repository/github-package-registry-navikt/")
        }
    }
}

subprojects {
    plugins.apply("org.jetbrains.kotlin.jvm")

    extra["tbdLibsVersion"] = tbdLibsVersion

    dependencies {
        // litt teit syntaks, det henger sammen med at kotlin-pluginen ikke er ordentlig til stede ennå, gradle-messig
        "testImplementation"("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    configure<KotlinJvmProjectExtension> {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of("21"))
        }
    }

    tasks {
        val copyDeps = register<Sync>("copyDeps") {
            description = "Kopierer runtime-avhengigheter til libs-mappa"
            from(configurations.named("runtimeClasspath"))
            into(layout.buildDirectory.dir("libs"))
        }
        named<Jar>("jar") {
            dependsOn(copyDeps)
            archiveBaseName.set("app")

            manifest {
                attributes["Main-Class"] = "no.nav.helse.speed.${project.name.replace("-", "_")}.AppKt"
                attributes["Class-Path"] = configurations.named("runtimeClasspath").get().joinToString(" ") { it.name }
            }
        }

        withType<Test> {
            useJUnitPlatform()
            testLogging {
                events("skipped", "failed")
            }
        }
    }
}
