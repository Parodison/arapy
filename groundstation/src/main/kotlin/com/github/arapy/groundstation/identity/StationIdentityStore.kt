package com.github.arapy.groundstation.identity

import kotlinx.serialization.Serializable
import java.io.File
import java.util.Properties
import java.util.Scanner
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class StationIdentity(
    val id: Uuid,
    val name: String,
)

object StationIdentityStore {
    private val file = File(System.getProperty("user.home"), ".config/groundstation/station.properties")

    @OptIn(ExperimentalUuidApi::class)
    fun getOrCreate(): StationIdentity {
        if (file.exists()) {
            val props = Properties().apply { load(file.inputStream()) }
            return StationIdentity(
                id = Uuid.parse(props.getProperty("id")),
                name = props.getProperty("name")
            )
        }

        println("No se encontró identidad de estación. Configuración inicial:")
        print("Nombre de la estación: ")
        val name = Scanner(System.`in`).nextLine().trim().ifBlank { "Arapy" }
        val identity = StationIdentity(id = Uuid.generateV7(), name = name)
        save(identity)

        println("Estación registrada: ${identity.name} (${identity.id})")
        return identity
    }

    private fun save(identity: StationIdentity) {
        file.parentFile.mkdirs()
        Properties().apply {
            setProperty("id", identity.id.toString())
            setProperty("name", identity.name)
        }.store(file.outputStream(), "Ground Station Identity")
    }
}