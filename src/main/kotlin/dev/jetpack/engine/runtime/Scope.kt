package dev.jetpack.engine.runtime

import dev.jetpack.engine.parser.ast.JetType

class ScopeException(message: String) : Exception(message)

class Scope(val parent: Scope? = null) {

    private val vars: MutableMap<String, JetValue> = mutableMapOf()
    private val consts: MutableSet<String> = mutableSetOf()
    private val readOnly: MutableSet<String> = mutableSetOf()
    private val declaredTypes: MutableMap<String, JetType> = mutableMapOf()

    fun define(name: String, value: JetValue, isConst: Boolean = false, declaredType: JetType? = null) {
        if (vars.containsKey(name)) throw ScopeException("Variable '$name' is already declared")
        vars[name] = coerceValueToType(value, declaredType)
        declaredType?.takeUnless { it == JetType.TUnknown }?.let { declaredTypes[name] = it }
        if (isConst) consts.add(name)
    }

    fun defineCoerced(name: String, value: JetValue, isConst: Boolean = false, declaredType: JetType? = null) {
        if (vars.containsKey(name)) throw ScopeException("Variable '$name' is already declared")
        vars[name] = value
        declaredType?.takeUnless { it == JetType.TUnknown }?.let { declaredTypes[name] = it }
        if (isConst) consts.add(name)
    }

    fun defineReadOnly(name: String, value: JetValue, declaredType: JetType? = null) {
        if (vars.containsKey(name)) throw ScopeException("Symbol '$name' is already declared")
        vars[name] = coerceValueToType(value, declaredType)
        declaredType?.takeUnless { it == JetType.TUnknown }?.let { declaredTypes[name] = it }
        readOnly.add(name)
    }

    fun get(name: String): JetValue {
        return vars[name] ?: parent?.get(name)
            ?: throw ScopeException("Undefined identifier '$name'")
    }

    fun set(name: String, value: JetValue): JetValue {
        when {
            vars.containsKey(name) -> {
                if (name in consts) throw ScopeException("Constant '$name' cannot be modified")
                if (name in readOnly) throw ScopeException("Cannot assign to read-only symbol '$name'")
                val stored = coerceValueToType(value, declaredTypes[name])
                vars[name] = stored
                return stored
            }
            parent != null -> return parent.set(name, value)
            else -> throw ScopeException("Undefined identifier '$name'")
        }
    }

    fun isDefined(name: String): Boolean =
        vars.containsKey(name) || parent?.isDefined(name) == true

    fun child(): Scope = Scope(this)
}
