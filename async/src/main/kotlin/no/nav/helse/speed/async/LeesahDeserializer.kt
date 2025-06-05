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

    override fun deserialize(topic: String, data: ByteArray): GenericRecord {
        // 11.04.25: V4 er i bruk i dev. Skjemaendringer kan komme før V4 går i prod.
        try {
            return deserialize(data, v4Skjema)
        } catch (exception: Exception) {
            sikkerlogg.feilVedDeserialisering(data, exception, "V4")
        }

        try {
            return deserialize(data, v3Skjema)
        } catch (exception: Exception) {
            sikkerlogg.feilVedDeserialisering(data, exception, "V3")
        }

        // Prøv forrige versjon
        try {
            return deserialize(data, v2Skjema)
        } catch (exception: Exception) {
            sikkerlogg.feilVedDeserialisering(data, exception, "V2")
            throw exception
        }
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
        private val v2Skjema = "V2".lastSkjema()
        private val v3Skjema = "V3".lastSkjema()
        private val v4Skjema = "V4".lastSkjema()
    }
}
