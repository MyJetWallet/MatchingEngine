package com.lykke.matching.engine

import com.lykke.matching.engine.utils.config.Config
import org.apache.log4j.Logger
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.SimpleCommandLinePropertySource
import kotlin.system.exitProcess

@SpringBootApplication
class AppStarter

@Suppress("HasPlatformType", "HasPlatformType")
val LOGGER = Logger.getLogger("AppStarter")

fun main(args: Array<String>) {
    try {
        val context = SpringApplicationBuilder(AppStarter::class.java)
            .initializers(ApplicationStatusContextInitializer())
            .run(*args)
        val spotName = context.getBean(Config::class.java).matchingEngine.name
        Runtime.getRuntime().addShutdownHook(ShutdownHook(spotName))
        addCommandLinePropertySource(args, context)
        context.getBean(Application::class.java).run()
    } catch (e: Exception) {
        LOGGER.error(e.message ?: "Unable to start app", e)
        exitProcess(1)
    }
}

private fun addCommandLinePropertySource(args: Array<String>, context: ConfigurableApplicationContext) {
    val commandLineArguments = SimpleCommandLinePropertySource(*args)
    context
        .environment
        .propertySources
        .addFirst(commandLineArguments)
}

internal class ShutdownHook(private val spotName: String) : Thread() {
    init {
        this.name = "ShutdownHook"
    }

    override fun run() {
        LOGGER.info("Stopping application: $spotName")
    }
}


