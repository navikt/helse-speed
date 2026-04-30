import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

val junitJupiterVersion = "6.0.3"
val tbdLibsVersion = "20260429.1019"

plugins {
    kotlin("jvm") version "2.3.20" apply false
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
    apply(plugin = "org.jetbrains.kotlin.jvm")

    ext.set("tbdLibsVersion", tbdLibsVersion)

    val testImplementation by configurations
    val testRuntimeOnly by configurations
    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    configure<KotlinJvmProjectExtension> {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of("21"))
        }
    }

    tasks {
        if (project.name != "fabrikk") {
            withType<Jar> {
                archiveBaseName.set("app")

                doFirst {
                    manifest {
                        val runtimeClasspath by configurations
                        attributes["Main-Class"] = "no.nav.helse.speed.${project.name.replace("-", "_")}.AppKt"
                        attributes["Class-Path"] = runtimeClasspath.joinToString(separator = " ") {
                            it.name
                        }
                    }
                }
            }

            val copyDeps by registering(Sync::class) {
                val runtimeClasspath by configurations
                from(runtimeClasspath)
                into("build/libs")
            }
            named("assemble") {
                dependsOn(copyDeps)
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

