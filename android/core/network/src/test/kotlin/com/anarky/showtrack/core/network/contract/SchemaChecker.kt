package com.anarky.showtrack.core.network.contract

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Compares kotlinx.serialization descriptors with the `components.schemas` of an OpenAPI 3.1
 * document as FastAPI/pydantic v2 emits it. Every check returns its problems as
 * `"<Component>.<field>: <reason>"`; an empty list means the shapes agree.
 *
 * Schema shapes understood: `$ref` to `#/components/schemas/X`; `anyOf: [X, {"type": "null"}]`
 * (nullable X); plain `type` schemas, a string `enum` counting as a string. Anything else
 * (`oneOf`, `allOf`, an `anyOf` with two non-null branches) is reported as an unsupported shape
 * rather than passed.
 *
 * Two rules go slightly beyond "required and non-null", because of how the app's `Json` is built
 * (`ignoreUnknownKeys` only, so no `coerceInputValues` and `encodeDefaults` off):
 *  - an explicit `null` fails to decode into a non-null field even when that field has a default,
 *    so any non-null client field is checked against server nullability;
 *  - a request field with a default is not encoded, so it does not satisfy a server `required`.
 */
@OptIn(ExperimentalSerializationApi::class)
class SchemaChecker(
    snapshot: JsonObject,
) {
    private val schemas: JsonObject =
        snapshot
            .getValue("components")
            .jsonObject
            .getValue("schemas")
            .jsonObject

    /** What a property schema resolves to once `$ref` and a nullable `anyOf` are unwrapped. */
    private sealed interface Resolved {
        data class Schema(
            val schema: JsonObject,
            val nullable: Boolean,
            /** The component the schema came from, when it came through a `$ref`. */
            val component: String?,
        ) : Resolved

        data class Problem(
            val reason: String,
        ) : Resolved
    }

    /** Every client field exists on the server, with a compatible type and nullability. */
    fun checkResponse(
        descriptor: SerialDescriptor,
        component: String,
    ): List<String> {
        val problems = mutableListOf<String>()
        val schema = schemas[component]?.jsonObject ?: return listOf("$component: no such component")
        checkObject(descriptor, schema, component, problems, mutableSetOf())
        return problems
    }

    /** Every field the server requires is one the client sends. */
    fun checkRequest(
        descriptor: SerialDescriptor,
        component: String,
    ): List<String> {
        val schema = schemas[component]?.jsonObject ?: return listOf("$component: no such component")
        val sent = descriptor.elementNames.filterIndexed { i, _ -> !descriptor.isElementOptional(i) }.toSet()
        val defaulted = descriptor.elementNames.toSet() - sent
        return schema.stringList("required").mapNotNull { name ->
            when (name) {
                in sent -> null
                in defaulted -> "$component.$name: required by the server but the client omits it by default"
                else -> "$component.$name: required by the server but the client never sends it"
            }
        }
    }

    /** Every value the client knows is one the server can send for `component.property`. */
    fun checkEnum(
        component: String,
        property: String,
        values: Collection<String>,
    ): List<String> {
        val where = "$component.$property"
        val raw =
            schemas[component]
                ?.jsonObject
                ?.get("properties")
                ?.jsonObject
                ?.get(property)
        val resolved = raw?.let { resolve(it.jsonObject) }
        val known = (resolved as? Resolved.Schema)?.schema?.stringList("enum")?.toSet()
        return when {
            resolved == null -> listOf("$where: no such property")
            resolved is Resolved.Problem -> listOf("$where: ${resolved.reason}")
            known.isNullOrEmpty() -> listOf("$where: not an enum")
            else -> values.filterNot { it in known }.map { "$where: value '$it' is not a server value" }
        }
    }

    private fun checkObject(
        descriptor: SerialDescriptor,
        schema: JsonObject,
        label: String,
        problems: MutableList<String>,
        visited: MutableSet<Pair<String, String>>,
    ) {
        if (!visited.add(descriptor.serialName to label)) return
        val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val required = schema.stringList("required").toSet()
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            val where = "$label.$name"
            val property = properties[name]?.jsonObject
            if (property == null) {
                problems += "$where: not in the server schema"
                continue
            }
            val element = descriptor.getElementDescriptor(i)
            if (!descriptor.isElementOptional(i) && !element.isNullable && name !in required) {
                problems += "$where: required on the client but the server may omit it"
            }
            checkSlot(element, property, where, problems, visited)
        }
    }

    /** One value position — a property, a list item, a map value — nullability first, then type. */
    private fun checkSlot(
        element: SerialDescriptor,
        raw: JsonObject,
        where: String,
        problems: MutableList<String>,
        visited: MutableSet<Pair<String, String>>,
    ) {
        when (val resolved = resolve(raw)) {
            is Resolved.Problem -> problems += "$where: ${resolved.reason}"
            is Resolved.Schema -> {
                if (resolved.nullable && !element.isNullable) {
                    problems += "$where: non-null on the client but nullable on the server"
                }
                checkType(element, resolved, where, problems, visited)
            }
        }
    }

    private fun checkType(
        element: SerialDescriptor,
        resolved: Resolved.Schema,
        where: String,
        problems: MutableList<String>,
        visited: MutableSet<Pair<String, String>>,
    ) {
        val schema = resolved.schema
        val type = (schema["type"] as? JsonPrimitive)?.content
        val expected = WIRE_TYPES[element.kind]
        when {
            // A JsonElement field takes any JSON value, so there is nothing to compare.
            element.isJsonElement() -> Unit
            expected == null -> problems += "$where: unsupported client kind ${element.kind}"
            type !in expected -> problems += "$where: client expects ${element.kind} but the server sends $type"
            element.kind == StructureKind.LIST -> checkItems(element, schema, where, problems, visited)
            element.kind == StructureKind.MAP -> checkMapValues(element, schema, where, problems, visited)
            element.kind == StructureKind.CLASS ->
                checkObject(
                    element,
                    schema,
                    resolved.component ?: where,
                    problems,
                    visited,
                )
        }
    }

    private fun checkItems(
        element: SerialDescriptor,
        schema: JsonObject,
        where: String,
        problems: MutableList<String>,
        visited: MutableSet<Pair<String, String>>,
    ) {
        val items = schema["items"]?.jsonObject
        if (items == null) {
            problems += "$where: array without items"
        } else {
            checkSlot(element.getElementDescriptor(0), items, "$where[]", problems, visited)
        }
    }

    /**
     * Map keys are JSON object keys, so always strings; a `propertyNames` enum is still a string.
     * `additionalProperties: true` (any value) is only compatible with a JsonElement value.
     */
    private fun checkMapValues(
        element: SerialDescriptor,
        schema: JsonObject,
        where: String,
        problems: MutableList<String>,
        visited: MutableSet<Pair<String, String>>,
    ) {
        val value = element.getElementDescriptor(1)
        when (val additional = schema["additionalProperties"]) {
            is JsonObject -> checkSlot(value, additional, "$where{}", problems, visited)
            JsonPrimitive(true) ->
                if (!value.isJsonElement()) {
                    problems += "$where: the server allows any value, the client expects ${value.kind}"
                }

            else -> problems += "$where: object without additionalProperties"
        }
    }

    private fun resolve(
        raw: JsonObject,
        nullable: Boolean = false,
        component: String? = null,
    ): Resolved {
        raw["\$ref"]?.let { ref ->
            val name = ref.jsonPrimitive.content.removePrefix(REF_PREFIX)
            val target = schemas[name]?.jsonObject ?: return Resolved.Problem("unresolved \$ref $name")
            return resolve(target, nullable, name)
        }
        if (UNSUPPORTED.any { it in raw }) return Resolved.Problem("unsupported schema shape")
        val anyOf = raw["anyOf"] ?: return Resolved.Schema(raw, nullable, component)
        val (nulls, others) = anyOf.jsonArray.map { it.jsonObject }.partition { it == NULL_SCHEMA }
        if (nulls.size != 1 || others.size != 1) return Resolved.Problem("unsupported schema shape")
        return resolve(others.single(), nullable = true, component = component)
    }

    private fun JsonObject.stringList(key: String): List<String> =
        this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun SerialDescriptor.isJsonElement(): Boolean = serialName.removeSuffix("?") == JSON_ELEMENT

    private companion object {
        const val REF_PREFIX = "#/components/schemas/"
        const val JSON_ELEMENT = "kotlinx.serialization.json.JsonElement"

        /** The OpenAPI `type`s each client kind decodes from; a JSON integer is a valid number. */
        val WIRE_TYPES: Map<SerialKind, Set<String>> =
            mapOf(
                PrimitiveKind.STRING to setOf("string"),
                PrimitiveKind.INT to setOf("integer"),
                PrimitiveKind.LONG to setOf("integer"),
                PrimitiveKind.BOOLEAN to setOf("boolean"),
                PrimitiveKind.DOUBLE to setOf("number", "integer"),
                PrimitiveKind.FLOAT to setOf("number", "integer"),
                StructureKind.LIST to setOf("array"),
                StructureKind.MAP to setOf("object"),
                StructureKind.CLASS to setOf("object"),
            )
        val UNSUPPORTED = listOf("oneOf", "allOf", "not")
        val NULL_SCHEMA = JsonObject(mapOf("type" to JsonPrimitive("null")))
    }
}
