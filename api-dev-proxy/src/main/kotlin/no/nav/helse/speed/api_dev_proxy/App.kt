package no.nav.helse.speed.api_dev_proxy

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.navikt.tbd_libs.azure.createAzureTokenClientFromEnvironment
import com.github.navikt.tbd_libs.naisful.naisApp
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.Clock
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.slf4j.LoggerFactory

private val logg = LoggerFactory.getLogger(::main.javaClass)
private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")
private val objectmapper = jacksonObjectMapper()
    .registerModules(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

fun main() {
    Thread.currentThread().setUncaughtExceptionHandler { _, e ->
        logg.error("Ufanget exception: {}", e.message, e)
        sikkerlogg.error("Ufanget exception: {}", e.message, e)
    }

    launchApp(System.getenv())
}

fun launchApp(env: Map<String, String>) {
    val azureClient = createAzureTokenClientFromEnvironment(env)

    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT, PrometheusRegistry.defaultRegistry, Clock.SYSTEM)
    val app = naisApp(
        meterRegistry = meterRegistry,
        objectMapper = objectmapper,
        applicationLogger = logg,
        callLogger = LoggerFactory.getLogger("no.nav.helse.speed.api_dev_proxy.CallLogging"),
        timersConfig = { call, _ -> tag("konsument", call.request.header("L5d-Client-Id") ?: "n/a") },
        mdcEntries = mapOf(
            "konsument" to { call: ApplicationCall -> call.request.header("L5d-Client-Id") }
        )
    ) {
        routing {
            api(azureClient, objectmapper)
        }
    }
    app.start(wait = true)
}
