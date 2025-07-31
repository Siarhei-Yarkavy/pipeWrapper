import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import exception.ErrorCodeAware
import exception.PWIllegalStateException
import exception.PWRuntimeException
import kotlinx.cinterop.*
import platform.posix.*
import platform.windows.GetLastError
import platform.windows.GetModuleFileNameW
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.experimental.ExperimentalNativeApi
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val APP_NAME = "pipeWrapper"
const val SUCCESSFUL_RETURN = 0
const val CREATE_PIPE_FAILED = 21
const val CREATE_LOGGER_FILE_FAILED = 11
const val GET_LOGGER_INSTANCE_FAILED = 12
const val CREATING_RUN_ID_FAILED = 13
const val DESERIALIZATION_JSON_FAILED = 9
const val CANNOT_GET_EXECUTABLE_DIRECTORY = 7
const val CONFIGURATION_CONSUMER_ABSENT = 8
const val REQUESTED_CONFIGURATION_ABSENT = 5
const val EXE_CONFIG_READER_IS_NOT_INITIALIZED = 6
const val COMMAND_LINE_PARSING_ERROR = 4
const val PRODUCER_CREATION_FAILED = 31
const val CONSUMER_CREATION_FAILED = 41
const val PROCESS_WAS_KILLED = 99
const val GENERAL_ERROR = 89
const val EXECUTABLE_STATE_ERROR = 98

enum class Executable(val exeName: String) {
    PRODUCER("producer"),
    CONSUMER("consumer");
}

data class CmdConfig(
    val profile: String,
    val lMode: String?,
    val itemsList: List<String>?
)

enum class CMDARGS(val paramName: String) {
    PROFILE("--profile"),
    LMODE("--lmode")
}

class PWApp : CliktCommand(name = APP_NAME) {

    val runId: String = generateRunId()

    val profile by option(
        CMDARGS.PROFILE.paramName,
        help = "Profile name"
    ).required()

    val lMode by option(
        CMDARGS.LMODE.paramName,
        help = "logger mode in one of " + Logger.LMODE.entries.joinToString()
    ).choice(*Logger.LMODE.entries.map { it.name }.toTypedArray(), ignoreCase = true)

    /*val items by option(
        CMDARGS.ITEM.paramName,
        help = "List element to replace placeholders enumerated in executable configs"
    ).multiple()*/

    val items by argument(
        name = "placeholders",
        help = "List element to replace placeholders enumerated in executable configs",
        helpTags = mapOf(
            "%1" to "Replaced by first argument",
            "%2" to "Replaced by second argument",
            "%N" to "Replaced by N argument"
        )
    ).multiple().optional()

    override fun run() {
        val config = CmdConfig(
            profile = profile,
            lMode = lMode,
            itemsList = items
        )
        runLogic(config)
    }

    @OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
    private fun runLogic(cmdConfig: CmdConfig) {

        Logger.init(cmdConfig.lMode)
        Logger.get().log("Time is ${Clock.System.now()}, we are starting...")

        var exitCode: Int = SUCCESSFUL_RETURN
        memScoped {
            val executor = NewExecutor(cmdConfig, this)
            exitCode = executor.executePipeline()
        }
        if (exitCode != SUCCESSFUL_RETURN) {
            throw ProgramResult(exitCode)
        }
    }

    fun cleanUp() {
        Logger.safeGet().close()
    }

    override fun help(context: Context): String {
        return "The following command line options are available."
    }

    @OptIn(ExperimentalForeignApi::class)
    fun generateRunId(): String = memScoped {

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

    @OptIn(ExperimentalForeignApi::class)
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

    @OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
    fun runApp(argv: Array<String>): Int {
        fun internalLogShort(exitCode: Int) {
            val shortMessage = "ErrorCode=$exitCode, the program finished abnormally"
            Logger.safeGet().log(shortMessage)
        }

        fun internalLogLong(ex: Exception, exitCode: Int) {
            val longMessage = "ErrorCode=$exitCode, Exception was thrown: " +
                    ex.message + "\n\n" + ex.stackTraceToString()
            Logger.safeGet().log(longMessage)
        }

        return try {
            this.parse(argv)
            SUCCESSFUL_RETURN
        } catch (ex: Exception) {
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

                is CliktError -> {
                    theApp.currentContext.command.echoFormattedHelp(ex)
                    exitCode = COMMAND_LINE_PARSING_ERROR
                    internalLogShort(exitCode)
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

val theApp = PWApp()

@OptIn(ExperimentalNativeApi::class)
fun main(args: Array<String>) {
    exitProcess(theApp.runApp(args))
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
