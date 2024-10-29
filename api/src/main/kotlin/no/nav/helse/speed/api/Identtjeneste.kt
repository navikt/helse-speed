package no.nav.helse.speed.api

import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.helse.speed.api.IdenterResultat.FantIkkeIdenter
import no.nav.helse.speed.api.IdenterResultat.Identer
import no.nav.helse.speed.api.IdenterResultat.Kilde
import no.nav.helse.speed.api.pdl.PdlClient
import no.nav.helse.speed.api.pdl.PdlIdenterResultat
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.SetParams
import java.security.MessageDigest

class Identtjeneste(
    private val jedisPool: JedisPool,
    private val pdlClient: PdlClient,
    private val objectMapper: ObjectMapper
) {
    fun hentFødselsnummerOgAktørId(ident: String, callId: String): IdenterResultat {
        return try {
            hentFraMellomlager(ident) ?: hentFraPDL(ident, callId) ?: FantIkkeIdenter
        } catch (err: Exception) {
            IdenterResultat.Feilmelding(err.message ?: "Ukjent feil", err)
        }
    }

    private fun hentFraMellomlager(ident: String): Identer? {
        return try {
            jedisPool.resource.use { jedis ->
                jedis.get(mellomlagringsnøkkel(ident))?.also {
                    logg.info("hentet identer fra mellomlager")
                }?.let { objectMapper.readValue(it, Identer::class.java) }
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
        private const val IDENT_EXPIRATION_SECONDS: Long = 3600

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

    enum class Kilde {
        CACHE, PDL
    }
}