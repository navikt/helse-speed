package no.nav.helse.speed.api

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.navikt.tbd_libs.azure.AzureToken
import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import com.github.navikt.tbd_libs.result_object.Result
import com.github.navikt.tbd_libs.result_object.ok
import io.mockk.every
import io.mockk.mockk
import no.nav.helse.speed.api.pdl.PdlClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime

class PdlClientFeilhåndteringTest {
    private companion object {
        private const val IDENT = "12345678911"
    }

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Test
    fun `tar vare på exception-type og melding når http-kallet feiler`() {
        val exception = IOException("Connection reset by peer (host=pdl-api.prod-fss-pub.nais.io)")
        val feil = assertError(exception)

        assertTrue(feil.error.contains("IOException")) { "forventet exception-typen i feilmeldingen, fikk ${feil.error}" }
        assertTrue(feil.error.contains("Connection reset by peer")) { "forventet underliggende melding i feilmeldingen, fikk ${feil.error}" }
        assertEquals(exception, feil.cause)
    }

    @Test
    fun `maskerer ident i feilmeldingen`() {
        val feil = assertError(IOException("klarte ikke slå opp $IDENT"))

        assertFalse(feil.error.contains(IDENT)) { "ident skal ikke lekke til konsumentene, fikk ${feil.error}" }
        assertTrue(feil.error.contains("<maskert>")) { "forventet maskert ident, fikk ${feil.error}" }
    }

    private fun assertError(exception: Exception): Result.Error {
        val resultat = pdlClient(exception).hentGeografiskTilknytning(IDENT, "en-call-id")
        return assertInstanceOf(Result.Error::class.java, resultat)
    }

    private fun pdlClient(exception: Exception) = PdlClient(
        baseUrl = "http://pdl",
        accessTokenClient = mockk<AzureTokenProvider> {
            every { bearerToken(any()) } returns AzureToken("et token", LocalDateTime.MAX).ok()
        },
        accessTokenScope = "et scope",
        objectMapper = objectMapper,
        httpClient = mockk<HttpClient> {
            every {
                send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
            } throws exception
        }
    )
}
