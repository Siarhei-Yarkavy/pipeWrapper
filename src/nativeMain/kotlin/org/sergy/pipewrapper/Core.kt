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
import platform.windows.SetConsoleCtrlHandler
import kotlin.concurrent.AtomicReference
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

const val SUCCESSFUL_RETURN: Int = 0
const val CREATE_PIPE_FAILED: Int = 21
const val CREATE_LOGGER_FILE_FAILED: Int = 11
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

enum class Executable(val exeName: String) {
    PRODUCER("producer"),
    CONSUMER("consumer");
}

data class CmdConfig(
    val profile: String,
    val lMode: String?,
    val tmout: String?,
    val itemsList: List<String>?
)

enum class CMDARGS(val paramName: String) {
    PROFILE("--profile"),
    LMODE("--lmode"),
    TIMEOUT("--tmout")
}
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class PWApp : CliktCommand(name = BuildKonfig.applicationName) {

    val runId: String = generateRunId()
    val executor : AtomicReference<NewExecutor?> = AtomicReference(null)

    private val profile by option(
        CMDARGS.PROFILE.paramName,
        help = "Profile name, required"
    ).required()

    private val lMode by option(
        CMDARGS.LMODE.paramName,
        help = "logger mode in one of " + Logger.LMODE.entries.joinToString() + ", default is " + Logger.defaultLMode
    ).choice(*Logger.LMODE.entries.map { it.name }.toTypedArray(), ignoreCase = true)

    private val tmout by option(
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
        val config = CmdConfig(
            profile = profile,
            lMode = lMode,
            tmout = tmout,
            itemsList = items
        )
        Logger.init(config.lMode)
        Logger.get().log("ISO time is ${Clock.System.now()}, we are starting...")
        memScoped {
            executor.getAndSet( NewExecutor(config, this))

            val hStdin = GetStdHandle(STD_INPUT_HANDLE.toUInt())
            if (hStdin == null || hStdin == INVALID_HANDLE_VALUE) {
                Logger.get().log("Failed to get app STDIN handle!")
                exitProcess(ERROR_SETUP_CONSOLE_STATE)
            }

            val mode = alloc<DWORDVar>()
            if (GetConsoleMode(hStdin, mode.ptr) == 0) {
                Logger.get().log("Failed to get console mode!")
                exitProcess(ERROR_SETUP_CONSOLE_STATE)
            }
                // Отключаем ENABLE_PROCESSED_INPUT для Ctrl+C
            if (SetConsoleMode(hStdin, mode.value and ENABLE_PROCESSED_INPUT.inv().toUInt()) == 0) {
                Logger.get().log("Failed to set console mode")
                exitProcess(ERROR_SETUP_CONSOLE_STATE)
            }

            val handler = staticCFunction(::ctrlHandler)
            if (SetConsoleCtrlHandler(handler, TRUE) == 0) {
                Logger.get().log("Error installing CTRL+BREAK handler")
            }

            val exitCode: Int = executor.value?.executePipeline() ?: GENERAL_ERROR
            if (exitCode != SUCCESSFUL_RETURN) {
                throw ProgramResult(exitCode)
            }
        }

    }

    private fun cleanUp() {
        Logger.safeGet().close()
    }

    override fun help(context: Context): String {
        return "The following command line options are available."
    }

    private fun generateRunId(): String = memScoped {

        @OptIn(ExperimentalForeignApi::class)
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

val theApp:PWApp = PWApp()

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    try {
        exitProcess(theApp.versionOption(version = BuildKonfig.version).runApp(args))
    } finally {
        IntraLock.dispose()
    }
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

@OptIn(ExperimentalForeignApi::class)
fun ctrlHandler(ctrlType: DWORD): BOOL {
    if (ctrlType == CTRL_BREAK_EVENT.toUInt()) {
        Logger.safeGet().log("Ctrl+BREAK is not supported! Use default timeout or taskkill command")
        //return TRUE // Return true to avoid shutdown
        //Logger.safeGet().log(theApp.executor?.safeGetProdIds().toString())
        IntraLock.synchronized {
            theApp.executor.value?.run {
            getExes().forEach { exe ->
                    Logger.safeGet().log("Terminating ${exe.exeName}")
                    val exitCode = requestProcessTermination(exe, 100)
                    Logger.safeGet().log("Terminated ${exe.exeName} with code=$exitCode")
                }
            }
        }
        ExitProcess(23u)
    }
    return FALSE // Continue for other events
}


/*@OptIn(ExperimentalForeignApi::class)
@Suppress("UNUSED_PARAMETER")
//@SymbolName("SetConsoleCtrlHandler")
external fun consoleCtrlHandler(
    handler: CPointer<CFunction<(DWORD) -> BOOL>>?,
    add: BOOL
): BOOL
*/