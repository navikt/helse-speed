package no.nav.helse.speed.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.helse.speed.api.IdenterResultat.FantIkkeIdenter
import no.nav.helse.speed.api.IdenterResultat.Identer
import no.nav.helse.speed.api.pdl.Ident
import no.nav.helse.speed.api.pdl.PdlClient
import no.nav.helse.speed.api.pdl.PdlIdenterResultat
import no.nav.helse.speed.api.pdl.PdlPersonResultat
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.SetParams
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate

class Identtjeneste(
    private val jedisPool: JedisPool,
    private val pdlClient: PdlClient,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: PrometheusMeterRegistry
) {
    fun hentPerson(ident: String, callId: String): PersonResultat {
        return try {
            hentPersonFraMellomlager(ident) ?: hentPersonFraPDL(ident, callId)
        } catch (err: Exception) {
            PersonResultat.Feilmelding(err.message ?: "Ukjent feil", err)
        }
    }
    fun hentFødselsnummerOgAktørId(ident: String, callId: String): IdenterResultat {
        return try {
            hentIdentFraMellomlager(ident) ?: hentFraPDL(ident, callId) ?: FantIkkeIdenter
        } catch (err: Exception) {
            IdenterResultat.Feilmelding(err.message ?: "Ukjent feil", err)
        }
    }

    fun hentHistoriskeFolkeregisterIdenter(ident: String, callId: String): HistoriskeIdenterResultat {
        return try {
            hentHistoriskeIdenterFraMellomlager(ident) ?: hentHistoriskeIdenterFraPDL(ident, callId)
        } catch (err: Exception) {
            HistoriskeIdenterResultat.Feilmelding(err.message ?: "Ukjent feil", err)
        }
    }

    fun tømFraMellomlager(identer: List<String>): SlettResultat {
        return try {
            jedisPool.resource.use { jedis ->
                identer
                    .flatMap {
                        listOf(
                            mellomlagringsnøkkel(CACHE_PREFIX_IDENTOPPSLAG, it),
                            mellomlagringsnøkkel(CACHE_PREFIX_PERSONINFOOPPSLAG, it),
                            mellomlagringsnøkkel(CACHE_PREFIX_HISTORISKE_IDENTEROPPSLAG, it)
                        )
                    }
                    .forEach { jedis.del(it) }
            }
            SlettResultat.Ok
        } catch (err: Exception) {
            SlettResultat.Feilmelding(err.message ?: "Ukjent feil", err)
        }
    }

    private fun hentPersonFraMellomlager(ident: String): PersonResultat.Person? {
        return hentFraMellomlager(mellomlagringsnøkkel(CACHE_PREFIX_PERSONINFOOPPSLAG, ident))
    }
    private fun hentIdentFraMellomlager(ident: String): Identer? {
        return hentFraMellomlager(mellomlagringsnøkkel(CACHE_PREFIX_IDENTOPPSLAG, ident))
    }
    private fun hentHistoriskeIdenterFraMellomlager(ident: String): HistoriskeIdenterResultat.Identer? {
        return hentFraMellomlager(mellomlagringsnøkkel(CACHE_PREFIX_HISTORISKE_IDENTEROPPSLAG, ident))
    }

    private inline fun <reified T> hentFraMellomlager(cacheKey: String): T? {
        return try {
            jedisPool.resource.use { jedis ->
                jedis.get(cacheKey)
                    ?.also { logg.info("hentet svar fra mellomlager") }
                    ?.let { objectMapper.readTree(it) }
                    ?.let { objectMapper.convertValue<T>(it) }
                    ?.also { Metrikkverdi(T::class.simpleName ?: "UKJENT", Metrikkverdi.Oppslagoperasjon.LESE).økTeller(meterRegistry) }
            }
        } catch (err: Exception) {
            sikkerlogg.error("Kunne ikke koble til jedis, fall-backer til ingen cache: ${err.message}", err)
            null
        }
    }

    private fun hentHistoriskeIdenterFraPDL(ident: String, callId: String): HistoriskeIdenterResultat {
        return when (val svar = pdlClient.hentAlleIdenter(ident, callId)) {
            PdlIdenterResultat.FantIkkeIdenter -> HistoriskeIdenterResultat.FantIkkeIdenter
            is PdlIdenterResultat.Identer -> HistoriskeIdenterResultat.Identer(
                fødselsnumre = svar.historiske.filterIsInstance<Ident.Fødselsnummer>().map { it.ident },
                kilde = Kilde.PDL
            ).also {
                lagreHistoriskeIdenterTilMellomlager(ident, it)
            }
        }
    }

    private fun hentPersonFraPDL(ident: String, callId: String): PersonResultat {
        return when (val resultat = pdlClient.hentPerson(ident, callId))  {
            PdlPersonResultat.FantIkkePerson -> PersonResultat.FantIkkePerson
            is PdlPersonResultat.Person -> PersonResultat.Person(
                fødselsdato = resultat.fødselsdato,
                dødsdato = resultat.dødsdato,
                fornavn = resultat.fornavn,
                mellomnavn = resultat.mellomnavn,
                etternavn = resultat.etternavn,
                adressebeskyttelse = when (resultat.adressebeskyttelse) {
                    PdlPersonResultat.Person.Adressebeskyttelse.FORTROLIG -> PersonResultat.Person.Adressebeskyttelse.FORTROLIG
                    PdlPersonResultat.Person.Adressebeskyttelse.STRENGT_FORTROLIG -> PersonResultat.Person.Adressebeskyttelse.STRENGT_FORTROLIG
                    PdlPersonResultat.Person.Adressebeskyttelse.STRENGT_FORTROLIG_UTLAND -> PersonResultat.Person.Adressebeskyttelse.STRENGT_FORTROLIG_UTLAND
                    PdlPersonResultat.Person.Adressebeskyttelse.UGRADERT -> PersonResultat.Person.Adressebeskyttelse.UGRADERT
                },
                kjønn = when (resultat.kjønn) {
                    PdlPersonResultat.Person.Kjønn.MANN -> PersonResultat.Person.Kjønn.MANN
                    PdlPersonResultat.Person.Kjønn.KVINNE -> PersonResultat.Person.Kjønn.KVINNE
                    PdlPersonResultat.Person.Kjønn.UKJENT -> PersonResultat.Person.Kjønn.UKJENT
                },
                kilde = Kilde.PDL
            ).also { lagrePersonTilMellomlager(ident, it) }
        }
    }

    private fun hentFraPDL(ident: String, callId: String): IdenterResultat? {
        return when (val identer = pdlClient.hentIdenter(ident, callId)) {
            is PdlIdenterResultat.Identer -> Identer(
                fødselsnummer = identer.fødselsnummer,
                aktørId = identer.aktørId,
                npid = identer.npid,
                kilde = Kilde.PDL
            ).also { lagreIdentTilMellomlager(ident, it) }
            is PdlIdenterResultat.FantIkkeIdenter -> FantIkkeIdenter
        }
    }

    private fun lagrePersonTilMellomlager(ident: String, resultat: PersonResultat.Person) {
        lagreTilMellomlager(mellomlagringsnøkkel(CACHE_PREFIX_PERSONINFOOPPSLAG, ident), resultat.copy(kilde = Kilde.CACHE))
    }

    private fun lagreHistoriskeIdenterTilMellomlager(ident: String, resultat: HistoriskeIdenterResultat.Identer) {
        lagreTilMellomlager(mellomlagringsnøkkel(CACHE_PREFIX_HISTORISKE_IDENTEROPPSLAG, ident), resultat.copy(kilde = Kilde.CACHE))
    }

    private fun lagreIdentTilMellomlager(ident: String, resultat: Identer) {
        lagreTilMellomlager(mellomlagringsnøkkel(CACHE_PREFIX_IDENTOPPSLAG, ident), resultat.copy(kilde = Kilde.CACHE))
    }

    private inline fun <reified T> lagreTilMellomlager(cacheKey: String, resultat: T) {
        try {
            jedisPool.resource.use { jedis ->
                logg.info("lagrer pdl-svar i mellomlager")
                jedis.set(cacheKey, objectMapper.writeValueAsString(resultat), SetParams.setParams().ex(IDENT_EXPIRATION_SECONDS))
            }
            Metrikkverdi(T::class.simpleName ?: "UKJENT", Metrikkverdi.Oppslagoperasjon.SKRIVE).økTeller(meterRegistry)
        } catch (err: Exception) {
            sikkerlogg.error("Kunne ikke koble til jedis, fall-backer til ingen cache: ${err.message}", err)
        }
    }

    data class Metrikkverdi(
        val oppslag: String,
        val operasjon: Oppslagoperasjon
    ) {
        enum class Oppslagoperasjon { LESE, SKRIVE }

        fun økTeller(meterRegistry: MeterRegistry) {
            Counter.builder("cachebruk")
                .description("Teller hvor mange ganger vi bruker cachen")
                .tag("operasjon", operasjon.name.lowercase())
                .tag("oppslag", oppslag)
                .register(meterRegistry)
                .increment()
        }

    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun mellomlagringsnøkkel(prefix: String, ident: String): String {
        val nøkkel = "$prefix$ident".toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(nøkkel)
        return digest.toHexString()
    }

    private companion object {
        private const val CACHE_PREFIX_IDENTOPPSLAG = "ident_"
        private const val CACHE_PREFIX_PERSONINFOOPPSLAG = "personinfo_"
        private const val CACHE_PREFIX_HISTORISKE_IDENTEROPPSLAG = "historiske_identer_"
        private val IDENT_EXPIRATION_SECONDS: Long = Duration.ofDays(7).toSeconds()

        private val logg = LoggerFactory.getLogger(this::class.java)
        private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")
    }
}

sealed interface IdenterResultat {
    data class Identer(
        val fødselsnummer: String,
        val aktørId: String,
        val npid: String? = null,
        val kilde: Kilde
    ): IdenterResultat

    data object FantIkkeIdenter: IdenterResultat
    data class Feilmelding(val melding: String, val årsak: Exception): IdenterResultat
}
enum class Kilde {
    CACHE, PDL
}
sealed interface HistoriskeIdenterResultat {
    data class Identer(
        val fødselsnumre: List<String>,
        val kilde: Kilde
    ): HistoriskeIdenterResultat
    data object FantIkkeIdenter: HistoriskeIdenterResultat
    data class Feilmelding(val melding: String, val årsak: Exception): HistoriskeIdenterResultat
}

sealed interface PersonResultat {
    data class Person(
        val fødselsdato: LocalDate,
        val dødsdato: LocalDate?,
        val fornavn: String,
        val mellomnavn: String?,
        val etternavn: String,
        val adressebeskyttelse: Adressebeskyttelse,
        val kjønn: Kjønn,
        val kilde: Kilde
    ): PersonResultat {
        enum class Adressebeskyttelse {
            FORTROLIG, STRENGT_FORTROLIG, STRENGT_FORTROLIG_UTLAND, UGRADERT
        }
        enum class Kjønn {
            MANN, KVINNE, UKJENT
        }
    }

    data object FantIkkePerson: PersonResultat
    data class Feilmelding(val melding: String, val årsak: Exception): PersonResultat
}

sealed interface SlettResultat {
    data object Ok: SlettResultat
    data class Feilmelding(val melding: String, val årsak: Exception): SlettResultat
}