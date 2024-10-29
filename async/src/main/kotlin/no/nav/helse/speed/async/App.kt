package no.nav.helse.speed.async

import com.github.navikt.tbd_libs.azure.createAzureTokenClientFromEnvironment
import no.nav.helse.rapids_rivers.RapidApplication
import org.slf4j.LoggerFactory
import java.net.http.HttpClient

private val logg = LoggerFactory.getLogger("no.nav.helse.speed.async.App")
private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")

fun main() {
    val env = System.getenv()
    val httpClient = HttpClient.newHttpClient()

    val azure = createAzureTokenClientFromEnvironment(env)
    val cluster = System.getenv("NAIS_CLUSTER_NAME")
    val scope = "api://$cluster.tbd.speed-async/.default"

    val erUtvikling = cluster == "dev-gcp"

    RapidApplication.create(env).start()
}
