package no.nav.helse.speed.api.pdl

import com.fasterxml.jackson.databind.JsonNode
import no.nav.helse.speed.api.pdl.PdlIdenterResultat.FantIkkeIdenter
import no.nav.helse.speed.api.pdl.PdlIdenterResultat.Identer
import org.slf4j.LoggerFactory

internal object PdlOversetter {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")

    fun oversetterIdenter(pdlReply: JsonNode): PdlIdenterResultat {
        håndterErrors(pdlReply)
        val pdlPerson = pdlReply["data"]["hentIdenter"]["identer"]
        fun identAvType(type: String): String {
            val identgruppe = pdlPerson.firstOrNull { it["gruppe"].asText() == type }
                ?: run {
                    sikkerlogg.info("Finner ikke ident av type=$type i svaret fra PDL\n$pdlReply")
                    throw NoSuchElementException("Finner ikke ident av type=$type i svaret fra PDL")
                }
            return identgruppe["ident"].asText()
        }
        return try {
            Identer(identAvType("FOLKEREGISTERIDENT"), identAvType("AKTORID"))
        } catch (e: NoSuchElementException) {
            FantIkkeIdenter
        }
    }
    fun oversetterAlleIdenter(pdlReply: JsonNode): Pair<Identer, List<Ident>> {
        håndterErrors(pdlReply)
        val (aktiveIdenter, historiske) = pdlReply.path("data").path("hentIdenter").path("identer").partition {
            !it.path("historisk").asBoolean()
        }

        val fnr: String = aktiveIdenter.single { it.path("gruppe").asText() == "FOLKEREGISTERIDENT" }.path("ident").asText()
        val aktørId: String = aktiveIdenter.single { it.path("gruppe").asText() == "AKTORID" }.path("ident").asText()

        val npid = aktiveIdenter.firstOrNull { it.path("gruppe").asText() == "NPID" }?.path("ident")?.asText()
        return Identer(
            fødselsnummer = fnr,
            aktørId = aktørId,
            npid = npid,
        ) to historiske.map {
            val ident = it.path("ident").asText()
            when (val gruppe = it.path("gruppe").asText()) {
                "FOLKEREGISTERIDENT" -> Ident.Fødselsnummer(ident)
                "AKTORID" -> Ident.AktørId(ident)
                "NPID" -> Ident.NPID(ident)
                else -> throw RuntimeException("ukjent identgruppe: $gruppe")
            }
        }
    }

    internal fun håndterErrors(pdlReply: JsonNode) {
        if (pdlReply["errors"] != null && pdlReply["errors"].isArray && !pdlReply["errors"].isEmpty) {
            val errors = pdlReply["errors"].map { it["message"]?.textValue() ?: "no message" }
            throw RuntimeException(errors.joinToString())
        }
    }
}

sealed interface PdlIdenterResultat {
    data class Identer(
        val fødselsnummer: String,
        val aktørId: String,
        val npid: String? = null
    ): PdlIdenterResultat

    data object FantIkkeIdenter: PdlIdenterResultat
}

sealed class Ident(val ident: String) {
    class Fødselsnummer(ident: String) : Ident(ident)
    class AktørId(ident: String) : Ident(ident)
    class NPID(ident: String) : Ident(ident)
}