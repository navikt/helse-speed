package no.nav.helse.speed.api_dev_proxy

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import com.github.navikt.tbd_libs.result_object.getOrThrow
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.jackson.JacksonConverter
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.accept
import io.ktor.server.routing.contentType
import io.ktor.server.routing.delete
import io.ktor.server.routing.header
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory

private suspend fun HttpResponse.proxyTilSpeed(call: RoutingCall) {
    call.respondText(Json, status) { bodyAsText() }
}

fun Route.api(azureTokenProvider: AzureTokenProvider, objectMapper: ObjectMapper) {
    val speedClient = HttpClient(CIO) {
        val sikkerlogg = LoggerFactory.getLogger("tjenestekall")
        install(Logging) {
            this.logger = object : Logger {
                override fun log(message: String) {
                    sikkerlogg.info("ktor speed client:\n$message")
                }
            }
        }
        install(ContentNegotiation) {
            register(Json, JacksonConverter(objectMapper))
        }
        defaultRequest {
            url("http://speed-api")
            headers {
                sikkerlogg.info("Henter token fra Azure")
                set(HttpHeaders.Authorization, "Bearer ${azureTokenProvider.bearerToken("api://dev-gcp.tbd.speed-api/.default").getOrThrow().token}")
            }
        }
    }

    route("/api/{...}") {
        post{
            val path = call.request.path()
            speedClient.post(path) {
                header("callId", call.callId)
                accept(Json)
                contentType(Json)
                setBody(call.receiveText())
            }.proxyTilSpeed(call)
        }
        delete {
            val path = call.request.path()
            speedClient.delete(path) {
                header("callId", call.callId)
                accept(Json)
                contentType(Json)
                setBody(call.receiveText())
            }.proxyTilSpeed(call)
        }
    }

}