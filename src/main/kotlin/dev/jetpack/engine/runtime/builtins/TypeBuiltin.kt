package dev.jetpack.engine.runtime.builtins

import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.*
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature

class TypeBuiltin : Builtin {
    override fun globalType(name: String): JetType? = when (name) {
        "typeof" -> callable(JetType.TString, signature(JetType.TUnknown))
        else     -> null
    }

    override fun resolveGlobal(name: String): JetFn? = when (name) {
        "typeof" -> { args -> JString(args[0].typeName()) }
        else -> null
    }

    override fun resolveMethod(target: JetValue, method: String): JetFn? = null

    override fun globalNames(): Set<String> = setOf("typeof")
}
