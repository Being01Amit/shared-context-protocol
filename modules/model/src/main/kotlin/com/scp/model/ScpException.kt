package com.scp.model

/** Base type for domain errors that cross module boundaries as structured failures. */
public sealed class ScpException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A referenced entity (project, session, ...) does not exist. */
public class NotFoundException(message: String) : ScpException(message)

/** Input rejected at the boundary (Konform validation or domain guard). */
public class InvalidInputException(message: String) : ScpException(message)
