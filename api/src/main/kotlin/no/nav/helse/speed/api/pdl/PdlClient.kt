package no.nav.helse.speed.api.pdl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import java.time.LocalDate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class PdlClient(
    private val baseUrl: String,
    private val accessTokenClient: AzureTokenProvider,
    private val accessTokenScope: String,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient = HttpClient.newHttpClient()
) {

    internal fun hentIdenter(ident: String, callId: String) = hentAlleIdenter(ident, false, callId)
    internal fun hentAlleIdenter(ident: String, callId: String) = hentAlleIdenter(ident, true, callId)
    internal fun hentPerson(ident: String, callId: String): PdlPersonResultat {
        val response = convertResponseBody<PdlHentPersonResponse>(request(hentPersonQuery(ident), callId)).data.hentPerson
        if (response == null) return PdlPersonResultat.FantIkkePerson
        return PdlPersonResultat.Person(
            fødselsdato = response.foedselsdato.first().foedselsdato,
            // foretrekker PDL dersom flere innslag
            dødsdato = response.doedsfall.firstOrNull { it.metadata.master.lowercase() == "pdl" }?.doedsdato ?: response.doedsfall.firstOrNull()?.doedsdato,
            fornavn = response.navn.first().fornavn,
            mellomnavn = response.navn.first().mellomnavn,
            etternavn = response.navn.first().etternavn,
            adressebeskyttelse = when (response.adressebeskyttelse.firstOrNull()?.gradering) {
                null, PdlHentPersonResponse.Adressebeskyttelse.Adressebeskyttelsegradering.UGRADERT -> PdlPersonResultat.Person.Adressebeskyttelse.UGRADERT
                PdlHentPersonResponse.Adressebeskyttelse.Adressebeskyttelsegradering.FORTROLIG -> PdlPersonResultat.Person.Adressebeskyttelse.FORTROLIG
                PdlHentPersonResponse.Adressebeskyttelse.Adressebeskyttelsegradering.STRENGT_FORTROLIG -> PdlPersonResultat.Person.Adressebeskyttelse.STRENGT_FORTROLIG
                PdlHentPersonResponse.Adressebeskyttelse.Adressebeskyttelsegradering.STRENGT_FORTROLIG_UTLAND -> PdlPersonResultat.Person.Adressebeskyttelse.STRENGT_FORTROLIG_UTLAND
            },
            kjønn = when (response.kjoenn.first().kjoenn) {
                PdlHentPersonResponse.Kjønn.Kjønnverdi.MANN -> PdlPersonResultat.Person.Kjønn.MANN
                PdlHentPersonResponse.Kjønn.Kjønnverdi.KVINNE -> PdlPersonResultat.Person.Kjønn.KVINNE
                PdlHentPersonResponse.Kjønn.Kjønnverdi.UKJENT -> PdlPersonResultat.Person.Kjønn.UKJENT
            }
        )
    }

    private fun request(query: PdlQueryObject, callId: String): HttpResponse<String> {
        val body = objectMapper.writeValueAsString(query)
        val request = HttpRequest.newBuilder(URI.create(baseUrl))
            .header("TEMA", "SYK")
            .header("Authorization", "Bearer ${accessTokenClient.bearerToken(accessTokenScope).token}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Nav-Call-Id", callId)
            .header("behandlingsnummer", "B139")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val responseHandler = HttpResponse.BodyHandlers.ofString()

        val response = httpClient.send(request, responseHandler)
        if (response.statusCode() != 200) throw PdlException("error (responseCode=${response.statusCode()}) from PDL")

        return response
    }

    private fun hentAlleIdenter(ident: String, historisk: Boolean, callId: String): PdlIdenterResultat {
        val response = convertResponseBody<PdlHentIdenterResponse>(request(hentIdenterQuery(ident, historisk), callId)).data.hentIdenter.identer
        if (response.isEmpty()) return PdlIdenterResultat.FantIkkeIdenter
        val (historiske, gjeldende) = response.partition { it.historisk }
        return PdlIdenterResultat.Identer(
            gjeldende = gjeldende.map(::mapIdent),
            historiske = historiske.map(::mapIdent)
        )
    }

    private fun mapIdent(ident: PdlHentIdenterResponse.Ident) =
        when (ident.gruppe) {
            PdlHentIdenterResponse.Identgruppe.AKTORID -> Ident.AktørId(ident.ident)
            PdlHentIdenterResponse.Identgruppe.FOLKEREGISTERIDENT -> Ident.Fødselsnummer(ident.ident)
            PdlHentIdenterResponse.Identgruppe.NPID -> Ident.NPID(ident.ident)
        }

    private inline fun <reified T> convertResponseBody(response: HttpResponse<String>): T {
        return try {
            objectMapper.readValue<T>(response.body())
        } catch (err: Exception) {
            throw PdlException(err.message ?: "JSON parsing error", err)
        }
    }
}

