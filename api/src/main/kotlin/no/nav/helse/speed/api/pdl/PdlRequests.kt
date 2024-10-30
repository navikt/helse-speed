package no.nav.helse.speed.api.pdl

private fun lastSkjema(sti: String) = PdlQueryObject::class.java.getResource(sti)!!.readText().replace(Regex("[\n\r]"), "")

private val hentIdenterQuery = lastSkjema("/pdl/hentIdenter.graphql")

data class PdlQueryObject(
    val query: String,
    val variables: Map<String, Any>
)

fun hentIdenterQuery(ident: String, historikk: Boolean) =
    PdlQueryObject(
        query = hentIdenterQuery,
        variables = mapOf(
            "ident" to ident,
            "historikk" to historikk
        )
    )