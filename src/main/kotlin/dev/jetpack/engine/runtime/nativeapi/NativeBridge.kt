package dev.jetpack.engine.runtime.nativeapi

import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.JBool
import dev.jetpack.engine.runtime.JetValue.JBuiltin
import dev.jetpack.engine.runtime.JetValue.JFloat
import dev.jetpack.engine.runtime.JetValue.JInt
import dev.jetpack.engine.runtime.JetValue.JList
import dev.jetpack.engine.runtime.JetValue.JModule
import dev.jetpack.engine.runtime.JetValue.JNull
import dev.jetpack.engine.runtime.JetValue.JObject
import dev.jetpack.engine.runtime.JetValue.JString
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

class NativeClassHandle(val type: Class<*>)

object NativeBridge {
    private val methodCache = ConcurrentHashMap<Class<*>, Map<String, List<Method>>>()
    private val staticMethodCache = ConcurrentHashMap<Class<*>, Map<String, List<Method>>>()
    private val fieldCache = ConcurrentHashMap<Class<*>, Map<String, Field>>()
    private val staticFieldCache = ConcurrentHashMap<Class<*>, Map<String, Field>>()

    fun packageModule(packageName: String): JModule = JModule(
        fields = mutableMapOf(),
        memberResolver = { name -> resolvePackageMember(packageName, name) },
        memberNamesProvider = { emptySet() },
        cacheResolvedMembers = true,
    )

    fun wrap(value: Any?, enumAsString: Boolean = false): JetValue = when (value) {
        null -> JNull
        is JetValue -> value
        is String -> JString(value)
        is Boolean -> JBool(value)
        is Double -> JFloat(value)
        is Float -> JFloat(value.toDouble())
        is Long -> JInt(requireJetInt(value, "long"))
        is Int -> JInt(value)
        is Short -> JInt(value.toInt())
        is Byte -> JInt(value.toInt())
        is Number -> throw RuntimeException("Unsupported native numeric type '${value.javaClass.simpleName}'")
        is Enum<*> -> if (enumAsString) JString(value.name) else wrapObject(value, enumAsString)
        is Char -> JString(value.toString())
        is Collection<*> -> JList(value.map { wrap(it, enumAsString) }.toMutableList())
        is Map<*, *> -> wrapMap(value, enumAsString)
        else -> if (value.javaClass.isArray) wrapArray(value, enumAsString) else wrapObject(value, enumAsString)
    }

    fun overlayObject(base: JObject, extraFields: Map<String, JetValue>): JObject = JObject(
        fields = extraFields.toMutableMap(),
        isReadOnly = true,
        memberResolver = { name -> base.getField(name) },
        memberNamesProvider = {
            linkedSetOf<String>().apply {
                addAll(base.fieldNames())
                addAll(extraFields.keys)
            }
        },
        cacheResolvedMembers = false,
        nativeObject = base.nativeObject,
    )

    fun call(callee: JetValue, args: List<JetValue>): JetValue? {
        val handle = (callee as? JObject)?.nativeObject as? NativeClassHandle ?: return null
        return invokeConstructor(handle.type, args)
    }

    fun callMember(target: JetValue, name: String, args: List<JetValue>): JetValue? {
        val native = (target as? JObject)?.nativeObject ?: return null
        if (native is NativeClassHandle) {
            val methods = staticMethodsFor(native.type)[name] ?: return null
            return invokeMethod(null, name, methods, args)
        }
        val methods = methodsFor(native.javaClass)[name] ?: return null
        return invokeMethod(native, name, methods, args)
    }

    private fun resolvePackageMember(packageName: String, name: String): JetValue {
        val qualifiedName = "$packageName.$name"
        return loadClass(qualifiedName)?.let(::wrapClass) ?: packageModule(qualifiedName)
    }

    private fun wrapClass(type: Class<*>): JObject = JObject(
        fields = mutableMapOf(),
        isReadOnly = true,
        memberResolver = { name -> resolveClassMember(type, name) },
        memberNamesProvider = {
            linkedSetOf<String>().apply {
                addAll(enumConstantNames(type))
                addAll(staticFieldsFor(type).keys)
                addAll(staticMethodsFor(type).keys)
            }
        },
        cacheResolvedMembers = false,
        nativeObject = NativeClassHandle(type),
    )

