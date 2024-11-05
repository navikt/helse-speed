package no.nav.helse.speed.api.pdl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import com.github.navikt.tbd_libs.result_object.Result
import com.github.navikt.tbd_libs.result_object.error
import com.github.navikt.tbd_libs.result_object.map
import com.github.navikt.tbd_libs.result_object.ok
import no.nav.helse.speed.api.pdl.PdlHentGeografiskTilknytningResponse.GeografiskTilknytningType
import no.nav.helse.speed.api.pdl.PdlHentPersonResponse.Person
import no.nav.helse.speed.api.pdl.PdlVergemålEllerFremtidsfullmaktResultat.VergemålEllerFremtidsfullmakt.Vergemåltype
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
    internal fun hentPerson(ident: String, callId: String): Result<PdlPersonResultat> {
        return request(hentPersonQuery(ident), callId).map {
            convertResponseBody<PdlHentPersonResponse>(it)
        }.map {
            val person = it.data.hentPerson
            if (person == null) PdlPersonResultat.FantIkkePerson.ok()
            else PdlPersonResultat.Person(
                fødselsdato = person.foedselsdato.first().foedselsdato,
                // foretrekker PDL dersom flere innslag
                dødsdato = person.doedsfall.firstOrNull { it.metadata.master.lowercase() == "pdl" }?.doedsdato ?: person.doedsfall.firstOrNull()?.doedsdato,
                fornavn = person.navn.first().fornavn,
                mellomnavn = person.navn.first().mellomnavn,
                etternavn = person.navn.first().etternavn,
                adressebeskyttelse = when (person.adressebeskyttelse.firstOrNull()?.gradering) {
                    null, PdlHentPersonResponse.Adressebeskyttelse.Adressebeskyttelsegradering.UGRADERT -> PdlPersonResultat.Person.Adressebeskyttelse.UGRADERT
                    PdlHentPersonResponse.Adressebeskyttelse.Adressebeskyttelsegradering.FORTROLIG -> PdlPersonResultat.Person.Adressebeskyttelse.FORTROLIG
                    PdlHentPersonResponse.Adressebeskyttelse.Adressebeskyttelsegradering.STRENGT_FORTROLIG -> PdlPersonResultat.Person.Adressebeskyttelse.STRENGT_FORTROLIG
                    PdlHentPersonResponse.Adressebeskyttelse.Adressebeskyttelsegradering.STRENGT_FORTROLIG_UTLAND -> PdlPersonResultat.Person.Adressebeskyttelse.STRENGT_FORTROLIG_UTLAND
                },
                kjønn = when (person.kjoenn.first().kjoenn) {
                    PdlHentPersonResponse.Kjønn.Kjønnverdi.MANN -> PdlPersonResultat.Person.Kjønn.MANN
                    PdlHentPersonResponse.Kjønn.Kjønnverdi.KVINNE -> PdlPersonResultat.Person.Kjønn.KVINNE
                    PdlHentPersonResponse.Kjønn.Kjønnverdi.UKJENT -> PdlPersonResultat.Person.Kjønn.UKJENT
                }
            ).ok()
        }
    }
    internal fun hentVergemålEllerFremtidsfullmakt(ident: String, callId: String): Result<PdlVergemålEllerFremtidsfullmaktResultat> {
        return request(hentVergemålQuery(ident), callId).map {
            convertResponseBody<PdlHentVergemålResponse>(it)
        }.map {
            val person = it.data.hentPerson
            if (person == null) PdlVergemålEllerFremtidsfullmaktResultat.FantIkkePerson.ok()
            else {
                val vergemåltyper = person.vergemaalEllerFremtidsfullmakt.map {
                    PdlVergemålEllerFremtidsfullmaktResultat.VergemålEllerFremtidsfullmakt.Vergemål(
                        type = when (it.type) {
                            PdlHentVergemålResponse.Vergemåltype.ensligMindreaarigAsylsoeker -> Vergemåltype.EnsligMindreårigAsylsøker
                            PdlHentVergemålResponse.Vergemåltype.ensligMindreaarigFlyktning -> Vergemåltype.EnsligMindreårigFlyktning
                            PdlHentVergemålResponse.Vergemåltype.voksen -> Vergemåltype.Voksen
                            PdlHentVergemålResponse.Vergemåltype.midlertidigForVoksen -> Vergemåltype.MidlertidigForVoksen
                            PdlHentVergemålResponse.Vergemåltype.mindreaarig -> Vergemåltype.Mindreårig
                            PdlHentVergemålResponse.Vergemåltype.midlertidigForMindreaarig -> Vergemåltype.MidlertidigForMindreårig
                            PdlHentVergemålResponse.Vergemåltype.forvaltningUtenforVergemaal -> Vergemåltype.ForvaltningUtenforVergemål
                            PdlHentVergemålResponse.Vergemåltype.stadfestetFremtidsfullmakt -> Vergemåltype.StadfestetFremtidsfullmakt
                        }

                    )
                }
                PdlVergemålEllerFremtidsfullmaktResultat.VergemålEllerFremtidsfullmakt(
                    vergemålEllerFremtidsfullmakter = vergemåltyper
                ).ok()
            }
        }
    }

    fun hentGeografiskTilknytning(ident: String, callId: String): Result<PdlGeografiskTilknytningResultat> {
        return request(hentGeografiskTilknytningQuery(ident), callId).map {
            convertResponseBody<PdlHentGeografiskTilknytningResponse>(it)
        }.map {
            when (it.gtType) {
                GeografiskTilknytningType.BYDEL -> when (it.gtBydel) {
                    null -> "Adressetypen er bydel, men bydel er null".error()
                    else -> PdlGeografiskTilknytningResultat.Bydel(it.gtBydel).ok()
                }
                GeografiskTilknytningType.KOMMUNE -> when (it.gtKommune) {
                    null -> "Adressetypen er kommune, men kommune er null".error()
                    else -> PdlGeografiskTilknytningResultat.Kommune(it.gtKommune).ok()
                }
                GeografiskTilknytningType.UTLAND -> when (it.gtLand) {
                    null -> PdlGeografiskTilknytningResultat.UtlandUkjentLand.ok()
                    else -> PdlGeografiskTilknytningResultat.Utland(it.gtLand).ok()
                }
                GeografiskTilknytningType.UDEFINERT -> PdlGeografiskTilknytningResultat.Udefinert.ok()
            }
        }
    }

    private fun request(query: PdlQueryObject, callId: String): Result<HttpResponse<String>> {
        return accessTokenClient.bearerToken(accessTokenScope).map { azureToken ->
            try {
                val body = objectMapper.writeValueAsString(query)
                val request = HttpRequest.newBuilder(URI.create(baseUrl))
                    .header("TEMA", "SYK")
                    .header("Authorization", "Bearer ${azureToken.token}")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Nav-Call-Id", callId)
                    .header("behandlingsnummer", "B139")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val responseHandler = HttpResponse.BodyHandlers.ofString()

                val response = httpClient.send(request, responseHandler)
                if (response.statusCode() != 200) "error (responseCode=${response.statusCode()}) from PDL".error()
                else response.ok()
            } catch (err: Exception) {
                err.error("Feil ved sending av http request")
            }
        }
    }

    private fun hentAlleIdenter(ident: String, historisk: Boolean, callId: String): Result<PdlIdenterResultat> {
        return request(hentIdenterQuery(ident, historisk), callId)
            .map {
                convertResponseBody<PdlHentIdenterResponse>(it)
            }
            .map {
                val identer = it.data.hentIdenter?.identer
                if (identer == null || identer.isEmpty()) PdlIdenterResultat.FantIkkeIdenter.ok()
                else {
                    val (historiske, gjeldende) = identer.partition { it.historisk }
                    PdlIdenterResultat.Identer(
                        gjeldende = gjeldende.map(::mapIdent),
                        historiske = historiske.map(::mapIdent)
                    ).ok()
                }
            }
    }

    private fun mapIdent(ident: PdlHentIdenterResponse.Ident) =
        when (ident.gruppe) {
            PdlHentIdenterResponse.Identgruppe.AKTORID -> Ident.AktørId(ident.ident)
            PdlHentIdenterResponse.Identgruppe.FOLKEREGISTERIDENT -> Ident.Fødselsnummer(ident.ident)
            PdlHentIdenterResponse.Identgruppe.NPID -> Ident.NPID(ident.ident)
        }

    private inline fun <reified T> convertResponseBody(response: HttpResponse<String>): Result<T> {
        return try {
            objectMapper.readValue<T>(response.body()).ok()
        } catch (err: Exception) {
            err.error(err.message ?: "JSON parsing error")
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PdlHentIdenterResponse(
    val data: PdlHentIdenter
) {
    data class PdlHentIdenter(
        val hentIdenter: Identer?
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
data class PdlHentVergemålResponse(
    val data: PdlHentPerson
) {
    data class PdlHentPerson(
        val hentPerson: Person?
    )
    data class Person(
        val vergemaalEllerFremtidsfullmakt: List<Vergemål>
    )
    data class Vergemål(
        val type: Vergemåltype
    )
    enum class Vergemåltype {
        ensligMindreaarigAsylsoeker,
        ensligMindreaarigFlyktning,
        voksen,
        midlertidigForVoksen,
        mindreaarig,
        midlertidigForMindreaarig,
        forvaltningUtenforVergemaal,
        stadfestetFremtidsfullmakt
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class PdlHentGeografiskTilknytningResponse(
    val gtType: GeografiskTilknytningType,
    val gtKommune: String?,
    val gtBydel: String?,
    val gtLand: String?
) {
    enum class GeografiskTilknytningType {
        BYDEL, KOMMUNE, UTLAND, @JsonEnumDefaultValue UDEFINERT
    }
}

sealed interface PdlGeografiskTilknytningResultat {
    data object Udefinert : PdlGeografiskTilknytningResultat
    data object UtlandUkjentLand : PdlGeografiskTilknytningResultat
    data class Utland(val land: String) : PdlGeografiskTilknytningResultat
    data class Kommune(val kommune: String) : PdlGeografiskTilknytningResultat
    data class Bydel(val bydel: String) : PdlGeografiskTilknytningResultat
}

sealed interface PdlVergemålEllerFremtidsfullmaktResultat {
    data class VergemålEllerFremtidsfullmakt(
        val vergemålEllerFremtidsfullmakter: List<Vergemål>
    ) : PdlVergemålEllerFremtidsfullmaktResultat {
        data class Vergemål(
            val type: Vergemåltype
        )
        enum class Vergemåltype {
            EnsligMindreårigAsylsøker,
            EnsligMindreårigFlyktning,
            Voksen,
            MidlertidigForVoksen,
            Mindreårig,
            MidlertidigForMindreårig,
            ForvaltningUtenforVergemål,
            StadfestetFremtidsfullmakt
        }
    }
    data object FantIkkePerson : PdlVergemålEllerFremtidsfullmaktResultat
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