package no.nav.helse.speed.api

import com.auth0.jwk.JwkProviderBuilder
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.navikt.tbd_libs.azure.createAzureTokenClientFromEnvironment
import com.github.navikt.tbd_libs.naisful.naisApp
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.routing.*
import io.micrometer.core.instrument.Clock
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.model.registry.PrometheusRegistry
import no.nav.helse.speed.api.pdl.PdlClient
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.net.URI
import java.time.Duration

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
    val azureApp = AzureApp(
        jwkProvider = JwkProviderBuilder(URI(env.getValue("AZURE_OPENID_CONFIG_JWKS_URI")).toURL()).build(),
        issuer = env.getValue("AZURE_OPENID_CONFIG_ISSUER"),
        clientId = env.getValue("AZURE_APP_CLIENT_ID"),
    )

    val jedisPool = lagJedistilkobling(env)

    val azureClient = createAzureTokenClientFromEnvironment(env)

    val pdlClient = PdlClient(
        baseUrl = env.getValue("PDL_URL"),
        accessTokenClient = azureClient,
        accessTokenScope = env.getValue("PDL_SCOPE"),
        objectMapper = objectmapper
    )

    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT, PrometheusRegistry.defaultRegistry, Clock.SYSTEM)
    val identtjeneste = Identtjeneste(jedisPool, pdlClient, objectmapper, meterRegistry)
    val app = naisApp(
        meterRegistry = meterRegistry,
        objectMapper = objectmapper,
        applicationLogger = logg,
        callLogger = LoggerFactory.getLogger("no.nav.helse.speed.api.CallLogging"),
        timersConfig = { call, _ -> tag("azp_name", call.principal<JWTPrincipal>()?.get("azp_name") ?: "n/a") },
        mdcEntries = mapOf(
            "azp_name" to { call: ApplicationCall -> call.principal<JWTPrincipal>()?.get("azp_name") }
        )
    ) {
        authentication { azureApp.konfigurerJwtAuth(this) }
        routing {
            authenticate {
                api(identtjeneste)
            }
        }
    }
    app.start(wait = true)
}

private fun lagJedistilkobling(env: Map<String, String>): JedisPool {
    val uri = URI(env.getValue("REDIS_URI_MELLOMLAGER"))
    val config = DefaultJedisClientConfig.builder()
        .user(env.getValue("REDIS_USERNAME_MELLOMLAGER"))
        .password(env.getValue("REDIS_PASSWORD_MELLOMLAGER"))
        .ssl(true)
        .hostnameVerifier { hostname, _ ->
            val evaluering = hostname == uri.host
            logg.info("verifiserer vertsnavn $hostname: {}", evaluering)
            evaluering
        }
        .build()
    val poolConfig = JedisPoolConfig().apply {
        minIdle = 1 // minimum antall ledige tilkoblinger
        setMaxWait(Duration.ofSeconds(3)) // maksimal ventetid på tilkobling
        testOnBorrow = true // tester tilkoblingen før lån
        testWhileIdle = true // tester ledige tilkoblinger periodisk

    }
    return JedisPool(poolConfig, HostAndPort(uri.host, uri.port), config)
}
