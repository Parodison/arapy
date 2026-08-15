package com.github.arapy.groundstation.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.cbor.cbor
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
val client = HttpClient(CIO) {
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Cbor)
    }
    install(ContentNegotiation) {
        cbor(Cbor {
            ignoreUnknownKeys = true
        })
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })

    }
}