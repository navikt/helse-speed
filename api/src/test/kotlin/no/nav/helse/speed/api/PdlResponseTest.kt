package no.nav.helse.speed.api

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import no.nav.helse.speed.api.PdlResponseTest.HentGeografiskTilknytning.GeografiskTilknytningType
import no.nav.helse.speed.api.pdl.PdlResponse
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

class PdlResponseTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun ok() {
        val result = objectMapper.readValue<PdlResponse<HentGeografiskTilknytning>>(okResponse)
        val value = result.valueOrNull!!
        assertEquals(GeografiskTilknytningType.KOMMUNE, value.gtType)
        assertEquals("3112", value.gtKommune)
        assertNull(value.gtLand)
        assertNull(value.gtBydel)
    }

    @Test
    fun error() {
        val result = objectMapper.readValue<PdlResponse<HentGeografiskTilknytning>>(errorResponse)
        assertNull(result.valueOrNull)
        assertNotNull(result.errors)
        val errors = result.errors as List<PdlResponse.PdlError>
        assertEquals(1, errors.size)
        assertEquals("Fant ikke person", errors[0].message)
        assertEquals(PdlResponse.PdlExtensions.PdlErrorCode.NOT_FOUND, errors[0].extensions.code)
    }

    private class HentGeografiskTilknytning(
        val gtType: GeografiskTilknytningType,
        val gtKommune: String?,
        val gtBydel: String?,
        val gtLand: String?,
    ) {
        enum class GeografiskTilknytningType {
            BYDEL,
            KOMMUNE,
            UTLAND,

            @JsonEnumDefaultValue UDEFINERT,
        }
    }
}

@Language("JSON")
private val errorResponse = """{
    "errors": [
        {
            "message": "Fant ikke person",
            "locations": [
                {
                    "line": 2,
                    "column": 5
                }
            ],
            "path": [
                "hentGeografiskTilknytning"
            ],
            "extensions": {
                "code": "not_found",
                "classification": "ExecutionAborted"
            }
        }
    ],
    "data": {
        "hentGeografiskTilknytning": null
    }
}"""

@Language("JSON")
private val okResponse = """{
    "data": {
        "hentGeografiskTilknytning": {
            "gtType": "KOMMUNE",
            "gtKommune": "3112",
            "gtBydel": null,
            "gtLand": null
        }
    }
}"""
