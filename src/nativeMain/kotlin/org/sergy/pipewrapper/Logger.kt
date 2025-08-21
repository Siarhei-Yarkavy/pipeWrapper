package org.sergy.pipewrapper

import kotlinx.cinterop.*
import org.sergy.pipewrapper.exception.PWIllegalStateException
import platform.posix.*
import platform.windows.*

interface ILogger {
    fun log(message: String)
    fun close()
    @OptIn(ExperimentalForeignApi::class)
    fun getExeErrorLoggingHandle(exe: Executable): HANDLE? = GetStdHandle(STD_ERROR_HANDLE)
}

@OptIn(ExperimentalForeignApi::class)
class Logger private constructor(private val mode: LMODE): ILogger  {

    enum class LMODE {
        //Logging mode
        INCL, FILE, CON, SIL
    }

    private var file: CPointer<FILE>? = null
    private val appLogHandles =
        mutableMapOf<Executable, CPointer<out CPointed>?>()

    init {
        val runId = theApp.runId
        if (isFileLogging()) {
            file = fopen("${runId}-${theApp.commandName}.log", "w")
            if (file == null) {
                perror("Failed to open main log file")
                throw PWIllegalStateException(CREATE_MAIN_LOGGER_FAILED)
            }
            memScoped {
                val saAttr = alloc<SECURITY_ATTRIBUTES>().apply {
                    nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt()
                    bInheritHandle = TRUE
                    lpSecurityDescriptor = null
                }

                for (exe in Executable.entries) {
                    val logHandle = CreateFileW(
                        "${runId}-${exe.exeName}.log",
                        GENERIC_WRITE.toUInt(),
                        FILE_SHARE_READ.toUInt(),
                        saAttr.ptr,
                        CREATE_ALWAYS.toUInt(),
                        FILE_ATTRIBUTE_NORMAL.toUInt(),
                        null
                    )
                    if (logHandle == INVALID_HANDLE_VALUE || logHandle == null) {
                        fputs("Error creating log file for ${exe.name}! Error code ${GetLastError()}\n",
                            stderr)
                        throw PWIllegalStateException(CREATE_EXECUTABLE_LOGGER_FAILED)
                    }
                    appLogHandles[exe] = logHandle
                }
            }
        }
    }

    companion object {
        private lateinit var instance: Logger
        val defaultLMode: LMODE = LMODE.SIL

        fun init(strMode: String?) {
            val mode = strMode?.uppercase()?.let(LMODE::valueOf) ?: defaultLMode
            instance = Logger(mode)
        }

        fun get(): ILogger =
            if (!isInitialized()) {
                perror("Error! Logger is not initialized!\nWinAPI last error code ${GetLastError()} " +
                        "\nErrno value ")
                fputs("ExitCode=$GET_LOGGER_INSTANCE_FAILED, Stack trace:\n" +
                        Throwable().stackTraceToString(), stderr)
                throw PWIllegalStateException(GET_LOGGER_INSTANCE_FAILED)

            } else {
                instance
            }

        fun safeGet(): ILogger =
            if (!isInitialized()) {
                object : ILogger {
                    override fun log(message: String) {
                        fputs("Warning! Logger is not available. Fallback to console stderr: $message",
                            stderr)
                    }
                    override fun close() {
                        // stub
                    }
                }
            } else {
                instance
            }

        private fun isInitialized() = this::instance.isInitialized
    }

    override fun log(message: String) {
        if (LMODE.SIL == mode) return
        memScoped {
            val rawTime = alloc<time_tVar>().apply {
                value = time(null)  // Save to variable
            }
            val tm = localtime(rawTime.ptr)
            val formatArgsArray = intArrayOf(
                    tm?.pointed?.tm_year?.plus(1900) ?: 0,
                    tm?.pointed?.tm_mon?.plus(1) ?: 0,
                    tm?.pointed?.tm_mday ?: 0,
                    tm?.pointed?.tm_hour ?: 0,
                    tm?.pointed?.tm_min ?: 0,
                    tm?.pointed?.tm_sec ?: 0
            )

            val format = "[pW %04d-%02d-%02d %02d:%02d:%02d] %s\n"
            if (isFileLogging()) {
                file?.let {
                    fprintf(
                        it, format,
                        //cannot spread here due to native code
                        formatArgsArray[0],
                        formatArgsArray[1],
                        formatArgsArray[2],
                        formatArgsArray[3],
                        formatArgsArray[4],
                        formatArgsArray[5],
                        message.cstr.ptr
                    )
                    fflush(it)

                } ?: perror("Fallback logging to console: $message, errno is ")
            }
            if (isConsoleLogging()) {
                fprintf(
                    stderr, format,
                    //cannot spread here due to native code
                    formatArgsArray[0],
                    formatArgsArray[1],
                    formatArgsArray[2],
                    formatArgsArray[3],
                    formatArgsArray[4],
                    formatArgsArray[5],
                    message.cstr.ptr
                )
                fflush(stderr)
            }
        }
    }

    private fun isFileLogging(): Boolean {
        return (mode in arrayOf(LMODE.INCL, LMODE.FILE))
    }

    private fun isConsoleLogging(): Boolean {
        return (mode in arrayOf(LMODE.INCL, LMODE.CON))
    }

    override fun getExeErrorLoggingHandle(exe: Executable): CPointer<out CPointed>? =
        if (isFileLogging()) appLogHandles[exe] else GetStdHandle(STD_ERROR_HANDLE)

    override fun close() {
        log("Closing the logger")
        file?.let { fclose(it) }
        for (handle in appLogHandles.values) {
            handle?.let { CloseHandle(it) }
        }
    }
}