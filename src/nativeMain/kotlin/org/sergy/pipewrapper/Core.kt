package org.sergy.pipewrapper

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import kotlinx.cinterop.*
import org.sergy.pipewrapper.exception.ErrorCodeAware
import org.sergy.pipewrapper.exception.PWIllegalStateException
import org.sergy.pipewrapper.exception.PWRuntimeException
import platform.posix.*
import platform.windows.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.experimental.ExperimentalNativeApi
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

//Return codes
const val SUCCESSFUL_RETURN: Int = 0
const val CREATE_PIPE_FAILED: Int = 21
const val CREATE_MAIN_LOGGER_FAILED: Int = 11
const val CREATE_EXECUTABLE_LOGGER_FAILED: Int = 14
const val CREATE_NUL_LOGGER_FAILED: Int = 15
const val GET_LOGGER_INSTANCE_FAILED: Int = 12
const val CREATING_RUN_ID_FAILED: Int = 13
const val DESERIALIZATION_JSON_FAILED: Int = 9
const val CANNOT_GET_EXECUTABLE_DIRECTORY: Int = 7
const val CONFIGURATION_CONSUMER_ABSENT: Int = 8
const val REQUESTED_CONFIGURATION_ABSENT: Int = 5
const val EXE_CONFIG_READER_IS_NOT_INITIALIZED: Int = 6
const val COMMAND_LINE_PARSING_ERROR: Int = 4
const val PRODUCER_CREATION_FAILED: Int = 31
const val CONSUMER_CREATION_FAILED: Int = 41
const val CHILD_PROCESS_WAS_KILLED: Int = 99
const val GENERAL_ERROR: Int = 89
const val EXECUTABLE_STATE_ERROR: Int = 98
const val AT_LEAST_ONE_CHILD_FAILED: Int = 97
const val ERROR_SETUP_CONSOLE_STATE: Int = 88
const val ERROR_UNKNOWN_CONSOLE_TYPE: Int = 83
//Constants
const val NULL_PROFILE_NAME: String = "NUL"

enum class Executable(val exeName: String) {
    PRODUCER("producer"),
    CONSUMER("consumer");
}

data class CmdConfig(
    val runInTextConsole : Boolean,
    val profile: String,
    val t: String?,
    val itemsList: List<String>?
)

enum class CMDARGS(val paramName: String) {
    PROFILE("--profile"),
    LMODE("--lmode"),
    TIMEOUT("--t")
}

@OptIn(ExperimentalForeignApi::class)
class PWApp : CliktCommand(name = BuildKonfig.applicationName) {

    val runId: String = generateRunId()
    private var hNul: CPointer<out CPointed>? = null

    @OptIn(ExperimentalAtomicApi::class)
    val executor: AtomicReference<NewExecutor?> = AtomicReference(null)

    private val profile by option(
        CMDARGS.PROFILE.paramName,
        help = "Profile name, required. Use '$NULL_PROFILE_NAME' to run direct pipe"
    ).required()

    private val lMode by option(
        CMDARGS.LMODE.paramName,
        help = "logger mode in one of " + Logger.LMODE.entries.joinToString() + ", default is " + Logger.defaultLMode
    ).choice(*Logger.LMODE.entries.map { it.name }.toTypedArray(), ignoreCase = true)

    private val t by option(
        CMDARGS.TIMEOUT.paramName,
        help = "Timeout before force terminating child processes, default is " + NewExecutor.DEFAULT_TIMEOUT
    )

    private val items by argument(
        name = "placeholders",
        help = "List element to replace placeholders enumerated in executable configs",
        helpTags = mapOf(
            "example" to "%1 Replaced by first argument, %N Replaced by N argument",
        )
    ).multiple().optional()

    override fun run() {
        runLogic()
    }

