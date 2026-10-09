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
import no.nav.helse.speed.api.pdl.PdlPersoninfo
import no.nav.helse.speed.api.pdl.PdlResultat
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertIs

class PdlClientHentPersonTest {
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Test
    fun `null-verdi blant flere foedselsdato-innslag fra pdl gir ikke feil`() {
        val person = assertOk(hentPerson(svarMedNullOgGyldigFoedselsdato))
        assertEquals(LocalDate.of(1990, 1, 1), person.fødselsdato)
    }

    private fun assertOk(resultat: PdlResultat<PdlPersoninfo>): PdlPersoninfo {
        assertIs<PdlResultat.Ok<PdlPersoninfo>>(resultat)
        return resultat.value
    }

    private fun hentPerson(body: String) = pdlClient(body).hentPerson("12345678911", "en-call-id").getOrThrow()

    private fun pdlClient(body: String) =
        PdlClient(
            baseUrl = "http://pdl",
            accessTokenClient =
                mockk<AzureTokenProvider> {
                    every { bearerToken(any()) } returns AzureToken("et token", LocalDateTime.MAX).ok()
                },
            accessTokenScope = "scope",
            objectMapper = objectMapper,
            httpClient =
                mockk<HttpClient> {
                    every {
                        send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
                    } returns MockHttpResponse(body, 200)
                },
        )
}

@Language("JSON")
private val svarMedNullOgGyldigFoedselsdato = """{
    "data": {
        "hentPerson": {
            "foedselsdato": [
                { "foedselsdato": null },
                { "foedselsdato": "1990-01-01" }
            ],
            "navn": [
                { "fornavn": "Kari", "mellomnavn": null, "etternavn": "Nordmann" }
            ],
            "adressebeskyttelse": [],
            "kjoenn": [],
            "doedsfall": []
        }
    }
}"""
