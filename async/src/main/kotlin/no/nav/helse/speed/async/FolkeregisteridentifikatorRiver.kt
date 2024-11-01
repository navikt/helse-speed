package no.nav.helse.speed.async

import com.github.navikt.tbd_libs.rapids_and_rivers.withMDC
import com.github.navikt.tbd_libs.speed.SpeedClient
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class FolkeregisteridentifikatorRiver(
    private val consumer: KafkaConsumer<ByteArray, GenericRecord>,
    private val speedClient: SpeedClient
) {
    private val latch = CountDownLatch(1)

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            stop()
        })
    }

    fun isRunning() = latch.count == 1L
    fun await() = latch.await()

    fun stop() {
        try {
            consumer.wakeup()
        } catch (err: Exception) { }
        latch.countDown()
    }

    fun runBlocking() {
        try {
            consumer.subscribe(listOf("pdl.leesah-v1"))
            while (isRunning()) {
                val records = consumer.poll(Duration.ofMillis(100))
                records.forEach { it ->
                    val record = it.value()
                    val opplysningstype = record.get("opplysningstype").toString()
                    if (opplysningstype == "FOLKEREGISTERIDENTIFIKATOR_V1") {
                        val callId = UUID.randomUUID().toString()
                        withMDC("callId" to callId) {
                            håndterFolkeregisteridentifikatorOpplysning(record, callId)
                        }
                    }
                }
            }
        } catch (ex: WakeupException) {
            sikkerlogg.info("Consumeren fikk beskjed om å våkne opp, mest sannsynlig fordi appen skal skrus av", ex)
            logg.info("Consumeren fikk beskjed om å våkne opp, mest sannsynlig fordi appen skal skrus av", ex)
        } catch (err: Exception) {
            sikkerlogg.error("Fikk error ved lesing av leesah: ${err.message}", err)
            logg.error("Fikk error ved lesing av leesah: se sikker logg")
        } finally {
            latch.countDown()
        }
    }

    private fun håndterFolkeregisteridentifikatorOpplysning(record: GenericRecord, callId: String) {
        sikkerlogg.info("mottok melding om folkeregisteridentifikator:\n$record")

        val folkeregisteridentifikator = record.get("Folkeregisteridentifikator")
        if (folkeregisteridentifikator !is GenericData.Record) return
        val ident = folkeregisteridentifikator["identifikasjonsnummer"].toString()
        val personidenter = record.get("personidenter")

        val identer = buildList<String> {
            add(ident)
            if (personidenter is List<*>) personidenter.forEach { add("$it") }
        }
        sikkerlogg.info("tømmer mellomlager for identene: $identer")
        speedClient.tømMellomlager(identer, callId)
    }

    private companion object {
        private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")
        private val logg = LoggerFactory.getLogger(this::class.java)
    }
}