package no.nav.helse.speed.api

import com.fasterxml.jackson.databind.ObjectMapper
import io.prometheus.client.Counter
import no.nav.helse.speed.api.IdenterResultat.FantIkkeIdenter
import no.nav.helse.speed.api.IdenterResultat.Identer
import no.nav.helse.speed.api.IdenterResultat.Kilde
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
    private val objectMapper: ObjectMapper
) {
    fun hentPerson(ident: String, callId: String): PersonResultat {
        return try {
            when (val resultat = pdlClient.hentPerson(ident, callId))  {
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
                    }
                )
            }
        } catch (err: Exception) {
            PersonResultat.Feilmelding(err.message ?: "Ukjent feil", err)
        }
    }
    fun hentFødselsnummerOgAktørId(ident: String, callId: String): IdenterResultat {
        return try {
            hentFraMellomlager(ident) ?: hentFraPDL(ident, callId) ?: FantIkkeIdenter
        } catch (err: Exception) {
            IdenterResultat.Feilmelding(err.message ?: "Ukjent feil", err)
        }
    }

    fun hentHistoriskeFolkeregisterIdenter(ident: String, callId: String): HistoriskeIdenterResultat {
        return try {
            when (val svar = pdlClient.hentAlleIdenter(ident, callId)) {
                PdlIdenterResultat.FantIkkeIdenter -> HistoriskeIdenterResultat.FantIkkeIdenter
                is PdlIdenterResultat.Identer -> HistoriskeIdenterResultat.Identer(
                    fødselsnumre = svar.historiske.filterIsInstance<Ident.Fødselsnummer>().map { it.ident }
                )
            }
        } catch (err: Exception) {
            HistoriskeIdenterResultat.Feilmelding(err.message ?: "Ukjent feil", err)
        }
    }

    fun tømFraMellomlager(identer: List<String>): SlettResultat {
        return try {
            jedisPool.resource.use { jedis ->
                identer
                    .map { mellomlagringsnøkkel(it) }
                    .forEach { jedis.del(it) }
            }
            SlettResultat.Ok
        } catch (err: Exception) {
            SlettResultat.Feilmelding(err.message ?: "Ukjent feil", err)
        }
    }

    private fun hentFraMellomlager(ident: String): Identer? {
        return try {
            jedisPool.resource.use { jedis ->
                jedis.get(mellomlagringsnøkkel(ident))
                    ?.also { logg.info("hentet identer fra mellomlager") }
                    ?.let { objectMapper.readValue(it, Identer::class.java) }
                    ?.also { cachebruk.labels("lese").inc() }
            }
        } catch (err: Exception) {
            sikkerlogg.error("Kunne ikke koble til jedis, fall-backer til ingen cache: ${err.message}", err)
            null
        }
    }

    private fun hentFraPDL(ident: String, callId: String): IdenterResultat? {
        return when (val identer = pdlClient.hentIdenter(ident, callId)) {
            is PdlIdenterResultat.Identer -> Identer(
                fødselsnummer = identer.fødselsnummer,
                aktørId = identer.aktørId,
                npid = identer.npid,
                kilde = Kilde.PDL
            ).also { lagreTilMellomlager(ident, it) }
            is PdlIdenterResultat.FantIkkeIdenter -> FantIkkeIdenter
        }
    }

    private fun lagreTilMellomlager(ident: String, resultat: Identer) {
        try {
            jedisPool.resource.use { jedis ->
                logg.info("lagrer pdl-svar i mellomlager")
                jedis.set(mellomlagringsnøkkel(ident), objectMapper.writeValueAsString(resultat.copy(kilde = Kilde.CACHE)), SetParams.setParams().ex(IDENT_EXPIRATION_SECONDS))
            }
            cachebruk.labels("skrive").inc()
        } catch (err: Exception) {
            sikkerlogg.error("Kunne ikke koble til jedis, fall-backer til ingen cache: ${err.message}", err)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun mellomlagringsnøkkel(ident: String): String {
        val nøkkel = "$CACHE_PREFIX$ident".toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(nøkkel)
        return digest.toHexString()
    }

    private companion object {
        private const val CACHE_PREFIX = "ident_"
        private val IDENT_EXPIRATION_SECONDS: Long = Duration.ofDays(7).toSeconds()

        private val logg = LoggerFactory.getLogger(this::class.java)
        private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")

        private val cachebruk = Counter
            .build()
            .name("cachebruk")
            .labelNames("operasjon")
            .help("Teller hvor mange ganger vi bruker cachen")
            .register()
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

    enum class Kilde {
        CACHE, PDL
    }
}
sealed interface HistoriskeIdenterResultat {
    data class Identer(val fødselsnumre: List<String>): HistoriskeIdenterResultat
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
        val kjønn: Kjønn
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