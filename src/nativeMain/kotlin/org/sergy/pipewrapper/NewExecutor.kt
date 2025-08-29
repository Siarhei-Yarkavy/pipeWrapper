package org.sergy.pipewrapper

import kotlinx.cinterop.*
import org.sergy.pipewrapper.exception.PWIllegalStateException
import org.sergy.pipewrapper.exception.PWRuntimeException
import platform.posix.stdin
import platform.windows.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class NewExecutor {
    companion object {
        const val DEFAULT_TIMEOUT: Int = 60 * 5 //in seconds
    }
    private data class ProcessData(
        val cmdLine: String,
        val piProcInfo: PROCESS_INFORMATION,
        val siStartInfo: STARTUPINFOW
    )

    private val scope: MemScope
    private val pipeMode: Boolean
    private val actualTimeOut: Int
    var shouldShutShutdown: AtomicBoolean = AtomicBoolean(false)
    private lateinit var hReadPipe: HANDLEVar
    private lateinit var hWritePipe: HANDLEVar

    private val appProcessData = mutableMapOf<Executable, ProcessData>()

    constructor(cmdConfig: CmdConfig, pScope: MemScope) {

        fun provideChildStdInput() : CPointer<out CPointed>? {
            return if (cmdConfig.runInTextConsole) {
                //We should force shutdown the children
                val saAttr = scope.alloc<SECURITY_ATTRIBUTES>().apply {
                    nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt()
                    bInheritHandle = TRUE
                    lpSecurityDescriptor = null
                }

                // 1. Create anonymous pipe
                val hNReadPipe = scope.alloc<HANDLEVar>()
                val hNWritePipe = scope.alloc<HANDLEVar>()
                try {
                    if (CreatePipe(
                            hNReadPipe.ptr,
                            hNWritePipe.ptr,
                            saAttr.ptr,
                            0U //system default size
                        ) == 0
                    ) {
                        val errorMessage = "Create pipe failed! WinAPI error code=${GetLastError()}"
                        Logger.get().log(errorMessage)
                        throw PWRuntimeException(CREATE_PIPE_FAILED, errorMessage)
                    }
                    //this ugly hack we have because apps like qaac.exe don't react to NUL or closed pipe
                    val data = byteArrayOf(0x0)
                    val bytesWritten = scope.alloc<DWORDVar>()
                    data.usePinned { pinned ->
                        val success = WriteFile(
                            hNWritePipe.value,
                            pinned.addressOf(0),
                            data.size.toUInt(),
                            bytesWritten.ptr,
                            null
                        )
                        if (success == 0) {
                            Logger.get().log("Failed to send zero bytes to child stdin, " +
                                    "winAPI error code=${GetLastError()}")
                        } else {
                            Logger.get().log(
                                "Sent ${bytesWritten.value} zero bytes into child app stdin to " +
                                        "'break' it and force shutdown"
                            )
                        }
                    }
                } finally {
                    CloseHandle(hNWritePipe.value)
                }
                hNReadPipe.value
            } else {
                GetStdHandle(STD_INPUT_HANDLE)
            }
        }

        fun getCmdStringFromConfig(app: Executable, cmdConfig: CmdConfig): String {
            val exeConfigReader = ExeConfigReader.get()
            val exeLineConfig = exeConfigReader.getConfig(app)
            val builder = StringBuilder(exeLineConfig.path)
            for (paramsEntry in exeLineConfig.params) {
                builder.append(" ").append(paramsEntry.key)
                if (paramsEntry.value.isNotEmpty()) {
                    builder.append(" ")
                    var paramValue: String = paramsEntry.value
                    cmdConfig.itemsList?.forEachIndexed { index, arg ->
                        paramValue = paramValue.replace("%${index + 1}", arg)
                    }
                    builder.append(paramValue)
                }
            }
            return builder.toString()
        }

        try {
            this.scope = pScope
            actualTimeOut =  cmdConfig.t?.toIntOrNull() ?: DEFAULT_TIMEOUT
            var producerCmdString: String? = null
            var consumerCmdString: String

            if(cmdConfig.profile == NULL_PROFILE_NAME) {
                if (cmdConfig.itemsList == null ||
                    cmdConfig.itemsList.size != 2) {
                    throw PWIllegalStateException(3333,"'NUL' profile requires two cmd line " +
                            "arguments!")
                }
                pipeMode = true
                producerCmdString = cmdConfig.itemsList[0].ifEmpty {
                    throw PWIllegalStateException(
                        PRODUCER_CREATION_FAILED,
                        "Empty producer cmd line argument argument!")
                }
                consumerCmdString = cmdConfig.itemsList[1].ifEmpty {
                    throw PWIllegalStateException(
                        CONSUMER_CREATION_FAILED,
                        "Empty producer cmd line argument argument!")
                }
            } else {
                ExeConfigReader.install(cmdConfig.profile)
                if (!ExeConfigReader.get().configExists(Executable.CONSUMER)) {
                    throw PWIllegalStateException(
                      CONFIGURATION_CONSUMER_ABSENT, "Consumer configuration is mandatory!"
                   )
                }
                pipeMode = ExeConfigReader.get().configExists(Executable.PRODUCER)
                if (pipeMode) {
                    producerCmdString = getCmdStringFromConfig(Executable.PRODUCER, cmdConfig)
                }
                consumerCmdString = getCmdStringFromConfig(Executable.CONSUMER, cmdConfig)
            }

            if (pipeMode) {
                val saAttr = scope.alloc<SECURITY_ATTRIBUTES>().apply {
                    nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt()
                    bInheritHandle = TRUE
                    lpSecurityDescriptor = null
                }

                // 1. Create anonymous pipe
                hReadPipe = scope.alloc<HANDLEVar>()
                hWritePipe = scope.alloc<HANDLEVar>()
                if (CreatePipe(
                        hReadPipe.ptr,
                        hWritePipe.ptr,
                        saAttr.ptr,
                        0U //system default size
                    ) == 0
                ) {
                    val errorMessage = "Create pipe failed! ${GetLastError()}"
                    Logger.get().log(errorMessage)
                    throw PWRuntimeException(CREATE_PIPE_FAILED, errorMessage)
                }

                val producerPiProcInfo = scope.alloc<PROCESS_INFORMATION>().apply { initProcessInfo() }
                val producerSiStartInfo = scope.alloc<STARTUPINFOW>().apply { initStartupInfo() }

                producerSiStartInfo.apply {
                    hStdInput = provideChildStdInput()
                    hStdError = Logger.get().getExeErrorLoggingHandle(Executable.PRODUCER)
                    // Reroute producer output to pipe
                    hStdOutput = hWritePipe.value
                }

                appProcessData[Executable.PRODUCER] =
                    ProcessData(producerCmdString!!, producerPiProcInfo, producerSiStartInfo)

            }

            val consumerPiProcInfo = scope.alloc<PROCESS_INFORMATION>().apply { initProcessInfo() }
            val consumerSiStartInfo = scope.alloc<STARTUPINFOW>().apply { initStartupInfo() }

            consumerSiStartInfo.apply {
                // in pipe mode we are getting data from pipe, else - inherited stdin
                hStdInput = if (pipeMode) hReadPipe.value else provideChildStdInput()
                hStdError = Logger.get().getExeErrorLoggingHandle(Executable.CONSUMER)
                hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE)
            }

            appProcessData[Executable.CONSUMER] =
                ProcessData(consumerCmdString, consumerPiProcInfo, consumerSiStartInfo)
        } catch (th : Throwable) {
            Logger.get().log("Error when building executor" +
                    if (th.message != null) ", details are: ${th.message}" else ""
            )
            cleanUp()
            throw th
        }
    }

    fun executePipeline(): Int {
        try {
            if (pipeMode) {
                val fullProducerCmd =
                    appProcessData[Executable.PRODUCER]!!.cmdLine.wcstr.getPointer(scope)
                Logger.get().log("\nProducer cmd = ${fullProducerCmd.toKString()}")

                if (CreateProcessW(
                        null,
                        fullProducerCmd,
                        null,
                        null,
                        TRUE,
                        0u,
                        null,
                        null,
                        appProcessData[Executable.PRODUCER]!!.siStartInfo.ptr,
                        appProcessData[Executable.PRODUCER]!!.piProcInfo.ptr
                    ) == 0
                ) {
                    val message = "Create producer Failed! WinAPI error code=${GetLastError()}"
                    Logger.get().log(message)
                    safeCloseHandle(hWritePipe)
                    safeCloseHandle(hReadPipe)
                    throw PWRuntimeException(PRODUCER_CREATION_FAILED, message)
                }

                val prodPid = appProcessData[Executable.PRODUCER]!!.piProcInfo.dwProcessId
                Logger.get().log("PRODUCER created. PID=$prodPid")
                safeCloseSiStdinHandle(appProcessData[Executable.PRODUCER]!!.siStartInfo)
                //We created process so could close handle on wrapper side
                safeCloseHandle(hWritePipe)
            }

            val fullConsumerCmd = appProcessData[Executable.CONSUMER]!!.cmdLine.wcstr.getPointer(scope)
            Logger.get().log("\nConsumer cmd = ${fullConsumerCmd.toKString()}")
            if (CreateProcessW(
                    null,
                    fullConsumerCmd,
                    null,
                    null,
                    TRUE,
                    0u,
                    null,
                    null,
                    appProcessData[Executable.CONSUMER]!!.siStartInfo.ptr,
                    appProcessData[Executable.CONSUMER]!!.piProcInfo.ptr
                ) == 0
            ) {
                val message = "Create CONSUMER Failed! WinAPI error code=${GetLastError()}}"
                Logger.get().log(message)
                if (pipeMode) {
                    safeCloseHandle(hWritePipe)
                    safeCloseHandle(hReadPipe)
                    requestProcessTermination(Executable.PRODUCER, 2000)
                }
                throw PWRuntimeException(CONSUMER_CREATION_FAILED, message)
            }
            //CloseHandle(appProcessData[Executable.CONSUMER]!!.siStartInfo.hStdInput)
            val consumerPid = appProcessData[Executable.CONSUMER]!!.piProcInfo.dwProcessId
            Logger.get().log("CONSUMER created. PID=$consumerPid")

            //now checking if consumer failed? fast after start, producer might be in hung state this case
            val consumerFailedOrFinishedFast = isProcessTerminated(Executable.CONSUMER, 3000)

            var producerExitCode: Int? = null
            val initialShutdownValue: Boolean = consumerFailedOrFinishedFast
                    || !isPipeInitializedOrUnknown(appProcessData[Executable.CONSUMER]!!.siStartInfo.hStdInput)
            safeCloseSiStdinHandle(appProcessData[Executable.CONSUMER]!!.siStartInfo)
            //As consumer was able to start closing our end of pipe
            if (pipeMode) safeCloseHandle(hReadPipe)

            var passedSeconds = 0
            val throttleTime = 5 //in seconds
            val receivedShouldShutdown = shouldShutShutdown.compareAndExchange(false,
                    initialShutdownValue)
            val infiniteTimeoutValue = 0
            Logger.get().log("Timeout to finish operation is " +
                    if (actualTimeOut == infiniteTimeoutValue) "not set" else "$actualTimeOut seconds")

            while (actualTimeOut == infiniteTimeoutValue ||
                passedSeconds < actualTimeOut && !shouldShutShutdown.load()) {
                if (isProcessTerminated(Executable.CONSUMER, throttleTime *
                            if (pipeMode) 500 else 1000)) break
                if (pipeMode) {
                    if (isProcessTerminated(Executable.PRODUCER, throttleTime * 500)) break
                }
                passedSeconds = passedSeconds.plus(throttleTime)
                val secInMin = 60
                if (passedSeconds % secInMin == 0) {
                    Logger.get().log("Passed minutes: ${passedSeconds/secInMin}")
                }
            }

            if (initialShutdownValue != shouldShutShutdown.load() || receivedShouldShutdown) {
                Logger.get().log("Received shutdown signal!")
            }
            if (appProcessData[Executable.PRODUCER]?.piProcInfo?.hProcess != null) {
                producerExitCode = requestProcessTermination(Executable.PRODUCER, 1000)
            }

            val consumerExitCode = requestProcessTermination(Executable.CONSUMER, 5000)

            val producerTerminated = producerExitCode == CHILD_PROCESS_WAS_KILLED
            val consumerTerminated = consumerExitCode == CHILD_PROCESS_WAS_KILLED

            val producerFailed = producerExitCode != null &&
                producerExitCode != SUCCESSFUL_RETURN && !producerTerminated

            val consumerFailed = consumerExitCode != SUCCESSFUL_RETURN && !consumerTerminated

            if (producerFailed || consumerFailed) {
                return AT_LEAST_ONE_CHILD_FAILED
            }
            if (producerTerminated || consumerTerminated) {
                return CHILD_PROCESS_WAS_KILLED
            }
            return SUCCESSFUL_RETURN

        } finally {
            cleanUp()
        }
    }

    private fun safeCloseHandle(handle: HANDLEVar?) {
        handle?.value = handle.value?.takeIf { it != INVALID_HANDLE_VALUE }.let {
                    CloseHandle(it); INVALID_HANDLE_VALUE
                }
    }

    private fun safeCloseSiStdinHandle(si: STARTUPINFOW?) {
        si?.run {
            CloseHandle(stdin?.takeIf { it != INVALID_HANDLE_VALUE })
        }
    }

    private fun safeCloseHandle(pi: PROCESS_INFORMATION?) {
        pi?.run {
            hProcess = hProcess?.takeIf { it != INVALID_HANDLE_VALUE }.let {
                CloseHandle(it); INVALID_HANDLE_VALUE
            }
            hThread = hThread?.takeIf { it != INVALID_HANDLE_VALUE }.let {
                CloseHandle(it); INVALID_HANDLE_VALUE
            }
        }
    }

    private fun requestProcessTermination(exe: Executable, timeout: Int): Int {
        // Time to take graceful shutdown
        val waitResult = WaitForSingleObject(
            appProcessData[exe]!!.piProcInfo.hProcess,
            timeout.toUInt()
        )

        // if Process still alive - kill it
        if (waitResult == WAIT_TIMEOUT.toUInt()) {
            if (TerminateProcess(
                    appProcessData[exe]!!.piProcInfo.hProcess,
                    CHILD_PROCESS_WAS_KILLED.toUInt()
                ) == 0
            ) {
                Logger.get().log("Process termination of ${exe.exeName}  was failed: ${GetLastError()}")
            } else {
                Logger.get().log("${exe.exeName.uppercase()} forcibly terminated")
            }
        } else if (waitResult == WAIT_OBJECT_0) {
            Logger.get().log("${exe.exeName.uppercase()} exited gracefully")
        } else {
            throw PWIllegalStateException(
                EXECUTABLE_STATE_ERROR,
                "Unexpected state of executable ${exe.exeName.uppercase()}"
            )
        }

        val appExitCodeMem: UIntVar = scope.alloc<UIntVar>()
        GetExitCodeProcess(
            appProcessData[exe]!!.piProcInfo.hProcess,
            appExitCodeMem.ptr
        )
        val unsignedValue: UInt = appExitCodeMem.value
        val signedValue: Int = unsignedValue.toInt()
        Logger.get().log("${exe.exeName.uppercase()} exit code $signedValue")

        safeCloseHandle(appProcessData[exe]!!.piProcInfo)
        return signedValue
    }

    private fun isProcessTerminated(exe: Executable, timeout: Int): Boolean =
        WAIT_OBJECT_0 == WaitForSingleObject(
            appProcessData[exe]!!.piProcInfo.hProcess,timeout.toUInt())

    private fun isPipeInitializedOrUnknown(handle: HANDLE?): Boolean {
        val waitResult = WaitForSingleObject(handle, 1000u)

        when (waitResult) {
            WAIT_TIMEOUT.toUInt() -> {
                Logger.get().log("Data handle is not initialized, is it stuck? Will terminate EXEs")
                return false
            }
            WAIT_OBJECT_0 -> {
                Logger.get().log("Data handle initialized")
                return true
            }
            WAIT_FAILED -> {
                Logger.get().log("Data handle looks invalid, stopping")
                return false
            }
            else -> return false
        }

    }

    private fun STARTUPINFO.initStartupInfo() {
        SecureZeroMemory!!.invoke(ptr, sizeOf<STARTUPINFO>().toULong())
        cb = sizeOf<STARTUPINFO>().toUInt()
        dwFlags = STARTF_USESTDHANDLES.toUInt()
    }

    private fun PROCESS_INFORMATION.initProcessInfo() {
        // memset looks as possible alternative
        SecureZeroMemory!!.invoke(ptr, sizeOf<PROCESS_INFORMATION>().toULong())
    }

    private fun cleanUp() {
        if (this::hReadPipe.isInitialized) {
            safeCloseHandle(hReadPipe)
        }
        if (this::hWritePipe.isInitialized) {
            safeCloseHandle(hWritePipe)
        }
        for (exe in Executable.entries) {
            safeCloseSiStdinHandle(appProcessData[exe]?.siStartInfo)
            safeCloseHandle(appProcessData[exe]?.piProcInfo)
        }
    }

}