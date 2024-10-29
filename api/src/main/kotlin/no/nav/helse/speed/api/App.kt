package no.nav.helse.speed.api

import com.auth0.jwk.JwkProviderBuilder
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.navikt.tbd_libs.azure.createAzureTokenClientFromEnvironment
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import net.logstash.logback.argument.StructuredArguments.keyValue
import net.logstash.logback.argument.StructuredArguments.v
import no.nav.helse.speed.api.pdl.PdlClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.io.CharArrayWriter
import java.net.URI
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

private val logg = LoggerFactory.getLogger(::main.javaClass)
private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")
private val objectmapper = jacksonObjectMapper().registerModules(JavaTimeModule())

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

    val identtjeneste = Identtjeneste(jedisPool, pdlClient, objectmapper)

    val app = embeddedServer(
        factory = CIO,
        environment = applicationEngineEnvironment {
            log = logg
            connectors.add(EngineConnectorBuilder().apply {
                this.port = 8080
            })
            module {
                authentication { azureApp.konfigurerJwtAuth(this) }
                lagApplikasjonsmodul(identtjeneste, objectmapper, CollectorRegistry.defaultRegistry)
            }
        }
    )
    app.start(wait = true)
}

private fun lagJedistilkobling(env: Map<String, String>): JedisPool {
    val uri = URI(env.getValue("REDIS_URI_MELLOMLAGER"))
    val config = DefaultJedisClientConfig.builder()
        .user(env.getValue("REDIS_USERNAME_MELLOMLAGER"))
        .password(env.getValue("REDIS_PASSWORD_MELLOMLAGER"))
        .ssl(true)
        .hostnameVerifier { hostname, session ->
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

fun Application.lagApplikasjonsmodul(identtjeneste: Identtjeneste, objectMapper: ObjectMapper, collectorRegistry: CollectorRegistry) {
    val readyToggle = AtomicBoolean(false)

    environment.monitor.subscribe(ApplicationStarted) {
        readyToggle.set(true)
    }

    install(CallId) {
        header("callId")
        verify { it.isNotEmpty() }
        generate { UUID.randomUUID().toString() }
    }
    install(CallLogging) {
        logger = LoggerFactory.getLogger("no.nav.helse.speed.api.CallLogging")
        level = Level.INFO
        callIdMdc("callId")
        disableDefaultColors()
        filter { call -> call.request.path().startsWith("/api/") }
    }
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, FeilResponse(
                feilmelding = "Ugyldig request: ${cause.message}\n${cause.stackTraceToString()}",
                callId = call.callId
            ))
        }
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, FeilResponse(
                feilmelding = "Ikke funnet: ${cause.message}\n${cause.stackTraceToString()}",
                callId = call.callId
            ))
        }
        exception<Throwable> { call, cause ->
            call.application.log.info("ukjent feil: ${cause.message}. svarer med InternalServerError og en feilmelding i JSON", cause)
            call.respond(HttpStatusCode.InternalServerError, FeilResponse(
                feilmelding = "Tjeneren møtte på ein feilmelding: ${cause.message}\n${cause.stackTraceToString()}",
                callId = call.callId
            ))
        }
    }
    install(ContentNegotiation) {
        register(ContentType.Application.Json, JacksonConverter(objectMapper))
    }
    requestResponseTracing(LoggerFactory.getLogger("no.nav.helse.speed.api.Tracing"))
    nais(readyToggle, collectorRegistry)
    routing {
        authenticate {
            api(identtjeneste)
        }
    }
}

data class FeilResponse(
    val feilmelding: String,
    val callId: String?
)

private const val isaliveEndpoint = "/isalive"
private const val isreadyEndpoint = "/isready"
private const val metricsEndpoint = "/metrics"

private val ignoredPaths = listOf(metricsEndpoint, isaliveEndpoint, isreadyEndpoint)

private fun Application.requestResponseTracing(logger: Logger) {
    intercept(ApplicationCallPipeline.Monitoring) {
        if (call.request.uri in ignoredPaths) return@intercept proceed()
        val headers = call.request.headers.toMap()
            .filterNot { (key, _) -> key.lowercase() in listOf("authorization") }
            .map { (key, values) ->
                keyValue("req_header_$key", values.joinToString(separator = ";"))
            }.toTypedArray()
        logger.info("{} {}", v("method", call.request.httpMethod.value), v("uri", call.request.uri), *headers)
        proceed()
    }

    sendPipeline.intercept(ApplicationSendPipeline.After) { message ->
        val status = call.response.status() ?: (when (message) {
            is OutgoingContent -> message.status
            is HttpStatusCode -> message
            else -> null
        } ?: HttpStatusCode.OK).also { status ->
            call.response.status(status)
        }

        if (call.request.uri in ignoredPaths) return@intercept
        logger.info("svarer status=${status.value} ${call.request.uri}")
    }
}

private fun Application.nais(readyToggle: AtomicBoolean, collectorRegistry: CollectorRegistry) {
    install(MicrometerMetrics) {
        registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT, collectorRegistry, Clock.SYSTEM)
        meterBinders = listOf(
            ClassLoaderMetrics(),
            JvmMemoryMetrics(),
            JvmGcMetrics(),
            ProcessorMetrics(),
            JvmThreadMetrics(),
        )
    }

    routing {
        get(isaliveEndpoint) {
            call.respondText("ALIVE", ContentType.Text.Plain)
        }

        get(isreadyEndpoint) {
            if (!readyToggle.get()) return@get call.respondText("NOT READY", ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
            call.respondText("READY", ContentType.Text.Plain)
        }

        get(metricsEndpoint) {
            val names = call.request.queryParameters.getAll("name[]")?.toSet() ?: emptySet()
            val formatted = CharArrayWriter(1024)
                .also { TextFormat.write004(it, collectorRegistry.filteredMetricFamilySamples(names)) }
                .use { it.toString() }

            call.respondText(
                contentType = ContentType.parse(TextFormat.CONTENT_TYPE_004),
                text = formatted
            )
        }
    }
}