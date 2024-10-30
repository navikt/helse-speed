package no.nav.helse.speed.async

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.navikt.tbd_libs.azure.createAzureTokenClientFromEnvironment
import com.github.navikt.tbd_libs.kafka.AivenConfig
import com.github.navikt.tbd_libs.kafka.ConsumerProducerFactory
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import com.github.navikt.tbd_libs.speed.SpeedClient
import no.nav.helse.rapids_rivers.RapidApplication
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.net.http.HttpClient
import java.util.Properties

private val logg = LoggerFactory.getLogger("no.nav.helse.speed.async.App")
private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")

fun main() {
    val env = System.getenv()
    val httpClient = HttpClient.newHttpClient()

    val azure = createAzureTokenClientFromEnvironment(env)

    val kafkaConfig = AivenConfig.default
    val consumerProducerFactory = ConsumerProducerFactory(kafkaConfig)
    val speedClient = SpeedClient(httpClient, jacksonObjectMapper().registerModule(JavaTimeModule()), azure)

    val leesahConsumer =  KafkaConsumer(kafkaConfig.consumerConfig(env.getValue("KAFKA_CONSUMER_GROUP_ID"), Properties().apply {
        put("specific.avro.reader", "true")
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
    }), ByteArrayDeserializer(), LeesahDeserializer())
    val fregRiver = FolkeregisteridentifikatorRiver(leesahConsumer, speedClient)
    val fregThread = Thread { fregRiver.runBlocking() }

    RapidApplication.create(env, consumerProducerFactory = consumerProducerFactory)
        .apply {
            register(object : RapidsConnection.StatusListener {
                override fun onReady(rapidsConnection: RapidsConnection) {
                    logg.info("starter FolkeregisteridentifikatorRiver")
                    sikkerlogg.info("starter FolkeregisteridentifikatorRiver")
                    fregThread.start()
                }

                override fun onShutdownSignal(rapidsConnection: RapidsConnection) {
                    logg.info("stopper FolkeregisteridentifikatorRiver")
                    sikkerlogg.info("stopper FolkeregisteridentifikatorRiver")
                    fregRiver.stop()
                }
            })
        }.start()
}
