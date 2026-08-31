package com.scp.model.mcp

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * A timestamp field that never fails the request it arrives in.
 *
 * Callers are language models writing a plain string into a schema that only says
 * "ISO 8601 UTC". The strict [Instant] serializer rejects everything but the canonical
 * form, and because deserialization happens before validation and before the skill layer,
 * one badly formatted timestamp discards the entire `update_context` payload — every
 * entry, decision, todo and file in the call — with nothing written to the log.
 *
 * So this accepts the forms models actually produce and falls back to `null` (meaning
 * "use now", per `UpdateContextUseCase.toRedactedEntry`) rather than throwing:
 *
 *  - `2026-08-31T10:00:00Z`, or with any offset      -> exact
 *  - `2026-08-31T10:00:00` (no zone)                 -> read as UTC
 *  - `2026-08-31 10:00:00` (space separator)         -> read as UTC
 *  - `2026-08-31`                                    -> start of that day, UTC
 *  - epoch seconds or milliseconds, as JSON number   -> exact
 *  - anything else, or JSON null                     -> null
 *
 * Dropping a timestamp costs one entry its exact time; throwing costs the whole session.
 *
 * The opt-in is unavoidable: `SerialDescriptor.nullable` and `Encoder.encodeNull` are both still
 * experimental, and a custom serializer over a nullable type cannot be written without them.
 */
@OptIn(ExperimentalSerializationApi::class)
public object LenientInstantSerializer : KSerializer<Instant?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.scp.model.mcp.LenientInstant", PrimitiveKind.STRING).nullable

    override fun serialize(encoder: Encoder, value: Instant?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant? {
        val jsonDecoder = decoder as? JsonDecoder ?: return parseOrNull(decoder.decodeString())
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive ->
                when {
                    element is JsonNull -> null
                    element.isString -> parseOrNull(element.content)
                    // A bare number is epoch time; >= 1e12 is milliseconds, otherwise seconds.
                    else -> element.content.toLongOrNull()?.let(::fromEpochNumber)
                }
            else -> null
        }
    }

    private fun fromEpochNumber(value: Long): Instant =
        if (value >= MILLIS_THRESHOLD) Instant.fromEpochMilliseconds(value) else Instant.fromEpochSeconds(value)

    private fun parseOrNull(raw: String): Instant? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // The 'T' separator is required by every parser below; models often send a space instead.
        val normalized = text.replaceFirst(' ', 'T')
        return tryParse { Instant.parse(normalized) }
            ?: tryParse { LocalDateTime.parse(normalized).toInstant(TimeZone.UTC) }
            ?: tryParse { LocalDate.parse(normalized).atStartOfDayIn(TimeZone.UTC) }
    }

    private fun tryParse(block: () -> Instant): Instant? =
        try {
            block()
        } catch (
            // Every parser signals a bad format with its own exception type; all mean "not this form".
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            null
        }

    private const val MILLIS_THRESHOLD: Long = 1_000_000_000_000L
}
