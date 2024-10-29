package no.nav.helse.speed.api.pdl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private fun query(sti: String) = PdlClient::class.java.getResource(sti)!!.readText().replace(Regex("[\n\r]"), "")

class PdlClient(
    private val baseUrl: String,
    private val accessTokenClient: AzureTokenProvider,
    private val accessTokenScope: String,
    private val objectMapper: ObjectMapper
) {

    companion object {
        private val httpClient = HttpClient.newHttpClient()
        private val hentIdenterQuery = query("/pdl/hentIdenter.graphql")
        private val hentAlleIdenterQuery = query("/pdl/hentAlleIdenter.graphql")
    }

    private fun request(
        ident: String,
        callId: String,
        query: String
    ): JsonNode {
        val body = objectMapper.writeValueAsString(PdlQueryObject(query, Variables(ident)))

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
        response.statusCode().let {
            if (it >= 300) throw RuntimeException("error (responseCode=$it) from PDL")
        }
        return objectMapper.readTree(response.body())
    }

    internal fun hentIdenter(
        ident: String,
        callId: String
    ) = PdlOversetter.oversetterIdenter(request(ident, callId, hentIdenterQuery))

    internal fun hentAlleIdenter(
        ident: String,
        callId: String
    ) = PdlOversetter.oversetterAlleIdenter(request(ident, callId, hentAlleIdenterQuery))
}
