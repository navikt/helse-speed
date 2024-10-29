package no.nav.helse.speed.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.api(identtjeneste: Identtjeneste) {
    post("/api/ident") {
        val request = call.receiveNullable<IdentRequest>() ?: return@post call.respond(HttpStatusCode.BadRequest, FeilResponse(
            feilmelding = "Ugyldig request",
            callId = call.callId
        ))
        val callId = call.callId ?: throw BadRequestException("Mangler callId-header")

        when (val svar = identtjeneste.hentFødselsnummerOgAktørId(request.ident, callId)) {
            IdenterResultat.FantIkkeIdenter -> throw NotFoundException("Fant ikke ident")
            is IdenterResultat.Feilmelding -> throw Exception(svar.melding, svar.årsak)
            is IdenterResultat.Identer -> call.respond(HttpStatusCode.OK, IdentResponse(
                fødselsnummer = svar.fødselsnummer,
                aktørId = svar.aktørId,
                npid = svar.npid,
                kilde = when (svar.kilde) {
                    IdenterResultat.Kilde.CACHE -> KildeResponse.CACHE
                    IdenterResultat.Kilde.PDL -> KildeResponse.PDL
                }
            ))
        }
    }
}

data class IdentRequest(val ident: String)
data class IdentResponse(
    val fødselsnummer: String,
    val aktørId: String,
    val npid: String?,
    val kilde: KildeResponse
)
enum class KildeResponse {
    CACHE, PDL
}
