package org.sergy.pipewrapper.exception

interface ErrorCodeAware {
    val errorCode: Int
}

class PWRuntimeException(
    override val errorCode: Int,
    message: String? = null,
    cause: Throwable? = null

) : RuntimeException(message, cause), ErrorCodeAware

class PWIllegalStateException(
    override val errorCode: Int,
    message: String? = null,
    cause: Throwable? = null
) : IllegalStateException(message, cause), ErrorCodeAware


