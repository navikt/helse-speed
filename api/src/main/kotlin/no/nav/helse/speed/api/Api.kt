package no.nav.helse.speed.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.github.navikt.tbd_libs.result_object.Result
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.LocalDate

fun Route.api(identtjeneste: Identtjeneste) {
    post("/api/person") {
        val request = call.receiveNullable<IdentRequest>() ?: throw BadRequestException("Mangler ident")
        val callId = call.callId ?: throw BadRequestException("Mangler callId-header")
        when (val svar = identtjeneste.hentPerson(request.ident, callId)) {
            is Result.Error -> throw Exception(svar.error, svar.cause)
            is Result.Ok -> when (val person = svar.value) {
                PersonResultat.FantIkkePerson -> throw NotFoundException("Fant ikke ident")
                is PersonResultat.Person -> call.respond(HttpStatusCode.OK, PersonResponse(
                    fødselsdato = person.fødselsdato,
                    dødsdato = person.dødsdato,
                    fornavn = person.fornavn,
                    mellomnavn = person.mellomnavn,
                    etternavn = person.etternavn,
                    adressebeskyttelse = when (person.adressebeskyttelse) {
                        PersonResultat.Person.Adressebeskyttelse.FORTROLIG -> PersonResponse.Adressebeskyttelse.FORTROLIG
                        PersonResultat.Person.Adressebeskyttelse.STRENGT_FORTROLIG -> PersonResponse.Adressebeskyttelse.STRENGT_FORTROLIG
                        PersonResultat.Person.Adressebeskyttelse.STRENGT_FORTROLIG_UTLAND -> PersonResponse.Adressebeskyttelse.STRENGT_FORTROLIG_UTLAND
                        PersonResultat.Person.Adressebeskyttelse.UGRADERT -> PersonResponse.Adressebeskyttelse.UGRADERT
                    },
                    kjønn = when (person.kjønn) {
                        PersonResultat.Person.Kjønn.MANN -> PersonResponse.Kjønn.MANN
                        PersonResultat.Person.Kjønn.KVINNE -> PersonResponse.Kjønn.KVINNE
                        PersonResultat.Person.Kjønn.UKJENT -> PersonResponse.Kjønn.UKJENT
                    },
                    kilde = when (person.kilde) {
                        Kilde.CACHE -> KildeResponse.CACHE
                        Kilde.PDL -> KildeResponse.PDL
                    }
                ))
            }
        }
    }
    route("/api/ident") {
        post {
            val request = call.receiveNullable<IdentRequest>() ?: throw BadRequestException("Mangler ident")
            val callId = call.callId ?: throw BadRequestException("Mangler callId-header")

            when (val svar = identtjeneste.hentFødselsnummerOgAktørId(request.ident, callId)) {
                is Result.Error -> throw Exception(svar.error, svar.cause)
                is Result.Ok -> when (val identer = svar.value) {
                    IdenterResultat.FantIkkeIdenter -> throw NotFoundException("Fant ikke ident")
                    is IdenterResultat.Identer -> call.respond(HttpStatusCode.OK, IdentResponse(
                        fødselsnummer = identer.fødselsnummer,
                        aktørId = identer.aktørId,
                        npid = identer.npid,
                        kilde = when (identer.kilde) {
                            Kilde.CACHE -> KildeResponse.CACHE
                            Kilde.PDL -> KildeResponse.PDL
                        }
                    ))
                }
            }
        }

        delete {
            val request = call.receiveNullable<SlettIdentRequest>() ?: throw BadRequestException("Mangler identer")
            when (val svar = identtjeneste.tømFraMellomlager(request.identer)) {
                SlettResultat.Ok -> call.respond(HttpStatusCode.OK, SlettResponse("OK"))
                is SlettResultat.Feilmelding -> throw Exception(svar.melding, svar.årsak)
            }
        }
    }
    post("/api/historiske_identer") {
        val request = call.receiveNullable<IdentRequest>() ?: throw BadRequestException("Mangler ident")
        val callId = call.callId ?: throw BadRequestException("Mangler callId-header")

        when (val svar = identtjeneste.hentHistoriskeFolkeregisterIdenter(request.ident, callId)) {
            is Result.Error -> throw Exception(svar.error, svar.cause)
            is Result.Ok -> when (val identer = svar.value) {
                HistoriskeIdenterResultat.FantIkkeIdenter -> throw NotFoundException("Fant ikke ident")
                is HistoriskeIdenterResultat.Identer -> call.respond(HttpStatusCode.OK, IdenterResponse(
                    fødselsnumre = identer.fødselsnumre,
                    kilde = when (identer.kilde) {
                        Kilde.CACHE -> KildeResponse.CACHE
                        Kilde.PDL -> KildeResponse.PDL
                    }
                ))
            }
        }
    }

}

@JsonIgnoreProperties(ignoreUnknown = true)
data class IdentRequest(val ident: String)
@JsonIgnoreProperties(ignoreUnknown = true)
data class SlettIdentRequest(val identer: List<String>)

data class SlettResponse(val status: String)

data class IdenterResponse(
    val fødselsnumre: List<String>,
    val kilde: KildeResponse
)
data class IdentResponse(
    val fødselsnummer: String,
    val aktørId: String,
    val npid: String?,
    val kilde: KildeResponse
)
enum class KildeResponse {
    CACHE, PDL
}

data class PersonResponse(
    val fødselsdato: LocalDate,
    val dødsdato: LocalDate?,
    val fornavn: String,
    val mellomnavn: String?,
    val etternavn: String,
    val adressebeskyttelse: Adressebeskyttelse,
    val kjønn: Kjønn,
    val kilde: KildeResponse
) {
    enum class Adressebeskyttelse {
        FORTROLIG, STRENGT_FORTROLIG, STRENGT_FORTROLIG_UTLAND, UGRADERT
    }
    enum class Kjønn {
        MANN, KVINNE, UKJENT
    }
}