package org.sergy.pipewrapper

import kotlinx.cinterop.*
import org.sergy.pipewrapper.exception.PWIllegalStateException
import org.sergy.pipewrapper.exception.PWRuntimeException
import platform.windows.*

@OptIn(ExperimentalForeignApi::class)
class NewExecutor {
    private data class ProcessData(
        val cmdLine: String,
        val piProcInfo: PROCESS_INFORMATION,
        val siStartInfo: STARTUPINFOW
    )

    private val scope: MemScope
    private val pipeMode: Boolean
    private lateinit var hReadPipe: HANDLEVar
    private lateinit var hWritePipe: HANDLEVar

    private val appProcessData = mutableMapOf<Executable, ProcessData>()

    constructor(cmdConfig: CmdConfig, pScope: MemScope) {
        try {
            ExeConfigReader.install(cmdConfig.profile)
            this.scope = pScope
            if (!ExeConfigReader.get().configExists(Executable.CONSUMER)) {
                throw PWIllegalStateException(
                    CONFIGURATION_CONSUMER_ABSENT, "Consumer configuration is mandatory!"
                )
            }
            pipeMode = ExeConfigReader.get().configExists(Executable.PRODUCER)

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
                    hStdInput = GetStdHandle(STD_INPUT_HANDLE)
                    hStdError = Logger.get().getExeErrorLoggingHandle(Executable.PRODUCER)
                    // Reroute producer output to pipe
                    hStdOutput = hWritePipe.value
                }
                val producerCmdString = getCmdString(Executable.PRODUCER, cmdConfig)
                appProcessData[Executable.PRODUCER] =
                    ProcessData(producerCmdString, producerPiProcInfo, producerSiStartInfo)

            }

            val consumerPiProcInfo = scope.alloc<PROCESS_INFORMATION>().apply { initProcessInfo() }
            val consumerSiStartInfo = scope.alloc<STARTUPINFOW>().apply { initStartupInfo() }