    private fun wrapObject(value: Any, enumAsString: Boolean): JObject = JObject(
        fields = mutableMapOf(),
        isReadOnly = true,
        memberResolver = { name -> resolveObjectMember(value, name, enumAsString) },
        memberNamesProvider = {
            linkedSetOf<String>().apply {
                addAll(fieldsFor(value.javaClass).keys)
                addAll(methodsFor(value.javaClass).keys)
            }
        },
        memberAssigner = { name, newValue -> assignObjectMember(value, name, newValue) },
        cacheResolvedMembers = false,
        nativeObject = value,
    )

    private fun resolveClassMember(type: Class<*>, name: String): JetValue? {
        enumConstant(type, name)?.let { return wrap(it) }
        staticFieldsFor(type)[name]?.let { return readField(null, it) }
        staticMethodsFor(type)[name]?.let { methods ->
            return JBuiltin { args -> invokeMethod(null, name, methods, args) }
        }
        return null
    }

    private fun resolveObjectMember(receiver: Any, name: String, enumAsString: Boolean): JetValue? {
        propertyGetter(receiver.javaClass, name)?.let {
            return invokeMethod(receiver, it.name, listOf(it), emptyList(), enumAsString)
        }
        fieldsFor(receiver.javaClass)[name]?.let { return readField(receiver, it) }
        methodsFor(receiver.javaClass)[name]?.let { methods ->
            return JBuiltin { args -> invokeMethod(receiver, name, methods, args, enumAsString) }
        }
        return null
    }

    private fun assignObjectMember(receiver: Any, name: String, value: JetValue) {
        propertySetter(receiver.javaClass, name, value)?.let { method ->
            invokeMethod(receiver, method.name, listOf(method), listOf(value))
            return
        }
        val field = fieldsFor(receiver.javaClass)[name]
            ?: throw RuntimeException("Native property '$name' is not writable")
        if (Modifier.isFinal(field.modifiers)) {
            throw RuntimeException("Native property '$name' is final")
        }
        val converted = convertArgument(value, field.type)?.first
            ?: throw RuntimeException("Native property '$name' does not accept value of type '${value.typeName()}'")
        try {
            field.set(receiver, converted)
        } catch (error: Exception) {
            throw RuntimeException(error.message ?: "Native property '$name' assignment failed")
        }
    }

    private fun propertyGetter(type: Class<*>, name: String): Method? {
        val suffix = propertySuffix(name)
        methodsFor(type)["is$suffix"]
            ?.firstOrNull { it.parameterCount == 0 && isBooleanReturn(it) }
            ?.let { return it }
        return methodsFor(type)["get$suffix"]?.firstOrNull { it.parameterCount == 0 }
    }

    private fun propertySetter(type: Class<*>, name: String, value: JetValue): Method? {
        val setterName = "set${propertySuffix(name)}"
        val candidates = methodsFor(type)[setterName].orEmpty()
            .filter { it.parameterCount == 1 && !it.isVarArgs }
        return selectExecutable(candidates, listOf(value))?.method
    }

    private fun propertySuffix(name: String): String =
        name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun isBooleanReturn(method: Method): Boolean =
        method.returnType == Boolean::class.java || method.returnType == java.lang.Boolean.TYPE

    private fun invokeConstructor(type: Class<*>, args: List<JetValue>): JetValue {
        val constructor = selectConstructor(type.constructors.toList(), args)
            ?: throw RuntimeException("Constructor '${type.name}' does not accept the provided arguments")
        val result = try {
            constructor.constructor.newInstance(*constructor.args)
        } catch (error: InvocationTargetException) {
            val cause = error.targetException ?: error
            throw RuntimeException(cause.message ?: "Constructor '${type.name}' failed")
        } catch (error: Exception) {
            throw RuntimeException(error.message ?: "Constructor '${type.name}' failed")
        }
        return wrap(result)
    }

