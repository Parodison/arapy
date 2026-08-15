plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinPluginSerialization)
    // Apply the Application plugin to add support for building an executable JVM application.
    application
    id("com.gradleup.shadow") version "9.6.1"
}

dependencies {
    // Project "app" depends on project "utils". (Project paths are separated with ":", so ":utils" refers to the top-level "utils" project.)
    implementation(libs.orbit.core)
    implementation(libs.kotlinxDatetime)
    implementation(libs.kotlinxCoroutines)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.serialization.kotlinx.cbor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pi4j.ktx)
    implementation(libs.pi4j.core)
    implementation(libs.pi4j.plugin.raspberrypi)
    implementation(libs.pi4j.plugin.pigpio)
    implementation(libs.slf4j.simple)
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `Main.kt` to a class with FQN `com.github.arapy.groundstation.MainKt`.)
    mainClass = "com.github.arapy.groundstation.MainKt"
}

tasks.shadowJar {
    // Pi4J plugins register themselves via META-INF/services/com.pi4j.extension.Plugin.
    // duplicatesStrategy must allow duplicates through so mergeServiceFiles() can combine them;
    // otherwise Shadow's default EXCLUDE strategy drops every entry but one before it runs
    // (e.g. the pigpio provider), so Pi4J can't find providers like "pigpio-pwm" at runtime.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

tasks.register<Exec>("deployToPi") {
    val piHost = "10.154.42.81"
    val piUser = "arapy"
    val piPassword = "2306"
    val jarName = "ground-station.jar"
    val jarFileProvider = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }

    group = "deployment"
    description = "Compila y copia el jar a la Raspberry Pi"
    dependsOn("shadowJar")

    doFirst {
        val jarFile = jarFileProvider.get().asFile
        commandLine(
            "bash", "-c",
            """
            sshpass -p '$piPassword' scp -o StrictHostKeyChecking=no "${jarFile.absolutePath}" $piUser@$piHost:~/$jarName
            """.trimIndent()
        )
    }
}

tasks.register<Exec>("runOnPi") {
    val piHost = "10.154.42.81"
    val piUser = "arapy"
    val piPassword = "2306"
    val jarName = "ground-station.jar"
    // Override with `./gradlew :groundstation:runOnPi -PdebugPort=5006` if 5005 is taken.
    val debugPort = (project.findProperty("debugPort") as String?) ?: "5005"

    group = "deployment"
    description = "Compila, copia y corre el jar en la Raspberry Pi (con puerto de debug remoto en :$debugPort)"
    dependsOn("deployToPi")

    // java on the Pi has CAP_SYS_RAWIO (see setcap) so it can talk to pigpio without root,
    // which means stdin stays free for the app's own interactive prompts (e.g. station setup).
    standardInput = System.`in`

    doFirst {
        // Clean up any process orphaned by a previous run that didn't get killed cleanly
        // (e.g. a hard-killed local build), otherwise the debug port bind fails on start.
        // Plain ProcessBuilder here (not Gradle's exec {}) so nothing captures the Project
        // instance, which would break the configuration cache.
        ProcessBuilder(
            "sshpass", "-p", piPassword,
            "ssh", "-o", "StrictHostKeyChecking=no",
            "$piUser@$piHost",
            "pkill -f '$jarName' || true"
        ).inheritIO().start().waitFor()
    }

    // -tt *forces* a remote pty (unlike -t, which silently no-ops when Gradle doesn't give
    // ssh a real local terminal, which is always). `exec java ...` replaces the remote shell
    // with java itself, so it's directly attached to that pty as the session's process — when
    // the ssh session ends (Ctrl+C locally, or a dead connection caught by ServerAlive*), the
    // pty closes and the kernel sends SIGHUP straight to java, killing it instead of orphaning it.
    commandLine(
        "sshpass", "-p", piPassword,
        "ssh", "-tt",
        "-o", "StrictHostKeyChecking=no",
        "-o", "ServerAliveInterval=5",
        "-o", "ServerAliveCountMax=2",
        "$piUser@$piHost",
        "exec java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$debugPort " +
            "-jar ~/$jarName"
    )
}
