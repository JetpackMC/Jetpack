package dev.jetpack.engine.runtime

import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.Param
import dev.jetpack.engine.parser.ast.Statement
import dev.jetpack.engine.parser.ast.toJetTypeOrNull
import java.util.IdentityHashMap

sealed class JetValue {
    data class JFunction(
        val params: List<Param>,
        val body: List<Statement>,
        val closure: Scope,
        val returnType: JetType? = null,
        val requiredCount: Int = params.count { it.default == null },
        val resolvedParamTypes: List<JetType> = params.map { it.typeName?.toJetTypeOrNull() ?: JetType.TUnknown },
    ) : JetValue()

    data class JBuiltin(val fn: suspend (List<JetValue>) -> JetValue) : JetValue()
    data class JInterval(val handle: IntervalHandle) : JetValue()
    data class JListener(val handle: ListenerHandle) : JetValue()
    data class JCommand(
        val handle: CommandHandle,
        val path: List<String> = emptyList(),
        val subcommands: Map<String, JCommand> = emptyMap(),
    ) : JetValue()
    data class JInt(val value: Int) : JetValue() {
        override fun toString() = value.toString()
    }
    data class JFloat(val value: Double) : JetValue()
    data class JString(val value: String) : JetValue() {
        override fun toString() = value
    }
    data class JBool(val value: Boolean) : JetValue() {
        override fun toString() = value.toString()
    }
    data class JList(
        val elements: MutableList<JetValue>,
        val isReadOnly: Boolean = false,
        var declaredElementType: JetType? = null,
    ) : JetValue()
    class JObject(
        val fields: MutableMap<String, JetValue>,
        val isReadOnly: Boolean = false,
        private val memberResolver: ((String) -> JetValue?)? = null,
        private val memberNamesProvider: (() -> Set<String>)? = null,
        private val memberAssigner: ((String, JetValue) -> Unit)? = null,
        private val cacheResolvedMembers: Boolean = true,
        val nativeObject: Any? = null,
    ) : JetValue() {
        fun getField(name: String): JetValue? {
            fields[name]?.let { return it }
            val resolved = memberResolver?.invoke(name) ?: return null
            if (cacheResolvedMembers) {
                fields[name] = resolved
            }
            return resolved
        }

        fun setField(name: String, value: JetValue) {
            memberAssigner?.let { assign ->
                assign(name, value)
                if (cacheResolvedMembers) {
                    fields[name] = value
                }
                return
            }
            if (isReadOnly) {
                throw RuntimeException("Cannot assign member on read-only object")
            }
            fields[name] = value
        }

        fun hasField(name: String): Boolean =
            fields.containsKey(name) || name in deferredMemberNames()

        fun fieldNames(): Set<String> =
            linkedSetOf<String>().apply {
                addAll(fields.keys)
                addAll(deferredMemberNames())
            }

        fun supportsStructuralMutation(): Boolean =
            !isReadOnly && memberResolver == null && memberAssigner == null

        fun hasDeferredMembers(): Boolean = memberResolver != null

        fun deferredMemberNames(): Set<String> = memberNamesProvider?.invoke().orEmpty()
    }
    class JModule(
        val fields: MutableMap<String, JetValue>,
        val declaredType: JetType.TModule = JetType.TModule(emptyMap()),
        private val memberResolver: ((String) -> JetValue?)? = null,
        private val memberNamesProvider: (() -> Set<String>)? = null,
        private val memberAssigner: ((String, JetValue) -> Unit)? = null,
        private val cacheResolvedMembers: Boolean = true,
    ) : JetValue() {
        fun getField(name: String): JetValue? {
            fields[name]?.let { return it }
            val resolved = memberResolver?.invoke(name) ?: return null
            if (cacheResolvedMembers) {
                fields[name] = resolved
            }
            return resolved
        }

        fun setField(name: String, value: JetValue) {
            val assign = memberAssigner
                ?: throw RuntimeException("Cannot assign member on module")
            assign(name, value)
            if (cacheResolvedMembers) {
                fields[name] = value
            }
        }

        fun hasField(name: String): Boolean =
            fields.containsKey(name) || name in deferredMemberNames()

        fun fieldNames(): Set<String> =
            linkedSetOf<String>().apply {
                addAll(fields.keys)
                addAll(deferredMemberNames())
            }

        fun hasDeferredMembers(): Boolean = memberResolver != null

        fun deferredMemberNames(): Set<String> = memberNamesProvider?.invoke().orEmpty()

        fun withDeclaredType(type: JetType.TModule): JModule {
            if (declaredType == type) return this
            return JModule(
                fields = fields,
                declaredType = type,
                memberResolver = memberResolver,
                memberNamesProvider = memberNamesProvider,
                memberAssigner = memberAssigner,
                cacheResolvedMembers = cacheResolvedMembers,
            )
        }
    }
    object JNull : JetValue() {
        override fun toString() = "null"
    }

    fun typeName(): String = when (this) {
        is JInt -> "int"
        is JFloat -> "float"
        is JString -> "string"
        is JBool -> "bool"
        is JList -> "list"
        is JObject -> "object"
        is JModule -> "module"
        JNull -> "null"
        is JFunction -> "function"
        is JBuiltin -> "function"
        is JInterval -> "interval"
        is JListener -> "listener"
        is JCommand -> "command"
    }

