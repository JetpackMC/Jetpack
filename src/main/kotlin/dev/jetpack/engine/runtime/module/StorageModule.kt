package dev.jetpack.engine.runtime.module

import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.JBool
import dev.jetpack.engine.runtime.JetValue.JBuiltin
import dev.jetpack.engine.runtime.JetValue.JList
import dev.jetpack.engine.runtime.JetValue.JModule
import dev.jetpack.engine.runtime.JetValue.JString

class StorageModule(private val storageService: StorageService) {

    fun spec(): ModuleSpec = ModuleSpec(
        name = "storage",
        value = asValue(),
        fields = mapOf(
            "create" to callable(JetType.TBool, signature(JetType.TString)),
            "destroy" to callable(JetType.TBool, signature(JetType.TString)),
            "exists" to callable(JetType.TBool, signature(JetType.TString)),
            "has" to callable(JetType.TBool, signature(JetType.TString, JetType.TString)),
            "keys" to callable(JetType.TList(JetType.TString), signature(JetType.TString)),
            "get" to callable(JetType.TUnknown, signature(JetType.TString), signature(JetType.TString, JetType.TString)),
            "set" to callable(JetType.TBool, signature(JetType.TString, JetType.TString, JetType.TUnknown)),
            "remove" to callable(JetType.TBool, signature(JetType.TString, JetType.TString)),
            "clear" to callable(JetType.TBool, signature(JetType.TString)),
        ),
    )

    fun asValue(): JetValue.JModule = JModule(
        mutableMapOf(
            "create" to builtin(::create),
            "destroy" to builtin(::destroy),
            "exists" to builtin(::exists),
            "has" to builtin(::has),
            "keys" to builtin(::keys),
            "get" to builtin(::get),
            "set" to builtin(::set),
            "remove" to builtin(::remove),
            "clear" to builtin(::clear),
        ),
    )

    private fun builtin(handler: (List<JetValue>) -> JetValue): JetValue =
        JBuiltin(handler)

    private fun create(args: List<JetValue>): JetValue {
        return JBool(storageService.create(requireStringArg(args[0], "storage.create")))
    }

    private fun destroy(args: List<JetValue>): JetValue {
        return JBool(storageService.destroy(requireStringArg(args[0], "storage.destroy")))
    }

    private fun exists(args: List<JetValue>): JetValue {
        return JBool(storageService.exists(requireStringArg(args[0], "storage.exists")))
    }

    private fun has(args: List<JetValue>): JetValue {
        return JBool(
            storageService.has(
                requireStringArg(args[0], "storage.has"),
                requireStringArg(args[1], "storage.has"),
            )
        )
    }

    private fun keys(args: List<JetValue>): JetValue {
        return JList(
            storageService.keys(requireStringArg(args[0], "storage.keys"))
                .map(::JString)
                .toMutableList(),
        )
    }

    private fun get(args: List<JetValue>): JetValue {
        val store = requireStringArg(args[0], "storage.get")
        return if (args.size == 1) {
            storageService.getAll(store)
        } else {
            storageService.get(store, requireStringArg(args[1], "storage.get"))
        }
    }

    private fun set(args: List<JetValue>): JetValue {
        return JBool(
            storageService.set(
                requireStringArg(args[0], "storage.set"),
                requireStringArg(args[1], "storage.set"),
                args[2],
            )
        )
    }

    private fun remove(args: List<JetValue>): JetValue {
        return JBool(
            storageService.remove(
                requireStringArg(args[0], "storage.remove"),
                requireStringArg(args[1], "storage.remove"),
            )
        )
    }

    private fun clear(args: List<JetValue>): JetValue {
        return JBool(storageService.clear(requireStringArg(args[0], "storage.clear")))
    }

    private fun requireStringArg(value: JetValue, functionName: String): String {
        val stringValue = value as? JString
            ?: throw RuntimeException("Function '$functionName' expects string arguments")
        return stringValue.value
    }
}
