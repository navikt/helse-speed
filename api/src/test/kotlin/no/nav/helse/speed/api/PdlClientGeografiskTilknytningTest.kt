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
import no.nav.helse.speed.api.pdl.PdlGeografiskTilknytning
import no.nav.helse.speed.api.pdl.PdlResultat
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime

class PdlClientGeografiskTilknytningTest {
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Test
    fun `tomt svar uten errors gir udefinert geografisk tilknytning`() {
        val resultat = hentGeografiskTilknytning(tomtSvar)
        val geografiskTilknytning = assertOk(resultat)
        assertEquals(PdlGeografiskTilknytning.GeografiskTilknytningType.UDEFINERT, geografiskTilknytning.type)
        assertNull(geografiskTilknytning.land)
        assertNull(geografiskTilknytning.kommune)
        assertNull(geografiskTilknytning.bydel)
    }

    @Test
    fun `tomt data-kart gir generisk feil`() {
        val resultat = hentGeografiskTilknytning(tomtDataKart)
        assertTrue(resultat is PdlResultat.GenericError) { "forventet GenericError, fikk $resultat" }
    }

    @Test
    fun `not_found gir NotFound`() {
        val resultat = hentGeografiskTilknytning(notFoundSvar)
        assertEquals(PdlResultat.NotFound, resultat)
    }

    @Test
    fun `bad_request gir BadRequest`() {
        val resultat = hentGeografiskTilknytning(badRequestSvar)
        assertTrue(resultat is PdlResultat.BadRequest) { "forventet BadRequest, fikk $resultat" }
    }

    @Test
    fun `svar med kommune`() {
        val geografiskTilknytning = assertOk(hentGeografiskTilknytning(kommuneSvar))
        assertEquals(PdlGeografiskTilknytning.GeografiskTilknytningType.KOMMUNE, geografiskTilknytning.type)
        assertEquals("3112", geografiskTilknytning.kommune)
        assertNull(geografiskTilknytning.bydel)
        assertNull(geografiskTilknytning.land)
    }

    @Test
    fun `svar med bydel`() {
        val geografiskTilknytning = assertOk(hentGeografiskTilknytning(bydelSvar))
        assertEquals(PdlGeografiskTilknytning.GeografiskTilknytningType.BYDEL, geografiskTilknytning.type)
        assertEquals("030102", geografiskTilknytning.bydel)
        assertNull(geografiskTilknytning.kommune)
        assertNull(geografiskTilknytning.land)
    }

    @Test
    fun `svar med utland`() {
        val geografiskTilknytning = assertOk(hentGeografiskTilknytning(utlandSvar))
        assertEquals(PdlGeografiskTilknytning.GeografiskTilknytningType.UTLAND, geografiskTilknytning.type)
        assertEquals("SWE", geografiskTilknytning.land)
        assertNull(geografiskTilknytning.kommune)
        assertNull(geografiskTilknytning.bydel)
    }

    private fun assertOk(resultat: PdlResultat<PdlGeografiskTilknytning>): PdlGeografiskTilknytning {
        assertTrue(resultat is PdlResultat.Ok) { "forventet Ok, fikk $resultat" }
        return (resultat as PdlResultat.Ok).value
    }

    private fun hentGeografiskTilknytning(body: String) =
        pdlClient(body).hentGeografiskTilknytning("12345678911", "en-call-id").getOrThrow()

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
private val tomtSvar = """{
    "data": {
        "hentGeografiskTilknytning": null
    }
}"""

@Language("JSON")
private val tomtDataKart = """{
    "data": {}
}"""

@Language("JSON")
private val notFoundSvar = """{
    "errors": [
        {
            "message": "Fant ikke person",
            "extensions": { "code": "not_found" }
        }
    ],
    "data": {
        "hentGeografiskTilknytning": null
    }
}"""

@Language("JSON")
private val badRequestSvar = """{
    "errors": [
        {
            "message": "Ugyldig ident",
            "extensions": { "code": "bad_request" }
        }
    ],
    "data": {
        "hentGeografiskTilknytning": null
    }
}"""

@Language("JSON")
private val kommuneSvar = """{
    "data": {
        "hentGeografiskTilknytning": {
            "gtType": "KOMMUNE",
            "gtKommune": "3112",
            "gtBydel": null,
            "gtLand": null
        }
    }
}"""

@Language("JSON")
private val bydelSvar = """{
    "data": {
        "hentGeografiskTilknytning": {
            "gtType": "BYDEL",
            "gtKommune": null,
            "gtBydel": "030102",
            "gtLand": null
        }
    }
}"""

@Language("JSON")
private val utlandSvar = """{
    "data": {
        "hentGeografiskTilknytning": {
            "gtType": "UTLAND",
            "gtKommune": null,
            "gtBydel": null,
            "gtLand": "SWE"
        }
    }
}"""
