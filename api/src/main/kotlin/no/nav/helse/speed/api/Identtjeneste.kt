package no.nav.helse.speed.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import no.nav.helse.speed.api.IdenterResultat.FantIkkeIdenter
import no.nav.helse.speed.api.IdenterResultat.Identer
import no.nav.helse.speed.api.pdl.PdlClient
import no.nav.helse.speed.api.pdl.PdlIdenterResultat
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.SetParams
import java.security.MessageDigest
import java.util.UUID

class Identtjeneste(
    private val jedisPool: JedisPool,
    private val pdlClient: PdlClient,
    private val objectMapper: ObjectMapper
) {
    fun hentFødselsnummerOgAktørId(ident: String): IdenterResultat {
        return try {
            hentFraMellomlager(ident) ?: hentFraPDL(ident, UUID.randomUUID()) ?: FantIkkeIdenter
        } catch (err: Exception) {
            IdenterResultat.Feilmelding(err.message ?: "Ukjent feil", err)
        }
    }

    private fun hentFraMellomlager(ident: String): Identer? {
        return jedisPool.resource.use { jedis ->
            jedis.get(mellomlagringsnøkkel(ident))?.also {
                logg.info("hentet identer fra mellomlager")
            }?.let { objectMapper.readValue(it, Identer::class.java) }
        }
    }
    
    private fun hentFraPDL(ident: String, callId: UUID): IdenterResultat? {
        return when (val identer = pdlClient.hentIdenter(ident, callId.toString())) {
            is PdlIdenterResultat.Identer -> Identer(
                fødselsnummer = identer.fødselsnummer,
                aktørId = identer.aktørId,
                npid = identer.npid
            ).also {
                jedisPool.resource.use { jedis ->
                    logg.info("lagrer OBO-token i mellomlager")
                    jedis.set(mellomlagringsnøkkel(ident), objectMapper.writeValueAsString(it), SetParams.setParams().ex(IDENT_EXPIRATION_SECONDS))
                }
            }
            is PdlIdenterResultat.FantIkkeIdenter -> FantIkkeIdenter
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
        val npid: String? = null
    ): IdenterResultat

    data object FantIkkeIdenter: IdenterResultat
    data class Feilmelding(val melding: String, val årsak: Exception): IdenterResultat
}