            consumerSiStartInfo.apply {
                dwFlags = STARTF_USESTDHANDLES.toUInt()
                hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE)
                hStdError = Logger.get().getExeErrorLoggingHandle(Executable.CONSUMER)
                // in pipe mode we are getting data from pipe, else - inherited stdin
                hStdInput = if (pipeMode) hReadPipe.value else GetStdHandle(STD_INPUT_HANDLE)
            }
            val consumerCmdString = getCmdString(Executable.CONSUMER, cmdConfig)
            appProcessData[Executable.CONSUMER] =
                ProcessData(consumerCmdString, consumerPiProcInfo, consumerSiStartInfo)
        } catch (th : Throwable) {
            Logger.get().log("Error in executor preparation" +
                    if (th.message != null) ", details are: $th.message" else ""
            )
            cleanUp()
            throw th
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun executePipeline(): Int {
        try {
            if (pipeMode) {
                val fullProducerCmd =
                    appProcessData[Executable.PRODUCER]!!.cmdLine.wcstr.getPointer(scope)
                Logger.get().log("Producer cmd = ${fullProducerCmd.toKString()}")

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
                    val message = "Create producer Failed! ${GetLastError()}"
                    Logger.get().log(message)
                    safeCloseHandle(hWritePipe)
                    safeCloseHandle(hReadPipe)
                    throw PWRuntimeException(PRODUCER_CREATION_FAILED, message)
                }

                val prodPid = appProcessData[Executable.PRODUCER]!!.piProcInfo.dwProcessId
                Logger.get().log("Producer process created. PID=$prodPid")
                //We created process so could close handle on wrapper side
                safeCloseHandle(hWritePipe)
            }

            val fullConsumerCmd = appProcessData[Executable.CONSUMER]!!.cmdLine.wcstr.getPointer(scope)
            Logger.get().log("Consumer cmd = ${fullConsumerCmd.toKString()}")
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
                val message = "Create Consumer Failed! ${GetLastError()}}"
                Logger.get().log(message)
                if (pipeMode) {
                    safeCloseHandle(hWritePipe)
                    safeCloseHandle(hReadPipe)
                    requestProcessTermination(Executable.PRODUCER, 2000)
                }
                throw PWRuntimeException(CONSUMER_CREATION_FAILED, message)
            }
            val consumerPid = appProcessData[Executable.CONSUMER]!!.piProcInfo.dwProcessId
            Logger.get().log("Consumer process created. PID=$consumerPid")

            //now checking if consumer failed? fast after start, producer might be in hung state this case
            val consumerFailedOrFinishedFast = isProcessTerminated(Executable.CONSUMER)
            Logger.get().log("Detected consumer state as " +
                    if (consumerFailedOrFinishedFast) "dead" else "alive"
            )

            var producerExitCode: Int? = null
            val mainTimeOut =  if (consumerFailedOrFinishedFast || !isPipeInitialized()) {
                    2000
                } else {
                    1000 * 60 * 5
                }

            if (pipeMode) {
                safeCloseHandle(hReadPipe) //as consumer was able to start
                producerExitCode = requestProcessTermination(Executable.PRODUCER, mainTimeOut)
            }

            val consumerExitCode = requestProcessTermination(
                Executable.CONSUMER,
                if (pipeMode) 6000/*to allow consumer finalize theirs flow*/ else mainTimeOut
            )

            return if (consumerExitCode != SUCCESSFUL_RETURN ||
                (producerExitCode != null && producerExitCode != SUCCESSFUL_RETURN)
            ) {
                CHILD_PROCESS_WAS_KILLED
            } else {
                SUCCESSFUL_RETURN
            }

        } finally {
            cleanUp()
        }
    }

    private fun getCmdString(app: Executable, cmdConfig: CmdConfig): String {
        val exeConfigReader = ExeConfigReader.get()
        val exeLineConfig = exeConfigReader.getConfig(app)
        val builder = StringBuilder(exeLineConfig.path)
        for (paramsEntry in exeLineConfig.params) {
            builder.append(" ").append(paramsEntry.key)
            if (paramsEntry.value.isNotEmpty()) {
                builder.append(" ")
                var paramValue: String = paramsEntry.value
                cmdConfig.itemsList?.forEachIndexed { index, arg ->
                    val oldValue = paramValue
                    paramValue = paramValue.replace("%${index + 1}", arg)
                    if (!oldValue.equals(paramValue)) {
                        Logger.get().log("Replaced '%${index + 1}' in '${paramsEntry.value}' with '$paramValue'")
                    }
                }
                builder.append(paramValue)
            }
        }
        return builder.toString()
    }

    private fun safeCloseHandle(handle: HANDLEVar?) {
        handle?.value = handle.value?.takeIf { it != INVALID_HANDLE_VALUE }.let {
                    CloseHandle(it); INVALID_HANDLE_VALUE
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

    @OptIn(ExperimentalForeignApi::class)
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
                Logger.get().log("Process ${exe.exeName} forcibly terminated")
            }
        } else if (waitResult == WAIT_OBJECT_0) {
            Logger.get().log("Process ${exe.exeName} exited gracefully")
        } else {
            throw PWIllegalStateException(
                EXECUTABLE_STATE_ERROR,
                "Unknown state of executable ${exe.exeName}"
            )
        }

        val appExitCodeMem: UIntVar = scope.alloc<UIntVar>()
        GetExitCodeProcess(
            appProcessData[exe]!!.piProcInfo.hProcess,
            appExitCodeMem.ptr
        )
        val unsignedValue: UInt = appExitCodeMem.value
        val signedValue: Int = unsignedValue.toInt()
        Logger.get().log("Executable ${exe.exeName} exit code $signedValue")

        safeCloseHandle(appProcessData[exe]!!.piProcInfo)
        return signedValue
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun isProcessTerminated(exe: Executable): Boolean =
        WAIT_OBJECT_0 == WaitForSingleObject(
            appProcessData[exe]!!.piProcInfo.hProcess,
            4000u
        )

    private fun isPipeInitialized(): Boolean {
        val waitResult = WaitForSingleObject(hReadPipe?.value, 1000u)

        if (waitResult == WAIT_TIMEOUT.toUInt()) {
            Logger.get().log("Pipe is not initialized, is it stuck? terminating EXEs")
            return false
        } else if (waitResult == WAIT_OBJECT_0) {
            Logger.get().log("Pipe initialized")
            return true
        }
        return false

    }

    @OptIn(ExperimentalForeignApi::class)
    private fun STARTUPINFO.initStartupInfo() {
        SecureZeroMemory!!.invoke(ptr, sizeOf<STARTUPINFO>().toULong())
        cb = sizeOf<STARTUPINFO>().toUInt()
        dwFlags = STARTF_USESTDHANDLES.toUInt()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun PROCESS_INFORMATION.initProcessInfo() {
        // memset looks as possible alternative
        SecureZeroMemory!!.invoke(ptr, sizeOf<PROCESS_INFORMATION>().toULong())
    }

    private fun cleanUp() {
        safeCloseHandle(hReadPipe)
        safeCloseHandle(hWritePipe)
        for (exe in Executable.entries) {
            safeCloseHandle(appProcessData[exe]?.piProcInfo)
        }
    }

}