    fun isTruthy(): Boolean = when (this) {
        is JBool -> value
        is JInt -> value != 0
        is JFloat -> value != 0.0
        is JString -> value.isNotEmpty()
        JNull -> false
        is JList, is JObject, is JModule, is JFunction, is JBuiltin, is JInterval, is JListener, is JCommand -> true
    }

    fun toNumericDouble(): Double = when (this) {
        is JInt -> value.toDouble()
        is JFloat -> value
        else -> throw RuntimeException("Value of type '${typeName()}' is not numeric")
    }

    fun isNumeric(): Boolean = this is JInt || this is JFloat

    open override fun toString(): String = renderValue(this, 0, IdentityHashMap())

    private fun renderValue(
        value: JetValue,
        depth: Int,
        visiting: IdentityHashMap<Any, Boolean>,
    ): String = when (value) {
        is JInt -> value.value.toString()
        is JFloat -> {
            val d = value.value
            if (d.isNaN() || d.isInfinite()) d.toString()
            else java.math.BigDecimal.valueOf(d).toPlainString()
        }
        is JString -> value.value
        is JBool -> value.value.toString()
        is JList -> renderList(value, depth, visiting)
        is JObject -> renderObject(value, depth, visiting)
        is JModule -> renderModule(value, depth, visiting)
        JNull -> "null"
        is JFunction -> "<function>"
        is JBuiltin -> "<builtin>"
        is JInterval -> "<interval>"
        is JListener -> "<listener>"
        is JCommand -> "<command>"
    }

    private fun renderList(
        value: JList,
        depth: Int,
        visiting: IdentityHashMap<Any, Boolean>,
    ): String {
        if (value.elements.isEmpty()) return "[]"
        if (visiting.put(value, true) != null) return "[<cycle>]"

        return try {
            if (value.elements.all { isInlineValue(it, visiting) }) {
                "[${value.elements.joinToString(", ") { renderValue(it, depth + 1, visiting) }}]"
            } else {
                val indent = indent(depth)
                val childIndent = indent(depth + 1)
                val body = value.elements.joinToString(",\n") {
                    "$childIndent${renderValue(it, depth + 1, visiting)}"
                }
                "[\n$body\n$indent]"
            }
        } finally {
            visiting.remove(value)
        }
    }

    private fun renderObject(
        value: JObject,
        depth: Int,
        visiting: IdentityHashMap<Any, Boolean>,
    ): String {
        if (visiting.put(value, true) != null) return "{<cycle>}"

        return try {
            val entries = value.fieldNames()
                .mapNotNull { name -> value.getField(name)?.let { name to it } }
            if (entries.isEmpty()) {
                "{}"
            } else {
                val indent = indent(depth)
                val childIndent = indent(depth + 1)
                val lines = mutableListOf<String>()
                for ((key, fieldValue) in entries) {
                    lines.add("$childIndent\"$key\": ${renderValue(fieldValue, depth + 1, visiting)}")
                }
                "{\n${lines.joinToString(",\n")}\n$indent}"
            }
        } finally {
            visiting.remove(value)
        }
    }

    private fun renderModule(
        value: JModule,
        depth: Int,
        visiting: IdentityHashMap<Any, Boolean>,
    ): String {
        if (visiting.put(value, true) != null) return "{<cycle>}"

        return try {
            val entries = value.fieldNames()
                .mapNotNull { name -> value.getField(name)?.let { name to it } }
            if (entries.isEmpty()) {
                "{}"
            } else {
                val indent = indent(depth)
                val childIndent = indent(depth + 1)
                val lines = mutableListOf<String>()
                for ((key, fieldValue) in entries) {
                    lines.add("$childIndent\"$key\": ${renderValue(fieldValue, depth + 1, visiting)}")
                }
                "{\n${lines.joinToString(",\n")}\n$indent}"
            }
        } finally {
            visiting.remove(value)
        }
    }

    private fun isInlineValue(value: JetValue, visiting: IdentityHashMap<Any, Boolean>): Boolean = when (value) {
        is JList -> !visiting.containsKey(value) && value.elements.all { isInlineValue(it, visiting) }
        is JObject, is JModule -> false
        else -> true
    }

    private fun indent(depth: Int): String = "    ".repeat(depth)

}

fun jetEquals(a: JetValue, b: JetValue): Boolean = when {
    a is JetValue.JNull && b is JetValue.JNull -> true
    a is JetValue.JNull || b is JetValue.JNull -> false
    a is JetValue.JInt && b is JetValue.JInt -> a.value == b.value
    a is JetValue.JFloat && b is JetValue.JFloat -> a.value == b.value
    a is JetValue.JInt && b is JetValue.JFloat -> a.value.toDouble() == b.value
    a is JetValue.JFloat && b is JetValue.JInt -> a.value == b.value.toDouble()
    a is JetValue.JString && b is JetValue.JString -> a.value == b.value
    a is JetValue.JBool && b is JetValue.JBool -> a.value == b.value
    a is JetValue.JList && b is JetValue.JList ->
        a.elements.size == b.elements.size &&
            a.elements.indices.all { i -> jetEquals(a.elements[i], b.elements[i]) }
    a is JetValue.JBool && b.isNumeric() -> a.value == b.isTruthy()
    a.isNumeric() && b is JetValue.JBool -> a.isTruthy() == b.value
    else -> false
}
