package dev.jetpack.engine.runtime.module

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.JBool
import dev.jetpack.engine.runtime.JetValue.JBuiltin
import dev.jetpack.engine.runtime.JetValue.JCommand
import dev.jetpack.engine.runtime.JetValue.JFloat
import dev.jetpack.engine.runtime.JetValue.JFunction
import dev.jetpack.engine.runtime.JetValue.JInt
import dev.jetpack.engine.runtime.JetValue.JInterval
import dev.jetpack.engine.runtime.JetValue.JList
import dev.jetpack.engine.runtime.JetValue.JListener
import dev.jetpack.engine.runtime.JetValue.JModule
import dev.jetpack.engine.runtime.JetValue.JNull
import dev.jetpack.engine.runtime.JetValue.JObject
import dev.jetpack.engine.runtime.JetValue.JString
import java.util.IdentityHashMap

class JsonModule {

    fun spec(): ModuleSpec = ModuleSpec(
        name = "json",
        value = asValue(),
        fields = mapOf(
            "parse" to callable(JetType.TUnknown, signature(JetType.TString)),
            "stringify" to callable(JetType.TString, signature(JetType.TUnknown)),
            "valid" to callable(JetType.TBool, signature(JetType.TString)),
        ),
    )

    fun asValue(): JetValue.JModule = JModule(
        mutableMapOf(
            "parse" to builtin(::parse),
            "stringify" to builtin(::stringify),
            "valid" to builtin(::valid),
        ),
    )

    private fun builtin(handler: (List<JetValue>) -> JetValue): JetValue = JBuiltin { handler(it) }

    private fun parse(args: List<JetValue>): JetValue {
        val text = (args[0] as JString).value
        val element = try {
            JsonParser.parseString(text)
        } catch (_: Exception) {
            throw RuntimeException("Function 'json.parse' received invalid JSON")
        }
        return deserializeValue(element)
    }

    private fun stringify(args: List<JetValue>): JetValue {
        val element = try {
            serializeValue(args[0], IdentityHashMap())
        } catch (error: IllegalArgumentException) {
            throw RuntimeException(error.message ?: "Function 'json.stringify' failed")
        }
        return JString(element.toString())
    }

    private fun valid(args: List<JetValue>): JetValue {
        val text = (args[0] as JString).value
        return JBool(
            runCatching { JsonParser.parseString(text) }.isSuccess
        )
    }

    private fun serializeValue(value: JetValue, visiting: IdentityHashMap<Any, Boolean>): JsonElement = when (value) {
        is JNull -> JsonNull.INSTANCE
        is JBool -> JsonPrimitive(value.value)
        is JInt -> JsonPrimitive(value.value)
        is JFloat -> JsonPrimitive(value.value)
        is JString -> JsonPrimitive(value.value)
        is JFunction, is JBuiltin, is JInterval, is JListener, is JCommand, is JModule ->
            throw IllegalArgumentException("Function 'json.stringify' only supports JSON-compatible values")
        is JList -> {
            if (visiting.put(value, true) != null) {
                throw IllegalArgumentException("Function 'json.stringify' does not support cyclic values")
            }
            try {
                JsonArray().apply {
                    value.elements.forEach { add(serializeValue(it, visiting)) }
                }
            } finally {
                visiting.remove(value)
            }
        }
        is JObject -> {
            if (value.hasDeferredMembers()) {
                throw IllegalArgumentException("Function 'json.stringify' only supports fully materialized objects")
            }
            if (visiting.put(value, true) != null) {
                throw IllegalArgumentException("Function 'json.stringify' does not support cyclic values")
            }
            try {
                JsonObject().apply {
                    for ((key, fieldValue) in value.fields) {
                        add(key, serializeValue(fieldValue, visiting))
                    }
                }
            } finally {
                visiting.remove(value)
            }
        }
    }

    private fun deserializeValue(element: JsonElement): JetValue = when {
        element.isJsonNull -> JNull
        element.isJsonPrimitive -> {
            val primitive = element.asJsonPrimitive
            when {
                primitive.isBoolean -> JBool(primitive.asBoolean)
                primitive.isNumber -> {
                    val raw = primitive.asString
                    if ('.' in raw || 'e' in raw || 'E' in raw) JFloat(primitive.asDouble)
                    else JInt(primitive.asInt)
                }
                primitive.isString -> JString(primitive.asString)
                else -> throw RuntimeException("Function 'json.parse' produced an unsupported primitive value")
            }
        }
        element.isJsonArray -> JList(element.asJsonArray.map(::deserializeValue).toMutableList())
        element.isJsonObject -> {
            val fields = linkedMapOf<String, JetValue>()
            for ((key, fieldValue) in element.asJsonObject.entrySet()) {
                fields[key] = deserializeValue(fieldValue)
            }
            JObject(fields.toMutableMap())
        }
        else -> throw RuntimeException("Function 'json.parse' produced an unsupported value")
    }
}
