val gradleversjon = "8.10.2"
val junitJupiterVersion = "5.10.2"

plugins {
    kotlin("jvm") version "1.9.22" apply false
}

allprojects {
    repositories {
        val githubPassword: String? by project
        mavenCentral()
        /* ihht. https://github.com/navikt/utvikling/blob/main/docs/teknisk/Konsumere%20biblioteker%20fra%20Github%20Package%20Registry.md
            så plasseres github-maven-repo (med autentisering) før nav-mirror slik at github actions kan anvende førstnevnte.
            Det er fordi nav-mirroret kjører i Google Cloud og da ville man ellers fått unødvendige utgifter til datatrafikk mellom Google Cloud og GitHub
         */
        maven {
            url = uri("https://maven.pkg.github.com/navikt/maven-release")
            credentials {
                username = "x-access-token"
                password = githubPassword
            }
        }
        maven("https://github-package-registry-mirror.gc.nav.no/cached/maven-release")
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    val testImplementation by configurations
    val testRuntimeOnly by configurations
    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks {
        if (project.name != "fabrikk") {
            withType<Jar> {
                archiveBaseName.set("app")

                doFirst {
                    manifest {
                        val runtimeClasspath by configurations
                        attributes["Main-Class"] = "no.nav.helse.speed.${project.name}.AppKt"
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

tasks {
    withType<Wrapper> {
        gradleVersion = gradleversjon
    }
}