    private fun invokeMethod(
        receiver: Any?,
        name: String,
        methods: List<Method>,
        args: List<JetValue>,
        enumAsString: Boolean = false,
    ): JetValue {
        val invocation = selectExecutable(methods, args)
            ?: throw RuntimeException("Method '$name' does not accept the provided arguments")
        val result = try {
            invocation.method.invoke(receiver, *invocation.args)
        } catch (error: InvocationTargetException) {
            val cause = error.targetException ?: error
            throw RuntimeException(cause.message ?: "Method '$name' failed")
        } catch (error: Exception) {
            throw RuntimeException(error.message ?: "Method '$name' failed")
        }
        return wrap(result, enumAsString)
    }

    private fun readField(receiver: Any?, field: Field): JetValue =
        try {
            wrap(field.get(receiver))
        } catch (error: Exception) {
            throw RuntimeException(error.message ?: "Native field '${field.name}' access failed")
        }

    private data class MethodInvocation(
        val method: Method,
        val args: Array<Any?>,
        val score: Int,
    )

    private data class ConstructorInvocation(
        val constructor: Constructor<*>,
        val args: Array<Any?>,
        val score: Int,
    )

    private fun selectExecutable(methods: List<Method>, args: List<JetValue>): MethodInvocation? =
        selectBest(methods.mapNotNull { prepareMethodInvocation(it, args) }, "method") { it.score }

    private fun selectConstructor(constructors: List<Constructor<*>>, args: List<JetValue>): ConstructorInvocation? =
        selectBest(constructors.mapNotNull { prepareConstructorInvocation(it, args) }, "constructor") { it.score }

    private fun <T> selectBest(candidates: List<T>, label: String, scoreOf: (T) -> Int): T? {
        if (candidates.isEmpty()) return null
        val bestScore = candidates.minOf(scoreOf)
        val best = candidates.filter { scoreOf(it) == bestScore }
        if (best.size > 1) {
            throw RuntimeException("Ambiguous native $label invocation")
        }
        return best.first()
    }

    private fun prepareMethodInvocation(method: Method, args: List<JetValue>): MethodInvocation? {
        val converted = prepareArguments(method.parameterTypes, method.isVarArgs, args) ?: return null
        return MethodInvocation(method, converted.first, converted.second)
    }

    private fun prepareConstructorInvocation(
        constructor: Constructor<*>,
        args: List<JetValue>,
    ): ConstructorInvocation? {
        val converted = prepareArguments(constructor.parameterTypes, constructor.isVarArgs, args) ?: return null
        return ConstructorInvocation(constructor, converted.first, converted.second)
    }

    private fun prepareArguments(
        parameterTypes: Array<Class<*>>,
        isVarArgs: Boolean,
        args: List<JetValue>,
    ): Pair<Array<Any?>, Int>? {
        if (!isVarArgs) {
            if (args.size != parameterTypes.size) return null
            val convertedArgs = arrayOfNulls<Any?>(parameterTypes.size)
            var totalScore = 0
            for (i in parameterTypes.indices) {
                val converted = convertArgument(args[i], parameterTypes[i]) ?: return null
                convertedArgs[i] = converted.first
                totalScore += converted.second
            }
            return convertedArgs to totalScore
        }

        val fixedCount = parameterTypes.size - 1
        if (args.size < fixedCount) return null
        val convertedArgs = arrayOfNulls<Any?>(parameterTypes.size)
        var totalScore = 0
        for (i in 0 until fixedCount) {
            val converted = convertArgument(args[i], parameterTypes[i]) ?: return null
            convertedArgs[i] = converted.first
            totalScore += converted.second
        }

        val componentType = parameterTypes.last().componentType ?: return null
        val varargArray = ReflectArray.newInstance(componentType, args.size - fixedCount)
        for (i in fixedCount until args.size) {
            val converted = convertArgument(args[i], componentType) ?: return null
            ReflectArray.set(varargArray, i - fixedCount, converted.first)
            totalScore += converted.second
        }
        convertedArgs[fixedCount] = varargArray
        return convertedArgs to totalScore + 1
    }

