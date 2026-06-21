package dev.jetpack.engine.runtime

import dev.jetpack.engine.parser.ast.JetType

class ScopeException(message: String) : Exception(message)

class Scope(val parent: Scope? = null) {

    private var vars: HashMap<String, JetValue>? = null
    private var consts: HashSet<String>? = null
    private var readOnly: HashSet<String>? = null
    private var declaredTypes: HashMap<String, JetType>? = null

    private fun vars(): HashMap<String, JetValue> =
        vars ?: HashMap<String, JetValue>().also { vars = it }

    private fun consts(): HashSet<String> =
        consts ?: HashSet<String>().also { consts = it }

    private fun readOnly(): HashSet<String> =
        readOnly ?: HashSet<String>().also { readOnly = it }

    private fun declaredTypes(): HashMap<String, JetType> =
        declaredTypes ?: HashMap<String, JetType>().also { declaredTypes = it }

    fun define(name: String, value: JetValue, isConst: Boolean = false, declaredType: JetType? = null) {
        if (vars?.containsKey(name) == true) throw ScopeException("Variable '$name' is already declared")
        vars()[name] = coerceValueToType(value, declaredType)
        declaredType?.takeUnless { it == JetType.TUnknown }?.let { declaredTypes()[name] = it }
        if (isConst) consts().add(name)
    }

    fun defineCoerced(name: String, value: JetValue, isConst: Boolean = false, declaredType: JetType? = null) {
        if (vars?.containsKey(name) == true) throw ScopeException("Variable '$name' is already declared")
        vars()[name] = value
        declaredType?.takeUnless { it == JetType.TUnknown }?.let { declaredTypes()[name] = it }
        if (isConst) consts().add(name)
    }

    fun defineReadOnly(name: String, value: JetValue, declaredType: JetType? = null) {
        if (vars?.containsKey(name) == true) throw ScopeException("Symbol '$name' is already declared")
        vars()[name] = coerceValueToType(value, declaredType)
        declaredType?.takeUnless { it == JetType.TUnknown }?.let { declaredTypes()[name] = it }
        readOnly().add(name)
    }

    fun get(name: String): JetValue {
        return getOrNull(name) ?: throw ScopeException("Undefined identifier '$name'")
    }

    fun getOrNull(name: String): JetValue? =
        vars?.get(name) ?: parent?.getOrNull(name)

    fun set(name: String, value: JetValue): JetValue {
        when {
            vars?.containsKey(name) == true -> {
                if (consts?.contains(name) == true) throw ScopeException("Constant '$name' cannot be modified")
                if (readOnly?.contains(name) == true) throw ScopeException("Cannot assign to read-only symbol '$name'")
                val stored = coerceValueToType(value, declaredTypes?.get(name))
                vars()[name] = stored
                return stored
            }
            parent != null -> return parent.set(name, value)
            else -> throw ScopeException("Undefined identifier '$name'")
        }
    }

    fun isDefined(name: String): Boolean =
        vars?.containsKey(name) == true || parent?.isDefined(name) == true

    fun child(): Scope = Scope(this)
}
