package dev.piko.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * URLSession-backed foreground transport. No JVM proxy selector, certificate overrides, request
 * logging, shared cookies, or account auth is installed. The host owns and closes this client.
 * This does not replace the SDK's authenticated client or provide background transfers.
 */
fun createIosAuxiliaryHttpClient(): HttpClient = createPikoAuxiliaryHttpClient(Darwin)