    private fun convertArgument(value: JetValue, targetType: Class<*>): Pair<Any?, Int>? {
        if (targetType == JetValue::class.java || targetType.isAssignableFrom(value.javaClass)) {
            return value to 0
        }
        if (value is JNull) {
            return if (targetType.isPrimitive) null else null to 10
        }
        if (value is JObject) {
            val native = value.nativeObject
            if (native is NativeClassHandle && targetType == Class::class.java) return native.type to 1
            if (native != null && targetType.isInstance(native)) return native to 0
        }
        convertNumeric(value, targetType)?.let { return it }
        return when {
            targetType == String::class.java || targetType == CharSequence::class.java ->
                (value as? JString)?.value?.let { it to 1 }
            targetType == Boolean::class.java || targetType == java.lang.Boolean.TYPE ->
                (value as? JBool)?.value?.let { it to 1 }
            targetType == Char::class.java || targetType == java.lang.Character.TYPE -> {
                val string = (value as? JString)?.value ?: return null
                if (string.length == 1) string[0] to 1 else null
            }
            targetType.isEnum -> convertEnum(value, targetType)
            targetType.isArray && value is JList -> convertArrayArgument(value, targetType.componentType)
            java.util.Collection::class.java.isAssignableFrom(targetType) && value is JList ->
                unwrapList(value) to 20
            java.util.Map::class.java.isAssignableFrom(targetType) && value is JObject ->
                unwrapObject(value) to 20
            java.util.Map::class.java.isAssignableFrom(targetType) && value is JModule ->
                unwrapModule(value) to 20
            targetType == Any::class.java || targetType == Object::class.java ->
                unwrapValue(value) to 50
            else -> null
        }
    }

    private fun convertNumeric(value: JetValue, targetType: Class<*>): Pair<Any?, Int>? {
        if (value is JInt) {
            val number = value.value
            return when (targetType) {
                Int::class.java, java.lang.Integer.TYPE -> number to 0
                Long::class.java, java.lang.Long.TYPE -> number.toLong() to 1
                Short::class.java, java.lang.Short.TYPE ->
                    if (number < Short.MIN_VALUE || number > Short.MAX_VALUE) null else number.toShort() to 2
                Byte::class.java, java.lang.Byte.TYPE ->
                    if (number < Byte.MIN_VALUE || number > Byte.MAX_VALUE) null else number.toByte() to 2
                Float::class.java, java.lang.Float.TYPE -> number.toFloat() to 3
                Double::class.java, java.lang.Double.TYPE -> number.toDouble() to 4
                Number::class.java -> number to 5
                else -> null
            }
        }

        val number = (value as? JFloat)?.value ?: return null
        return when (targetType) {
            Byte::class.java, java.lang.Byte.TYPE ->
                if (number < Byte.MIN_VALUE || number > Byte.MAX_VALUE) null else number.toInt().toByte() to 2
            Short::class.java, java.lang.Short.TYPE ->
                if (number < Short.MIN_VALUE || number > Short.MAX_VALUE) null else number.toInt().toShort() to 2
            Int::class.java, java.lang.Integer.TYPE ->
                if (number < Int.MIN_VALUE || number > Int.MAX_VALUE) null else number.toInt() to 2
            Long::class.java, java.lang.Long.TYPE ->
                if (number < Long.MIN_VALUE || number > Long.MAX_VALUE) null else number.toLong() to 3
            Float::class.java, java.lang.Float.TYPE -> number.toFloat() to 0
            Double::class.java, java.lang.Double.TYPE -> number to 1
            Number::class.java -> number to 5
            else -> null
        }
    }

    private fun convertEnum(value: JetValue, targetType: Class<*>): Pair<Any?, Int>? {
        val native = (value as? JObject)?.nativeObject
        if (native != null && targetType.isInstance(native)) return native to 0
        val raw = (value as? JString)?.value ?: return null
        val constants = targetType.enumConstants?.filterIsInstance<Enum<*>>() ?: return null
        constants.firstOrNull { it.name == raw }?.let { return it to 3 }
        return constants.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.let { it to 4 }
    }

