package org.sergy.pipewrapper

import kotlinx.cinterop.*
import platform.windows.*

@OptIn(ExperimentalForeignApi::class)
object IntraLock {
    private val criticalSectionAllocation = nativeHeap.alloc<CRITICAL_SECTION>()
    val criticalSectionPtr: CPointer<CRITICAL_SECTION> = criticalSectionAllocation.ptr

    init {
        InitializeCriticalSection(criticalSectionPtr)
    }

    inline fun <T> synchronized(block: () -> T): T {
        EnterCriticalSection(criticalSectionPtr)
        try {
            return block()
        } finally {
            LeaveCriticalSection(criticalSectionPtr)
        }
    }

    fun dispose() {
        DeleteCriticalSection(criticalSectionPtr)
        nativeHeap.free(criticalSectionAllocation)
    }
}
