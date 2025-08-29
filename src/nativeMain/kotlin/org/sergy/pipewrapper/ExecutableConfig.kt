package org.sergy.pipewrapper

import org.sergy.pipewrapper.exception.PWRuntimeException
import kotlinx.cinterop.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.*

@Serializable
data class ExeLineConfig(
    val path: String,
    val params: MutableMap<String, String>
)

class ExeConfigReader private constructor(private val profile: String) {

    private val configMap: Map<Executable, ExeLineConfig> by lazy {
        loadConfig()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun loadConfig(): Map<Executable, ExeLineConfig> {
        val mutableConfigMap: MutableMap<Executable, ExeLineConfig> = mutableMapOf()
        val filePath = theApp.getExecutableDirectory() + "\\$profile\\"
        Logger.get().log("Reading configuration from path \"$filePath\"")
        for (exe in Executable.entries) {
            val fullConfigPath = filePath + exe.exeName + ".json"
            val jsonString = readFile(fullConfigPath)
            val exeLineConfig: ExeLineConfig? = if (jsonString != null) {
                try {
                    Json.decodeFromString<ExeLineConfig>(jsonString)
                } catch (e: Exception) {
                    Logger.get().log(
                        "${exe.exeName} reading config $fullConfigPath deserialization JSON error: ${e.message}"
                    )
                    throw PWRuntimeException(DESERIALIZATION_JSON_FAILED, cause = e)
                }
            } else {
                val msg = "Command line configuration is absent for ${exe.name.uppercase()}!"
                Logger.get().log(msg)
                null
            }
            if (exeLineConfig != null) mutableConfigMap[exe] = exeLineConfig

        }
        return mutableConfigMap.toMap()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readFile(filePath: String): String? = memScoped {

        val file = fopen(filePath, "r")
        if (file == null) {
            Logger.get().log("File $filePath doesn't exist!")
            return null
        }
        try {
            // getting file size
            fseek(file, 0, SEEK_END)
            val size = ftell(file)
            fseek(file, 0, SEEK_SET)

            // Reading file
            val buffer = allocArray<ByteVar>(size + 1)
            val bytesRead = fread(buffer, 1U, size.toULong(), file)
            buffer[bytesRead.toInt()] = 0 // adding zero terminator

            buffer.toKString()
        } finally {
            fclose(file)
        }
    }

    fun getConfig(exe: Executable): ExeLineConfig {
        return (if (configExists(exe)) configMap[exe] else throw PWRuntimeException(
            REQUESTED_CONFIGURATION_ABSENT, "Configuration for ${exe.exeName} is absent."
        ))!!
    }

    fun configExists(exe: Executable): Boolean {
        return configMap.containsKey(exe) && configMap[exe] != null
    }

    companion object {
        private var instance: ExeConfigReader? = null

        // not thread safe !!!
        fun install(profile: String): ExeConfigReader {
            return ExeConfigReader(profile).also { instance = it }
        }

        fun get(): ExeConfigReader {
            return extendedCheckNotNull(instance, EXE_CONFIG_READER_IS_NOT_INITIALIZED) {
                val message = "Configuration reader should be set!"
                Logger.get().log(message)
                message
            }
        }
    }

}