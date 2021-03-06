package com.lykke.utils.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import javax.naming.ConfigurationException

internal object HttpConfigParser {
    fun <Config> initConfig(httpString: String, classOfT: Class<Config>): Config {
        try {
            val cfgUrl = URL(httpString)
            val connection = cfgUrl.openConnection()
            val inputStream = BufferedReader(InputStreamReader(connection.inputStream))

            val response = StringBuilder()
            var inputLine = inputStream.readLine()

            while (inputLine != null) {
                response.append(inputLine)
                inputLine = inputStream.readLine()
            }

            inputStream.close()

            val mapper = ObjectMapper(YAMLFactory())
            mapper.findAndRegisterModules()
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            mapper.configure(DeserializationFeature.UNWRAP_ROOT_VALUE, true)
            return mapper.readValue(
                response.toString(),
                classOfT
            )
        } catch (e: Exception) {
            throw ConfigurationException("Unable to read config from $httpString: ${e.message}")
        }
    }
}