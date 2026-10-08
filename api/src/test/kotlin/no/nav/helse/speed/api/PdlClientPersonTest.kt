package no.nav.helse.speed.api

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.navikt.tbd_libs.azure.AzureToken
import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import com.github.navikt.tbd_libs.mock.MockHttpResponse
import com.github.navikt.tbd_libs.result_object.getOrThrow
import com.github.navikt.tbd_libs.result_object.ok
import io.mockk.every
import io.mockk.mockk
import no.nav.helse.speed.api.pdl.PdlClient
import no.nav.helse.speed.api.pdl.PdlResultat
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.LocalDateTime

class PdlClientPersonTest {
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Test
    fun `fødselsdato-innslag uten fødselsdato ignoreres`() {
        val resultat = pdlClient(svarMedFødselsdatoUtenDato).hentPerson("12345678911", "en-call-id").getOrThrow()
        assertTrue(resultat is PdlResultat.Ok) { "forventet Ok, fikk $resultat" }
        assertEquals(LocalDate.of(1992, 9, 16), (resultat as PdlResultat.Ok).value.fødselsdato)
    }

    private fun pdlClient(body: String) = PdlClient(
        baseUrl = "http://pdl",
        accessTokenClient = mockk<AzureTokenProvider> {
            every { bearerToken(any()) } returns AzureToken("et token", LocalDateTime.MAX).ok()
        },
        accessTokenScope = "et scope",
        objectMapper = objectMapper,
        httpClient = mockk<HttpClient> {
            every {
                send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
            } returns MockHttpResponse(body, 200)
        }
    )
}

@Language("JSON")
private val svarMedFødselsdatoUtenDato = """{
    "data": {
        "hentPerson": {
            "foedselsdato": [
                { "foedselsdato": null },
                { "foedselsdato": "1992-09-16" }
            ],
            "navn": [ { "fornavn": "FORNØYD", "mellomnavn": null, "etternavn": "FISK" } ],
            "adressebeskyttelse": [],
            "kjoenn": [ { "kjoenn": "MANN" } ],
            "doedsfall": []
        }
    }
}"""
