package com.anarky.showtrack.core.data.mapper

/**
 * Decodes a server enum string tolerantly: a case-insensitive match on the member's `name`, or
 * [unknown] for anything else (a value a newer backend added, or a missing one). A mapper must
 * never fail a whole page over one value it does not recognise.
 */
internal inline fun <reified E : Enum<E>> wireEnum(
    raw: String?,
    unknown: E,
): E = enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: unknown

/**
 * The lower-case spelling the server expects. `UNKNOWN` is a client-only placeholder and is never
 * a wire value: asking for it throws, so a request carrying it fails before it is sent.
 */
internal fun Enum<*>.wireValue(): String {
    require(name != "UNKNOWN") { "UNKNOWN is not a wire value" }
    return name.lowercase()
}
