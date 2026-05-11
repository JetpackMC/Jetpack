package dev.jetpack.engine.parser.ast

interface BuiltinTypeProvider {
    fun globalType(name: String): JetType?
    fun methodType(targetType: JetType, method: String): JetType?
    fun moduleFieldType(moduleName: String, field: String): JetType? = null
    fun isKnownGlobal(name: String): Boolean = globalType(name) != null
}
