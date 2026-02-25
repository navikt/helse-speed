package no.nav.helse.speed.api

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.navikt.tbd_libs.naisful.NaisEndpoints
import com.github.navikt.tbd_libs.naisful.test.TestContext
import com.github.navikt.tbd_libs.naisful.test.naisfulTestApp
import com.github.navikt.tbd_libs.result_object.ok
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.mockk
import no.nav.helse.speed.api.PersonResultat.Person.Adressebeskyttelse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class E2ETest {

    @Test
    fun `api - alle identer`() {
        val identtjeneste = mockk<Identtjeneste> {
            every { hentAlleIdenter("fnr", any()) } returns AlleIdenterResultat.Identer(
                identer = listOf(
                    AlleIdenterResultat.Ident("12345678901", AlleIdenterResultat.IdentType.FOLKEREGISTERIDENT, gjeldende = true),
                    AlleIdenterResultat.Ident("1234567890123", AlleIdenterResultat.IdentType.AKTORID, gjeldende = true),
                    AlleIdenterResultat.Ident("11111678901", AlleIdenterResultat.IdentType.FOLKEREGISTERIDENT, gjeldende = false)
                ),
                kilde = Kilde.PDL
            ).ok()
        }
        speedTestApp(identtjeneste) {
            val response = sendAlleIdenterRequest("fnr")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("FOLKEREGISTERIDENT"))
            assertTrue(body.contains("AKTORID"))
            assertTrue(body.contains("PDL"))
        }
    }

    @Test
    fun `api - alle identer fra cache`() {
        val identtjeneste = mockk<Identtjeneste> {
            every { hentAlleIdenter("fnr", any()) } returns AlleIdenterResultat.Identer(
                identer = listOf(
                    AlleIdenterResultat.Ident("12345678901", AlleIdenterResultat.IdentType.FOLKEREGISTERIDENT, gjeldende = true)
                ),
                kilde = Kilde.CACHE
            ).ok()
        }
        speedTestApp(identtjeneste) {
            val response = sendAlleIdenterRequest("fnr")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("CACHE"))
        }
    }

    @Test
    fun `api - alle identer - fant ikke ident`() {
        val identtjeneste = mockk<Identtjeneste> {
            every { hentAlleIdenter("ukjent", any()) } returns AlleIdenterResultat.FantIkkeIdenter.ok()
        }
        speedTestApp(identtjeneste) {
            val response = sendAlleIdenterRequest("ukjent")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `api - person`() {
        val identtjeneste = mockk<Identtjeneste> {
            every { hentPerson("fnr", any()) } returns PersonResultat.Person(
                fødselsdato = LocalDate.of(1992, 9, 16),
                dødsdato = LocalDate.of(2084, 10, 24),
                fornavn = "FORNØYD",
                mellomnavn = "MELLOM",
                etternavn = "FISK",
                adressebeskyttelse = Adressebeskyttelse.UGRADERT,
                kjønn = PersonResultat.Person.Kjønn.MANN,
                kilde = Kilde.PDL
            ).ok()
        }
        speedTestApp(identtjeneste) {
            val response = sendPersonRequest("fnr")
            assertEquals(response.status, HttpStatusCode.OK)
        }
    }

    private fun speedTestApp(identtjeneste: Identtjeneste, testblokk: suspend TestContext.() -> Unit) {
        naisfulTestApp(
            testApplicationModule = {
                routing {
                    api(identtjeneste)
                }
            },
            objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule()),
            meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
            naisEndpoints = NaisEndpoints.Default,
            callIdHeaderName = "callId",
            testblokk = testblokk
        )
    }
}

suspend fun TestContext.sendAlleIdenterRequest(ident: String): HttpResponse {
    return client.post("/api/alle_identer") {
        contentType(ContentType.Application.Json)
        setBody(IdentRequest(ident = ident))
    }
}

suspend fun TestContext.sendPersonRequest(ident: String): HttpResponse {
    return client.post("/api/person") {
        contentType(ContentType.Application.Json)
        setBody(IdentRequest(ident = ident))
    }
}