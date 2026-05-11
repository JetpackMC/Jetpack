package dev.jetpack.event

import dev.jetpack.JetpackPlugin
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.*
import dev.jetpack.engine.runtime.ListenerHandle
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object EventBridge {

    private class ListenerEntry(
        val scriptFile: String,
        val callback: (JetValue) -> Unit,
        @Volatile var active: Boolean = true,
        @Volatile var destroyed: Boolean = false,
    )

    private val handlers = ConcurrentHashMap<String, CopyOnWriteArrayList<ListenerEntry>>()
    private class EventBinding(
        val eventClassName: String,
        val eventClass: Class<out Event>,
        val listener: Listener = object : Listener {},
        @Volatile var registered: Boolean = false,
    )

    private val bindings = ConcurrentHashMap<String, EventBinding>()
    private val methodCache = ConcurrentHashMap<Class<*>, Map<String, List<Method>>>()

    fun register(
        plugin: JetpackPlugin,
        eventClassName: String,
        scriptFile: String,
        callback: (JetValue) -> Unit,
    ): ListenerHandle {
        val eventClass = resolveEventClass(eventClassName)
        requireNotNull(eventClass) { "Unknown event type '$eventClassName'" }
        val binding = bindings.computeIfAbsent(eventClassName) { EventBinding(eventClassName, eventClass) }
        val entry = ListenerEntry(scriptFile, callback)
        handlers.getOrPut(eventClassName) { CopyOnWriteArrayList() }.add(entry)
        reconcileBinding(plugin, binding)

        return object : ListenerHandle {
            override fun activate(): Boolean {
                if (entry.destroyed || entry.active) return false
                entry.active = true
                reconcileBinding(plugin, binding)
                return true
            }
            override fun deactivate(): Boolean {
                if (entry.destroyed || !entry.active) return false
                entry.active = false
                reconcileBinding(plugin, binding)
                return true
            }
            override fun destroy(): Boolean {
                if (entry.destroyed) return false
                entry.destroyed = true
                entry.active = false
                handlers[eventClassName]?.remove(entry)
                cleanupEmptyBinding(binding)
                return true
            }
            override fun trigger(sender: JetValue): Boolean {
                if (entry.destroyed || !entry.active) return false
                entry.callback(sender)
                return true
            }
            override fun isActive(): Boolean = !entry.destroyed && entry.active
        }
    }

    fun unregisterAll(scriptFile: String) {
        for ((eventClassName, list) in handlers) {
            val removed = list.removeAll { entry ->
                if (entry.scriptFile != scriptFile) return@removeAll false
                entry.destroyed = true
                entry.active = false
                true
            }
            if (removed) {
                bindings[eventClassName]?.let(::cleanupEmptyBinding)
            }
        }
    }

    fun unregisterAllEvents() {
        handlers.values.forEach { list ->
            list.forEach { entry ->
                entry.destroyed = true
                entry.active = false
            }
        }
        handlers.clear()
        bindings.values.forEach { binding ->
            HandlerList.unregisterAll(binding.listener)
            binding.registered = false
        }
        bindings.clear()
    }

    internal fun reflectToJetValue(obj: Any): JObject =
        wrapObject(obj)

    internal fun overlayObject(base: JObject, extraFields: Map<String, JetValue>): JObject = JObject(
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

    private fun wrapObject(value: Any): JObject = JObject(
        fields = mutableMapOf(),
        isReadOnly = true,
        memberResolver = { name ->
            methodsFor(value.javaClass)[name]?.let { methods ->
                JBuiltin { args -> invokeReflectedMethod(value, name, methods, args) }
            }
        },
        memberNamesProvider = {
            linkedSetOf<String>().apply {
                addAll(methodsFor(value.javaClass).keys)
            }
        },
        cacheResolvedMembers = false,
        nativeObject = value,
    )

    private fun convertValue(value: Any?): JetValue = when (value) {
        null        -> JNull
        is String   -> JString(value)
        is Boolean  -> JBool(value)
        is Double   -> JFloat(value)
        is Float    -> JFloat(value.toDouble())
        is Long     -> JInt(requireJetInt(value, "long"))
        is Int      -> JInt(value)
        is Short    -> JInt(value.toInt())
        is Byte     -> JInt(value.toInt())
        is Number   -> throw RuntimeException("Unsupported native numeric type '${value.javaClass.simpleName}'")
        is Enum<*>  -> JString(value.name)
        is Char     -> JString(value.toString())
        is Collection<*> -> JList(value.map(::convertValue).toMutableList())
        value.javaClass.isArray -> convertArray(value)
        is Map<*, *>     -> convertMap(value)
        else             -> wrapObject(value)
    }

    private fun convertArray(value: Any): JetValue {
        val size = ReflectArray.getLength(value)
        val elements = MutableList(size) { index -> convertValue(ReflectArray.get(value, index)) }
        return JList(elements.toMutableList())
    }

    private fun convertMap(value: Map<*, *>): JetValue {
        val fields = linkedMapOf<String, JetValue>()
        for ((key, rawValue) in value) {
            val stringKey = key?.toString() ?: continue
            fields[stringKey] = convertValue(rawValue)
        }
        return JObject(fields.toMutableMap(), isReadOnly = true, nativeObject = value)
    }

    private fun methodsFor(type: Class<*>): Map<String, List<Method>> =
        methodCache.computeIfAbsent(type) { current ->
            current.methods
                .asSequence()
                .filter { method ->
                    method.declaringClass != Any::class.java &&
                        method.name != "getClass" &&
                        !method.isSynthetic &&
                        !method.isBridge
                }
                .groupBy { it.name }
        }

    private data class ReflectedInvocation(
        val method: Method,
        val args: Array<Any?>,
        val score: Int,
    )

    private fun invokeReflectedMethod(
        receiver: Any,
        name: String,
        methods: List<Method>,
        args: List<JetValue>,
    ): JetValue {
        val invocation = methods
            .mapNotNull { prepareInvocation(it, args) }
            .minByOrNull { it.score }
            ?: throw RuntimeException("Method '$name' does not accept the provided arguments")

        val result = try {
            invocation.method.invoke(receiver, *invocation.args)
        } catch (error: InvocationTargetException) {
            val cause = error.targetException ?: error
            throw RuntimeException(cause.message ?: "Method '$name' failed")
        } catch (error: Exception) {
            throw RuntimeException(error.message ?: "Method '$name' failed")
        }

        return convertValue(result)
    }

    private fun prepareInvocation(method: Method, args: List<JetValue>): ReflectedInvocation? {
        val parameterTypes = method.parameterTypes
        if (!method.isVarArgs) {
            if (args.size != parameterTypes.size) return null
            val convertedArgs = arrayOfNulls<Any?>(parameterTypes.size)
            var totalScore = 0
            for (i in parameterTypes.indices) {
                val converted = convertArgument(args[i], parameterTypes[i]) ?: return null
                convertedArgs[i] = converted.first
                totalScore += converted.second
            }
            return ReflectedInvocation(method, convertedArgs, totalScore)
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

        return ReflectedInvocation(method, convertedArgs, totalScore + 1)
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
            if (native != null && targetType.isInstance(native)) {
                return native to 0
            }
        }

        convertNumeric(value, targetType)?.let { return it }

        return when {
            targetType == String::class.java || targetType == CharSequence::class.java -> {
                (value as? JString)?.value?.let { it to 1 }
            }
            targetType == Boolean::class.java || targetType == java.lang.Boolean.TYPE -> {
                (value as? JBool)?.value?.let { it to 1 }
            }
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
            value is JObject && value.nativeObject != null && targetType.isAssignableFrom(value.nativeObject.javaClass) ->
                value.nativeObject to 5
            else -> null
        }
    }

    private fun convertNumeric(value: JetValue, targetType: Class<*>): Pair<Any?, Int>? {
        if (value is JInt) {
            val v = value.value
            return when (targetType) {
                Int::class.java, java.lang.Integer.TYPE -> v to 0
                Long::class.java, java.lang.Long.TYPE -> v.toLong() to 1
                Short::class.java, java.lang.Short.TYPE ->
                    if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) null else v.toShort() to 2
                Byte::class.java, java.lang.Byte.TYPE ->
                    if (v < Byte.MIN_VALUE || v > Byte.MAX_VALUE) null else v.toByte() to 2
                Float::class.java, java.lang.Float.TYPE -> v.toFloat() to 3
                Double::class.java, java.lang.Double.TYPE -> v.toDouble() to 4
                Number::class.java -> v to 5
                else -> null
            }
        }

        val numeric = when (value) {
            is JFloat -> value.value
            else -> return null
        }

        return when (targetType) {
            Byte::class.java, java.lang.Byte.TYPE -> {
                if (numeric < Byte.MIN_VALUE || numeric > Byte.MAX_VALUE) null else numeric.toInt().toByte() to 2
            }
            Short::class.java, java.lang.Short.TYPE -> {
                if (numeric < Short.MIN_VALUE || numeric > Short.MAX_VALUE) null else numeric.toInt().toShort() to 2
            }
            Int::class.java, java.lang.Integer.TYPE -> {
                if (numeric < Int.MIN_VALUE || numeric > Int.MAX_VALUE) null else numeric.toInt() to 2
            }
            Long::class.java, java.lang.Long.TYPE -> {
                if (numeric < Int.MIN_VALUE || numeric > Int.MAX_VALUE) null else numeric.toInt().toLong() to 3
            }
            Float::class.java, java.lang.Float.TYPE -> numeric.toFloat() to 0
            Double::class.java, java.lang.Double.TYPE -> numeric to 1
            Number::class.java -> numeric to 5
            else -> null
        }
    }

    private fun requireJetInt(value: Long, typeName: String): Int {
        if (value < Int.MIN_VALUE.toLong() || value > Int.MAX_VALUE.toLong()) {
            throw RuntimeException("Native $typeName value '$value' is out of Jet int range")
        }
        return value.toInt()
    }

    private fun convertEnum(value: JetValue, targetType: Class<*>): Pair<Any?, Int>? {
        val raw = (value as? JString)?.value ?: return null
        val constants = targetType.enumConstants?.filterIsInstance<Enum<*>>() ?: return null
        val exact = constants.firstOrNull { it.name == raw }
        if (exact != null) return exact to 3
        val ignoreCase = constants.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        return ignoreCase?.let { it to 4 }
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

    private fun unwrapList(value: JList): List<Any?> =
        value.elements.map(::unwrapValue)

    private fun unwrapObject(value: JObject): Any? =
        value.nativeObject ?: value.fields.mapValues { (_, fieldValue) -> unwrapValue(fieldValue) }

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

    private fun resolveEventClass(name: String): Class<out Event>? =
        JetpackEvent.resolve(name)?.eventClass

    private fun reconcileBinding(plugin: JetpackPlugin, binding: EventBinding) {
        synchronized(binding) {
            val activeEntries = handlers[binding.eventClassName].orEmpty().filter { it.active && !it.destroyed }
            when {
                activeEntries.isEmpty() && binding.registered -> {
                    HandlerList.unregisterAll(binding.listener)
                    binding.registered = false
                }
                activeEntries.isNotEmpty() && !binding.registered -> {
                    plugin.server.pluginManager.registerEvent(
                        binding.eventClass,
                        binding.listener,
                        EventPriority.NORMAL,
                        eventCallback@{ _, event ->
                            if (!binding.eventClass.isInstance(event)) return@eventCallback
                            val callbacks = handlers[binding.eventClassName].orEmpty()
                                .filter { it.active && !it.destroyed }
                            if (callbacks.isEmpty()) return@eventCallback
                            val reflected = reflectToJetValue(event)
                            val jetValue = if (event is Cancellable) {
                                overlayObject(reflected, mapOf("cancel" to JBuiltin { _ ->
                                    event.isCancelled = true
                                    JNull
                                }))
                            } else reflected
                            callbacks.forEach { it.callback(jetValue) }
                        },
                        plugin,
                    )
                    binding.registered = true
                }
            }
        }
    }

    private fun cleanupEmptyBinding(binding: EventBinding) {
        synchronized(binding) {
            val list = handlers[binding.eventClassName]
            if (list != null && list.isNotEmpty()) {
                if (list.none { it.active && !it.destroyed } && binding.registered) {
                    HandlerList.unregisterAll(binding.listener)
                    binding.registered = false
                }
                return
            }
            handlers.remove(binding.eventClassName)
            if (binding.registered) {
                HandlerList.unregisterAll(binding.listener)
                binding.registered = false
            }
            bindings.remove(binding.eventClassName, binding)
        }
    }
}
