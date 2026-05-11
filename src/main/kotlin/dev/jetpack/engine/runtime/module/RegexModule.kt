package dev.jetpack.engine.runtime.module

import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.*
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class RegexModule {

    fun spec(): ModuleSpec = ModuleSpec(
        name = "regex",
        value = asValue(),
        fields = mapOf(
            "test" to callable(JetType.TBool, signature(JetType.TString, JetType.TString)),
            "find" to callable(JetType.TNullable(JetType.TString), signature(JetType.TString, JetType.TString)),
            "findAll" to callable(JetType.TList(JetType.TString), signature(JetType.TString, JetType.TString)),
            "groups" to callable(JetType.TList(JetType.TUnknown), signature(JetType.TString, JetType.TString)),
            "replace" to callable(JetType.TString, signature(JetType.TString, JetType.TString, JetType.TString)),
            "replaceAll" to callable(JetType.TString, signature(JetType.TString, JetType.TString, JetType.TString)),
            "split" to callable(JetType.TList(JetType.TString), signature(JetType.TString, JetType.TString)),
            "escape" to callable(JetType.TString, signature(JetType.TString)),
        ),
    )

    fun asValue(): JetValue.JModule = JModule(
        mutableMapOf(
            "test"       to builtin(::test),
            "find"       to builtin(::find),
            "findAll"    to builtin(::findAll),
            "groups"     to builtin(::groups),
            "replace"    to builtin(::replace),
            "replaceAll" to builtin(::replaceAll),
            "split"      to builtin(::split),
            "escape"     to builtin(::escape),
        ),
    )

    private fun builtin(handler: (List<JetValue>) -> JetValue): JetValue = JBuiltin { handler(it) }

    private fun compile(pattern: String, fn: String): Pattern = try {
        Pattern.compile(pattern)
    } catch (e: PatternSyntaxException) {
        throw RuntimeException("Function '$fn': invalid regex pattern: ${e.description}")
    }

    private fun requireString(value: JetValue): String =
        (value as JString).value

    private fun test(args: List<JetValue>): JetValue {
        val str = requireString(args[0])
        val pattern = requireString(args[1])
        return JBool(compile(pattern, "regex.test").matcher(str).find())
    }

    private fun find(args: List<JetValue>): JetValue {
        val str = requireString(args[0])
        val pattern = requireString(args[1])
        val m = compile(pattern, "regex.find").matcher(str)
        return if (m.find()) JString(m.group()) else JNull
    }

    private fun findAll(args: List<JetValue>): JetValue {
        val str = requireString(args[0])
        val pattern = requireString(args[1])
        val m = compile(pattern, "regex.findAll").matcher(str)
        val results = mutableListOf<JetValue>()
        while (m.find()) results += JString(m.group())
        return JList(results)
    }

    private fun groups(args: List<JetValue>): JetValue {
        val str = requireString(args[0])
        val pattern = requireString(args[1])
        val m = compile(pattern, "regex.groups").matcher(str)
        if (!m.find()) return JList(mutableListOf())
        val results = mutableListOf<JetValue>()
        for (i in 1..m.groupCount()) {
            results += m.group(i)?.let { JString(it) } ?: JNull
        }
        return JList(results)
    }

    private fun replace(args: List<JetValue>): JetValue {
        val str = requireString(args[0])
        val pattern = requireString(args[1])
        val replacement = requireString(args[2])
        return JString(compile(pattern, "regex.replace").matcher(str).replaceFirst(Matcher.quoteReplacement(replacement)))
    }

    private fun replaceAll(args: List<JetValue>): JetValue {
        val str = requireString(args[0])
        val pattern = requireString(args[1])
        val replacement = requireString(args[2])
        return JString(compile(pattern, "regex.replaceAll").matcher(str).replaceAll(Matcher.quoteReplacement(replacement)))
    }

    private fun split(args: List<JetValue>): JetValue {
        val str = requireString(args[0])
        val pattern = requireString(args[1])
        val parts = compile(pattern, "regex.split").split(str, -1)
        return JList(parts.map { JString(it) as JetValue }.toMutableList())
    }

    private fun escape(args: List<JetValue>): JetValue {
        val str = requireString(args[0])
        return JString(Pattern.quote(str))
    }
}
