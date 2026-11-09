package dev.piko.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout

/**
 * Process-owned, unauthenticated transport for auxiliary services (MetaTube and image fetching).
 * This is not the SDK's authenticated client and must not receive global account/token headers.
 * HTTP error bodies remain available for service-specific handling; TLS uses engine defaults.
 *
 * The factory overload gives HttpClient ownership of its engine. Close each returned client at
 * host shutdown, after its consumers. Do not allocate one client per request or per recomposition.
 * [configure] allows engine setup and explicit host overrides (also used by offline MockEngine tests).
 */
fun <T : HttpClientEngineConfig> createPikoAuxiliaryHttpClient(
    engineFactory: HttpClientEngineFactory<T>,
    configure: HttpClientConfig<T>.() -> Unit = {},
): HttpClient = HttpClient(engineFactory) {
    expectSuccess = false
    followRedirects = true
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    configure()
}
