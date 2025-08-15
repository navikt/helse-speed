package no.nav.helse.speed.async

import java.util.Base64
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.apache.kafka.common.serialization.Deserializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class LeesahDeserializer : Deserializer<GenericRecord> {
    private val decoderFactory: DecoderFactory = DecoderFactory.get()

    private val tilgjengeligeSkjemaVersjoner = listOf("V5", "V4", "V3", "V2")
        .map { versjon ->
            versjon to versjon.lastSkjema()
        }

    override fun deserialize(topic: String, data: ByteArray): GenericRecord {
        var lastException: Exception? = null
        return tilgjengeligeSkjemaVersjoner.firstNotNullOfOrNull { (versjon, skjema) ->
            try {
                deserialize(data, skjema)
            } catch (exception: Exception) {
                sikkerlogg.feilVedDeserialisering(data, exception, versjon)
                lastException = exception
                null
            }
        } ?: throw lastException!!
    }

    private fun deserialize(data: ByteArray, schema: Schema) : GenericRecord {
        val reader = GenericDatumReader<GenericRecord>(schema)
        val decoder = decoderFactory.binaryDecoder(data, null)
        /*
        KafkaAvroSerializer legger på 5 bytes, 1 magic byte og 4 som sier noe om hvilke entry i schema registeret som
        brukes. Siden vi ikke ønsker å ha et dependency til schema registryet har vi en egen deserializer og skipper de
        5 første bytene
         */
        decoder.skipFixed(5)
        return reader.read(null, decoder)
    }

    companion object {
        private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")
        private fun Logger.feilVedDeserialisering(data: ByteArray, throwable: Throwable, versjon: String) =
            warn("Klarte ikke å deserialisere Personhendelse-melding fra Leesah med $versjon. Base64='${Base64.getEncoder().encodeToString(data)}'", throwable)
        private fun String.lastSkjema() =
            Schema.Parser().parse(LeesahDeserializer::class.java.getResourceAsStream("/pdl/Personhendelse_$this.avsc"))
    }
}
