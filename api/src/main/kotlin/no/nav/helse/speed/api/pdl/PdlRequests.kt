package no.nav.helse.speed.api.pdl

data class PdlQueryObject(
        val query: String,
        val variables: Variables
)

data class Variables(
        val ident: String
)