    private fun convertArrayArgument(value: JList, componentType: Class<*>): Pair<Any?, Int>? {
        val array = ReflectArray.newInstance(componentType, value.elements.size)
        var totalScore = 0
        value.elements.forEachIndexed { index, element ->
            val converted = convertArgument(element, componentType) ?: return null
            ReflectArray.set(array, index, converted.first)
            totalScore += converted.second
        }
        return array to totalScore + 5
    }

    private fun wrapArray(value: Any, enumAsString: Boolean): JList {
        val size = ReflectArray.getLength(value)
        return JList(MutableList(size) { index -> wrap(ReflectArray.get(value, index), enumAsString) })
    }

    private fun wrapMap(value: Map<*, *>, enumAsString: Boolean): JObject {
        val fields = linkedMapOf<String, JetValue>()
        for ((key, rawValue) in value) {
            fields[key?.toString() ?: continue] = wrap(rawValue, enumAsString)
        }
        return JObject(fields.toMutableMap(), isReadOnly = true, nativeObject = value)
    }

    private fun unwrapList(value: JList): List<Any?> =
        value.elements.map(::unwrapValue)

    private fun unwrapObject(value: JObject): Any? =
        value.nativeObject?.takeUnless { it is NativeClassHandle }
            ?: value.fields.mapValues { (_, fieldValue) -> unwrapValue(fieldValue) }

    private fun unwrapModule(value: JModule): Map<String, Any?> =
        value.fieldNames().associateWith { name -> unwrapValue(value.getField(name) ?: JNull) }

    private fun unwrapValue(value: JetValue): Any? = when (value) {
        JNull -> null
        is JBool -> value.value
        is JInt -> value.value
        is JFloat -> value.value
        is JString -> value.value
        is JList -> unwrapList(value)
        is JObject -> unwrapObject(value)
        is JModule -> unwrapModule(value)
        else -> value
    }

    private fun methodsFor(type: Class<*>): Map<String, List<Method>> =
        methodCache.computeIfAbsent(type) { current ->
            current.methods
                .asSequence()
                .filter { isVisibleMethod(it) && !Modifier.isStatic(it.modifiers) }
                .groupBy { it.name }
        }

    private fun staticMethodsFor(type: Class<*>): Map<String, List<Method>> =
        staticMethodCache.computeIfAbsent(type) { current ->
            current.methods
                .asSequence()
                .filter { isVisibleMethod(it) && Modifier.isStatic(it.modifiers) }
                .groupBy { it.name }
        }

    private fun fieldsFor(type: Class<*>): Map<String, Field> =
        fieldCache.computeIfAbsent(type) { current ->
            current.fields
                .asSequence()
                .filter { !Modifier.isStatic(it.modifiers) }
                .associateBy { it.name }
        }

    private fun staticFieldsFor(type: Class<*>): Map<String, Field> =
        staticFieldCache.computeIfAbsent(type) { current ->
            current.fields
                .asSequence()
                .filter { Modifier.isStatic(it.modifiers) }
                .associateBy { it.name }
        }

    private fun isVisibleMethod(method: Method): Boolean =
        method.declaringClass != Any::class.java &&
            method.name != "getClass" &&
            !method.isSynthetic &&
            !method.isBridge

    private fun enumConstantNames(type: Class<*>): Set<String> =
        type.enumConstants?.filterIsInstance<Enum<*>>()?.mapTo(linkedSetOf()) { it.name }.orEmpty()

    private fun enumConstant(type: Class<*>, name: String): Enum<*>? =
        type.enumConstants?.filterIsInstance<Enum<*>>()?.firstOrNull { it.name == name }

    private fun loadClass(name: String): Class<*>? {
        if (!name.startsWith("org.bukkit.")) return null
        val loaders = listOf(
            Thread.currentThread().contextClassLoader,
            NativeBridge::class.java.classLoader,
        ).filterNotNull()
        for (loader in loaders) {
            runCatching { return Class.forName(name, false, loader) }
        }
        return null
    }

    private fun requireJetInt(value: Long, typeName: String): Int {
        if (value < Int.MIN_VALUE.toLong() || value > Int.MAX_VALUE.toLong()) {
            throw RuntimeException("Native $typeName value '$value' is out of Jet int range")
        }
        return value.toInt()
    }
}