    @OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)
    private fun runLogic() {
        Logger.init(lMode)
        Logger.get().log("ISO time is ${Clock.System.now()}, we are starting...")
        Logger.get().log("Helpful WinAPI errors table: " +
                "https://learn.microsoft.com/en-us/windows/win32/debug/system-error-codes")

        var isTextSession = false
        val hIn = GetStdHandle(STD_INPUT_HANDLE)
        if (hIn == INVALID_HANDLE_VALUE) {
            val message = "Could not get stdin!"
            Logger.get().log(message)
            throw PWIllegalStateException(ERROR_SETUP_CONSOLE_STATE, message)
        }

        val fileType = GetFileType(hIn)
        when (fileType.toInt()) {
            FILE_TYPE_CHAR -> {
                val nulName = "NUL"
                hNul = CreateFileW(
                    nulName,
                    GENERIC_READ,
                    FILE_SHARE_READ.toUInt(),
                    null,
                    OPEN_EXISTING.toUInt(),
                    FILE_ATTRIBUTE_NORMAL.toUInt(),
                    null
                )

                if (hNul == INVALID_HANDLE_VALUE) {
                    val message = "Could not open NUL device!"
                    Logger.get().log(message)
                    throw PWIllegalStateException(ERROR_SETUP_CONSOLE_STATE, message)
                }

                // Set new handler for stdin for Windows API
                val result = SetStdHandle(STD_INPUT_HANDLE, hNul)
                if (result == 0) {
                    val err = GetLastError()
                    val message = "SetStdHandle failed with winAPI error code=$err!"
                    Logger.get().log(message)
                    throw PWIllegalStateException(ERROR_SETUP_CONSOLE_STATE, message)
                }

                // Reroute CRT stdin (scanf/getchar etc.) to "NUL".
                // Use freopen: this replaces FILE* stdin to NUL.
                val reopened = freopen(nulName, "r", stdin)
                if (reopened == null) {
                    val err = GetLastError()
                    val message = "Failed to attach stdin to NUL with winAPI error code=$err!"
                    Logger.get().log(message)
                    // Do not close hNul here — SetStdHandle already connects it.
                    throw PWIllegalStateException(ERROR_SETUP_CONSOLE_STATE, message)
                }
                isTextSession = true
                Logger.get().log(
                    "\n\n[!] ${commandName.uppercase()} runs in text terminal mode " +
                            "and expects binary pipe data exchange only. " +
                            "${commandName.uppercase()} prevents reading from stdin in this mode " +
                            "for this app and children. " +
                            "${commandName.uppercase()} stdin successfully redirected to NUL.\n\n"
                )
            }

            FILE_TYPE_DISK, FILE_TYPE_PIPE -> Unit
            else -> {
                Logger.get().log("[!] Unknown type of stdin type: $fileType")
                throw ProgramResult(ERROR_UNKNOWN_CONSOLE_TYPE)
            }
        }
        val config = CmdConfig(
            runInTextConsole = isTextSession,
            profile = profile,
            t = t,
            itemsList = items
        )
        memScoped {
            executor.store(NewExecutor(config, this))

            val hConsole = CreateFileW(
                "CONIN$",
                GENERIC_READ or GENERIC_WRITE.toUInt(),
                FILE_SHARE_READ.toUInt() or FILE_SHARE_WRITE.toUInt(),
                null,
                OPEN_EXISTING.toUInt(),
                0u,
                null
            )

            if (hConsole == null || hConsole == INVALID_HANDLE_VALUE) {
                val message = "Cannot open console handle!"
                Logger.get().log(message)
                throw PWIllegalStateException(ERROR_SETUP_CONSOLE_STATE, message)
            }

            val mode = alloc<DWORDVar>()
            if (GetConsoleMode(hConsole, mode.ptr) == 0) {
                val message = "Failed to get console mode!"
                Logger.get().log(message)
                throw PWIllegalStateException(ERROR_SETUP_CONSOLE_STATE, message)

            }
            // Disable ENABLE_PROCESSED_INPUT для Ctrl+C
            if (SetConsoleMode(
                    hConsole,
                    mode.value and ENABLE_PROCESSED_INPUT.inv().toUInt()
                ) == 0
            ) {
                Logger.get().log("Failed to set console mode to ignore CTRL+C")
                throw PWIllegalStateException(ERROR_SETUP_CONSOLE_STATE)
            }

            val handler = staticCFunction(::ctrlHandler)
            if (SetConsoleCtrlHandler(handler, TRUE) == 0) {
                val message = "Error installing CTRL+BREAK handler!"
                Logger.get().log(message)
                throw PWIllegalStateException(ERROR_SETUP_CONSOLE_STATE, message)
            }

            val exitCode: Int = executor.load()?.executePipeline() ?: GENERAL_ERROR
            if (exitCode != SUCCESSFUL_RETURN) {
                throw ProgramResult(exitCode)
            }
        }
    }

    private fun cleanUp() {
        Logger.safeGet().close()
        hNul?.let {CloseHandle(it)}
    }

    override fun help(context: Context): String {
        return "The following command line options are available."
    }

    private fun generateRunId(): String = memScoped {

        fun formatTimeSafe(tm: tm, millis: Int): String {
            var buffer: Pinned<ByteArray>? = null
            val bsize = 64 // size with some oversize

            try {
                buffer = ByteArray(bsize).pin()
                val result = snprintf(
                    buffer.addressOf(0),
                    bsize.toULong(),
                    "%04d-%02d-%02d_%02d-%02d-%02d-%03d",
                    tm.tm_year + 1900,
                    tm.tm_mon + 1,
                    tm.tm_mday,
                    tm.tm_hour,
                    tm.tm_min,
                    tm.tm_sec,
                    millis
                )

                if (result < 0) throw PWRuntimeException(
                    CREATING_RUN_ID_FAILED,
                    "Formating runId time part failed!"
                )
                return buffer.get().toKString()
            } finally {
                buffer?.unpin()
            }
        }

        val timeVal = alloc<timeval>()
        if (mingw_gettimeofday(timeVal.ptr, null) != 0) {
            throw PWRuntimeException(CREATING_RUN_ID_FAILED, "Failed to get precise time !")
        }

        //type cast
        val timeSec = alloc<time_tVar>().apply {
            value = timeVal.tv_sec.convert<time_t>()
        }
        val timeInfo = localtime(timeSec.ptr)!!
        val tm = timeInfo.pointed
        val millisPart: Int = timeVal.tv_usec / 1000

        val randomPart: String = (1..5).map {
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[Random.nextInt(36)]
        }.joinToString("")

        val timePart: String = formatTimeSafe(tm, millisPart)
        "${timePart}_$randomPart"
    }

    fun getExecutableDirectory(): String = memScoped {
        // Create max length buffer
        val bufferLength = 32767
        val buffer = allocArray<UShortVar>(bufferLength)

        // get path to executable file
        val result = GetModuleFileNameW(
            hModule = null, // current mode (no module)
            lpFilename = buffer,
            nSize = bufferLength.toUInt()
        )

        if (result == 0U) {
            throw PWRuntimeException(
                CANNOT_GET_EXECUTABLE_DIRECTORY,
                "Get $commandName directory failed, error code: ${GetLastError()}"
            )
        }

        val fullPath = buffer.toKString()

        // Remove filename leaving only dir
        val exeDir = fullPath.substringBeforeLast('\\', missingDelimiterValue = fullPath)
        Logger.get().log("Identified $commandName executable dir as \"$exeDir\"")
        exeDir
    }

    fun runApp(argv: Array<String>): Int {
        fun internalLogShort(exitCode: Any) {
            val shortMessage = "ErrorCode=$exitCode, the program finished abnormally"
            Logger.safeGet().log(shortMessage)
        }

        fun internalLogLong(ex: Throwable, exitCode: Int) {
            val longMessage = "ErrorCode=$exitCode, Exception was thrown: " +
                    ex.message + "\n\n" + ex.stackTraceToString()
            Logger.safeGet().log(longMessage)
        }

        return try {
            this.parse(argv)
            SUCCESSFUL_RETURN
        } catch (ex: Throwable) {
            var exitCode = GENERAL_ERROR
            when (ex) {
                is ErrorCodeAware -> {
                    exitCode = ex.errorCode
                    internalLogLong(ex, exitCode)
                }

                is ProgramResult -> {
                    exitCode = ex.statusCode
                    if (SUCCESSFUL_RETURN != exitCode) {
                        internalLogShort(exitCode)
                    }
                }

                is PrintMessage, is PrintHelpMessage -> {
                    theApp.currentContext.command.echoFormattedHelp(ex)
                    exitCode = SUCCESSFUL_RETURN
                }

                is CliktError -> {
                    theApp.currentContext.command.echoFormattedHelp(ex)
                    exitCode = COMMAND_LINE_PARSING_ERROR
                    internalLogShort("$exitCode(${ex::class.simpleName})")
                }

                else -> {
                    internalLogLong(ex, exitCode)
                }
            }
            exitCode
        } finally {
            theApp.cleanUp()
        }
    }
}

val theApp: PWApp = PWApp()

@OptIn(ExperimentalNativeApi::class)
fun main(args: Array<String>) {
    exitProcess(theApp.versionOption(version = BuildKonfig.version).runApp(args))
}

@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
fun ctrlHandler(ctrlType: DWORD): BOOL {
    if (ctrlType == CTRL_BREAK_EVENT.toUInt()) {
        theApp.executor.load()?.shouldShutShutdown?.store(true)
        return TRUE
    }
    return FALSE // Continue for other events
}

@OptIn(ExperimentalContracts::class)
inline fun <T : Any> extendedCheckNotNull(
    value: T?,
    contextId: Int,
    crossinline lazyAction: () -> Any = { }
): T {
    contract {
        returns() implies (value != null)
    }
    if (value == null) {
        val message = lazyAction()
        throw PWIllegalStateException(
            contextId,
            if (message is Unit) "Required value was null!" else message.toString()
        )
    }
    return value
}