class PdlException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PdlHentIdenterResponse(
    val data: PdlHentIdenter
) {
    data class PdlHentIdenter(
        val hentIdenter: Identer
    )
    data class Identer(
        val identer: List<Ident>
    )
    data class Ident(
        val ident: String,
        val historisk: Boolean,
        val gruppe: Identgruppe
    )
    enum class Identgruppe {
        AKTORID, FOLKEREGISTERIDENT, NPID
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PdlHentPersonResponse(
    val data: PdlHentPerson
) {
    data class PdlHentPerson(
        val hentPerson: Person?
    )
    data class Person(
        val foedselsdato: List<Fødselsdato>,
        val navn: List<Navn>,
        val adressebeskyttelse: List<Adressebeskyttelse>,
        val kjoenn: List<Kjønn>,
        val doedsfall: List<Dødsfall>
    )
    data class Fødselsdato(
        val foedselsdato: LocalDate
    )
    data class Navn(
        val fornavn: String,
        val mellomnavn: String?,
        val etternavn: String
    )
    data class Adressebeskyttelse(
        val gradering: Adressebeskyttelsegradering?
    ) {
        enum class Adressebeskyttelsegradering {
            FORTROLIG, STRENGT_FORTROLIG, STRENGT_FORTROLIG_UTLAND, @JsonEnumDefaultValue UGRADERT
        }
    }
    data class Kjønn(
        val kjoenn: Kjønnverdi
    ) {
        enum class Kjønnverdi {
            MANN, KVINNE, @JsonEnumDefaultValue UKJENT
        }
    }
    data class Dødsfall(
        val doedsdato: LocalDate,
        val metadata: Metadata
    )
    data class Metadata(val master: String)
}

sealed interface PdlPersonResultat {
    data class Person(
        val fødselsdato: LocalDate,
        val dødsdato: LocalDate?,
        val fornavn: String,
        val mellomnavn: String?,
        val etternavn: String,
        val adressebeskyttelse: Adressebeskyttelse,
        val kjønn: Kjønn
    ): PdlPersonResultat {
        enum class Adressebeskyttelse {
            FORTROLIG, STRENGT_FORTROLIG, STRENGT_FORTROLIG_UTLAND, UGRADERT
        }
        enum class Kjønn {
            MANN, KVINNE, UKJENT
        }
    }
    data object FantIkkePerson: PdlPersonResultat
}
sealed interface PdlIdenterResultat {
    data class Identer(
        val gjeldende: List<Ident>,
        val historiske: List<Ident>
    ): PdlIdenterResultat {
        val fødselsnummer = gjeldende.first { it is Ident.Fødselsnummer }.ident
        val aktørId = gjeldende.first { it is Ident.AktørId }.ident
        val npid = gjeldende.firstOrNull { it is Ident.NPID }?.ident
    }

    data object FantIkkeIdenter: PdlIdenterResultat
}

sealed class Ident(val ident: String) {
    class Fødselsnummer(ident: String) : Ident(ident)
    class AktørId(ident: String) : Ident(ident)
    class NPID(ident: String) : Ident(ident)
}