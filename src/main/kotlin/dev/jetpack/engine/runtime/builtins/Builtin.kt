package dev.jetpack.engine.runtime.builtins

import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.runtime.JetValue

typealias JetFn = suspend (List<JetValue>) -> JetValue

interface Builtin {
    fun resolveGlobal(name: String): JetFn?
    fun resolveMethod(target: JetValue, method: String): JetFn?

    fun globalType(name: String): JetType? = null
    fun methodType(targetType: JetType, method: String): JetType? = null

    fun globalNames(): Set<String> = emptySet()
}
