package no.nav.helse.speed.api_dev_proxy

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import com.github.navikt.tbd_libs.result_object.getOrThrow
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.ContentType.Application.Json
import io.ktor.serialization.jackson.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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
            url("http://speed-api/api/")
            headers {
                set(HttpHeaders.Authorization, "Bearer ${azureTokenProvider.bearerToken("api://dev-gcp.tbd.speed-api/.default").getOrThrow().token}")
            }
        }
    }
    post("/api/person") {
        val request = call.receiveNullable<IdentRequest>() ?: throw BadRequestException("Mangler ident")
        val callId = call.callId ?: throw BadRequestException("Mangler callId-header")

        speedClient.post("person") {
            header("callId", callId)
            accept(Json)
            contentType(Json)
            setBody(request)
        }.proxyTilSpeed(call)
    }
    route("/api/ident") {
        post {
            val request = call.receiveNullable<IdentRequest>() ?: throw BadRequestException("Mangler ident")
            val callId = call.callId ?: throw BadRequestException("Mangler callId-header")
            speedClient.post("ident") {
                header("callId", callId)
                accept(Json)
                contentType(Json)
                setBody(request)
            }.proxyTilSpeed(call)
        }

        delete {
            val request = call.receiveNullable<SlettIdentRequest>() ?: throw BadRequestException("Mangler identer")
            val callId = call.callId ?: throw BadRequestException("Mangler callId-header")
            speedClient.delete("ident") {
                header("callId", callId)
                accept(Json)
                contentType(Json)
                setBody(request)
            }.proxyTilSpeed(call)
        }
    }
    post("/api/historiske_identer") {
        val request = call.receiveNullable<IdentRequest>() ?: throw BadRequestException("Mangler ident")
        val callId = call.callId ?: throw BadRequestException("Mangler callId-header")
        speedClient.post("historiske_identer") {
            header("callId", callId)
            accept(Json)
            contentType(Json)
            setBody(request)
        }.proxyTilSpeed(call)
    }
    post("/api/vergemål_eller_fremtidsfullmakt") {
        val request = call.receiveNullable<IdentRequest>() ?: throw BadRequestException("Mangler ident")
        val callId = call.callId ?: throw BadRequestException("Mangler callId-header")
        speedClient.post("vergemål_eller_fremtidsfullmakt") {
            header("callId", callId)
            accept(Json)
            contentType(Json)
            setBody(request)
        }.proxyTilSpeed(call)
    }

    post("/api/geografisk_tilknytning") {
        val request = call.receiveNullable<IdentRequest>() ?: throw BadRequestException("Mangler ident")
        val callId = call.callId ?: throw BadRequestException("Mangler callId-header")
        speedClient.post("geografisk_tilknytning") {
            header("callId", callId)
            accept(Json)
            contentType(Json)
            setBody(request)
        }.proxyTilSpeed(call)
    }

}

@JsonIgnoreProperties(ignoreUnknown = true)
data class IdentRequest(val ident: String)
@JsonIgnoreProperties(ignoreUnknown = true)
data class SlettIdentRequest(val identer: List<String>)
