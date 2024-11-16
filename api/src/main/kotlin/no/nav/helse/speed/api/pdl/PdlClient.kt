package no.nav.helse.speed.api.pdl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import com.github.navikt.tbd_libs.result_object.Result
import com.github.navikt.tbd_libs.result_object.error
import com.github.navikt.tbd_libs.result_object.map
import com.github.navikt.tbd_libs.result_object.ok
import no.nav.helse.speed.api.pdl.PdlResultat.*
import org.slf4j.LoggerFactory
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
    private companion object {
        private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")
    }
    internal fun hentIdenter(ident: String, callId: String) = hentAlleIdenter(ident, false, callId)
    internal fun hentAlleIdenter(ident: String, callId: String) = hentAlleIdenter(ident, true, callId)
    internal fun hentPerson(ident: String, callId: String): Result<PdlResultat<PdlPersoninfo>> {
        return request(hentPersonQuery(ident), callId).map {
            convertResponseBody<PdlPersonInfoDto>(it)
        }.map { person ->
            when (person) {
                is Ok -> when (person.value.navn.isEmpty()) {
                    true -> NotFound
                    false -> Ok(
                        PdlPersoninfo(
                            fødselsdato = person.value.foedselsdato.first().foedselsdato,
                            // foretrekker PDL dersom flere innslag
                            dødsdato = person.value.doedsfall.firstOrNull { it.metadata.master.lowercase() == "pdl" }?.doedsdato
                                ?: person.value.doedsfall.firstOrNull()?.doedsdato,
                            fornavn = person.value.navn.first().fornavn,
                            mellomnavn = person.value.navn.first().mellomnavn?.takeIf(String::isNotBlank),
                            etternavn = person.value.navn.first().etternavn,
                            adressebeskyttelse = when (person.value.adressebeskyttelse.firstOrNull()?.gradering) {
                                null, PdlPersonInfoDto.Adressebeskyttelse.Adressebeskyttelsegradering.UGRADERT -> PdlPersoninfo.Adressebeskyttelse.UGRADERT
                                PdlPersonInfoDto.Adressebeskyttelse.Adressebeskyttelsegradering.FORTROLIG -> PdlPersoninfo.Adressebeskyttelse.FORTROLIG
                                PdlPersonInfoDto.Adressebeskyttelse.Adressebeskyttelsegradering.STRENGT_FORTROLIG -> PdlPersoninfo.Adressebeskyttelse.STRENGT_FORTROLIG
                                PdlPersonInfoDto.Adressebeskyttelse.Adressebeskyttelsegradering.STRENGT_FORTROLIG_UTLAND -> PdlPersoninfo.Adressebeskyttelse.STRENGT_FORTROLIG_UTLAND
                            },
                            kjønn = when (person.value.kjoenn.firstOrNull()?.kjoenn) {
                                PdlPersonInfoDto.Kjønn.Kjønnverdi.MANN -> PdlPersoninfo.Kjønn.MANN
                                PdlPersonInfoDto.Kjønn.Kjønnverdi.KVINNE -> PdlPersoninfo.Kjønn.KVINNE
                                null, PdlPersonInfoDto.Kjønn.Kjønnverdi.UKJENT -> PdlPersoninfo.Kjønn.UKJENT
                            }
                        )
                    )
                }
                is BadRequest -> person
                is GenericError -> person
                is NotFound -> person
            }.ok()
        }
    }
    internal fun hentVergemålEllerFremtidsfullmakt(ident: String, callId: String): Result<PdlResultat<PdlVergemålEllerFremtidsfullmakt>> {
        return request(hentVergemålQuery(ident), callId).map {
            convertResponseBody<PdlVergemålEllerFremtidsfullmaktDto>(it)
        }.map { person ->
            when (person) {
                is BadRequest -> person
                is GenericError -> person
                is NotFound -> person
                is Ok -> {
                    val vergemåltyper = person.value.vergemaalEllerFremtidsfullmakt.map {
                        PdlVergemålEllerFremtidsfullmakt.Vergemål(
                            type = when (it.type) {
                                PdlVergemålEllerFremtidsfullmaktDto.Vergemåltype.ensligMindreaarigAsylsoeker -> PdlVergemålEllerFremtidsfullmakt.Vergemåltype.EnsligMindreårigAsylsøker
                                PdlVergemålEllerFremtidsfullmaktDto.Vergemåltype.ensligMindreaarigFlyktning -> PdlVergemålEllerFremtidsfullmakt.Vergemåltype.EnsligMindreårigFlyktning
                                PdlVergemålEllerFremtidsfullmaktDto.Vergemåltype.voksen -> PdlVergemålEllerFremtidsfullmakt.Vergemåltype.Voksen
                                PdlVergemålEllerFremtidsfullmaktDto.Vergemåltype.midlertidigForVoksen -> PdlVergemålEllerFremtidsfullmakt.Vergemåltype.MidlertidigForVoksen
                                PdlVergemålEllerFremtidsfullmaktDto.Vergemåltype.mindreaarig -> PdlVergemålEllerFremtidsfullmakt.Vergemåltype.Mindreårig
                                PdlVergemålEllerFremtidsfullmaktDto.Vergemåltype.midlertidigForMindreaarig -> PdlVergemålEllerFremtidsfullmakt.Vergemåltype.MidlertidigForMindreårig
                                PdlVergemålEllerFremtidsfullmaktDto.Vergemåltype.forvaltningUtenforVergemaal -> PdlVergemålEllerFremtidsfullmakt.Vergemåltype.ForvaltningUtenforVergemål
                                PdlVergemålEllerFremtidsfullmaktDto.Vergemåltype.stadfestetFremtidsfullmakt -> PdlVergemålEllerFremtidsfullmakt.Vergemåltype.StadfestetFremtidsfullmakt
                            }

                        )
                    }
                    Ok(PdlVergemålEllerFremtidsfullmakt(vergemåltyper))
                }
            }.ok()
        }
    }

    fun hentGeografiskTilknytning(ident: String, callId: String): Result<PdlResultat<PdlGeografiskTilknytning>> {
        return request(hentGeografiskTilknytningQuery(ident), callId).map {
            convertResponseBody<PdlGeografiskTilknytningDto>(it)
        }.map {
            when (it) {
                is BadRequest -> it
                is GenericError -> it
                is NotFound -> it
                is Ok -> Ok(
                    PdlGeografiskTilknytning(
                        type = when (it.value.gtType) {
                            PdlGeografiskTilknytningDto.GeografiskTilknytningType.BYDEL -> PdlGeografiskTilknytning.GeografiskTilknytningType.BYDEL
                            PdlGeografiskTilknytningDto.GeografiskTilknytningType.KOMMUNE -> PdlGeografiskTilknytning.GeografiskTilknytningType.KOMMUNE
                            PdlGeografiskTilknytningDto.GeografiskTilknytningType.UTLAND -> PdlGeografiskTilknytning.GeografiskTilknytningType.UTLAND
                            PdlGeografiskTilknytningDto.GeografiskTilknytningType.UDEFINERT -> PdlGeografiskTilknytning.GeografiskTilknytningType.UDEFINERT
                        },
                        bydel = it.value.gtBydel,
                        land = it.value.gtLand,
                        kommune = it.value.gtKommune
                    )
                )
            }.ok()
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

    private fun hentAlleIdenter(ident: String, historisk: Boolean, callId: String): Result<PdlResultat<PdlIdenter>> {
        return request(hentIdenterQuery(ident, historisk), callId)
            .map { convertResponseBody<PdlIdenterDto>(it) }
            .map {
                when (it) {
                    is BadRequest -> it
                    is GenericError -> it
                    is NotFound -> it
                    is Ok -> {
                        val (historiske, gjeldende) = it.value.identer.partition { it.historisk }
                        Ok(PdlIdenter(
                            gjeldende = gjeldende.map(::mapIdent),
                            historiske = historiske.map(::mapIdent)
                        ))
                    }
                }.ok()
            }
    }

    private fun mapIdent(ident: PdlIdenterDto.Ident) =
        when (ident.gruppe) {
            PdlIdenterDto.Identgruppe.AKTORID -> Ident.AktørId(ident.ident)
            PdlIdenterDto.Identgruppe.FOLKEREGISTERIDENT -> Ident.Fødselsnummer(ident.ident)
            PdlIdenterDto.Identgruppe.NPID -> Ident.NPID(ident.ident)
        }

    private inline fun <reified T> convertResponseBody(response: HttpResponse<String>): Result<PdlResultat<T>> {
        return try {
            val content = response.body()
            sikkerlogg.info("svar fra pdl: http ${response.statusCode()}:\n$content")
            objectMapper
                .readValue<PdlResponse<T>>(content)
                .result
                .ok()
        } catch (err: Exception) {
            err.error(err.message ?: "JSON parsing error")
        }
    }
}

// standard respons fra PDL
@JsonIgnoreProperties(ignoreUnknown = true)
data class PdlResponse<T>(
    val errors: List<PdlError>?,
    private val data: Map<String, T>
) {
    val valueOrNull get() = data.values.singleOrNull()

    val result: PdlResultat<T> get() = when(val verdi = valueOrNull) {
        null -> when {
            errors == null || errors.isEmpty() -> GenericError("Ukjent feil: body er null, men errors er også manglende", null)
            else -> when (val code = errors.first().extensions.code) {
                PdlExtensions.PdlErrorCode.NOT_FOUND -> NotFound
                PdlExtensions.PdlErrorCode.BAD_REQUEST -> BadRequest(errors.first().message)
                PdlExtensions.PdlErrorCode.UNAUTHENTICATED,
                PdlExtensions.PdlErrorCode.UNKNOWN -> GenericError(errors.first().message, code.name)
            }
        }
        else -> Ok(verdi)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PdlError(
        val message: String,
        val extensions: PdlExtensions
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PdlExtensions(
        val code: PdlErrorCode
    ) {
        enum class PdlErrorCode {
            @JsonProperty("not_found")
            NOT_FOUND,
            @JsonProperty("bad_request")
            BAD_REQUEST,
            @JsonProperty("unauthenticated")
            UNAUTHENTICATED,
            @JsonEnumDefaultValue
            UNKNOWN
        }
    }
}

sealed interface PdlResultat<out T> {
    data object NotFound : PdlResultat<Nothing>
    data class BadRequest(val error: String) : PdlResultat<Nothing>
    data class GenericError(val error: String, val code: String?) : PdlResultat<Nothing>
    data class Ok<T>(val value: T) : PdlResultat<T>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PdlIdenterDto(
    val identer: List<Ident>
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
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
data class PdlVergemålEllerFremtidsfullmaktDto(
    val vergemaalEllerFremtidsfullmakt: List<Vergemål>
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
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
data class PdlPersonInfoDto(
    val foedselsdato: List<Fødselsdato>,
    val navn: List<Navn>,
    val adressebeskyttelse: List<Adressebeskyttelse>,
    val kjoenn: List<Kjønn>,
    val doedsfall: List<Dødsfall>
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Fødselsdato(
        val foedselsdato: LocalDate
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Navn(
        val fornavn: String,
        val mellomnavn: String?,
        val etternavn: String
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Adressebeskyttelse(
        val gradering: Adressebeskyttelsegradering?
    ) {
        enum class Adressebeskyttelsegradering {
            FORTROLIG, STRENGT_FORTROLIG, STRENGT_FORTROLIG_UTLAND, @JsonEnumDefaultValue UGRADERT
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Kjønn(
        val kjoenn: Kjønnverdi
    ) {
        enum class Kjønnverdi {
            MANN, KVINNE, @JsonEnumDefaultValue UKJENT
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Dødsfall(
        val doedsdato: LocalDate,
        val metadata: Metadata
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Metadata(val master: String)
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PdlGeografiskTilknytningDto(
    val gtType: GeografiskTilknytningType,
    val gtKommune: String?,
    val gtBydel: String?,
    val gtLand: String?
) {
    enum class GeografiskTilknytningType {
        BYDEL, KOMMUNE, UTLAND, @JsonEnumDefaultValue UDEFINERT
    }
}

data class PdlGeografiskTilknytning(
    val type: GeografiskTilknytningType,
    val land: String?,
    val kommune: String?,
    val bydel: String?
) {
    enum class GeografiskTilknytningType {
        BYDEL, // bydel er ikke null
        KOMMUNE, // kommune er ikke null
        UTLAND,  // land kan være null
        UDEFINERT // alt er null
    }
}

data class PdlVergemålEllerFremtidsfullmakt(
    val vergemålEllerFremtidsfullmakter: List<Vergemål>
) {
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

data class PdlPersoninfo(
    val fødselsdato: LocalDate,
    val dødsdato: LocalDate?,
    val fornavn: String,
    val mellomnavn: String?,
    val etternavn: String,
    val adressebeskyttelse: Adressebeskyttelse,
    val kjønn: Kjønn
) {
    enum class Adressebeskyttelse {
        FORTROLIG, STRENGT_FORTROLIG, STRENGT_FORTROLIG_UTLAND, UGRADERT
    }
    enum class Kjønn {
        MANN, KVINNE, UKJENT
    }
}

data class PdlIdenter(
    val gjeldende: List<Ident>,
    val historiske: List<Ident>
) {
    val fødselsnummer = gjeldende.first { it is Ident.Fødselsnummer }.ident
    val aktørId = gjeldende.first { it is Ident.AktørId }.ident
    val npid = gjeldende.firstOrNull { it is Ident.NPID }?.ident
}

sealed class Ident(val ident: String) {
    class Fødselsnummer(ident: String) : Ident(ident)
    class AktørId(ident: String) : Ident(ident)
    class NPID(ident: String) : Ident(ident)
}