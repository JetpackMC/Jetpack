package dev.jetpack.engine.parser.ast

sealed class JetType {
    object TInt      : JetType() { override fun toString() = "int" }
    object TFloat    : JetType() { override fun toString() = "float" }
    object TString   : JetType() { override fun toString() = "string" }
    object TBool     : JetType() { override fun toString() = "bool" }
    object TObject   : JetType() { override fun toString() = "object" }
    object TNull     : JetType() { override fun toString() = "null" }
    object TFunction : JetType() { override fun toString() = "function" }
    object TInterval : JetType() { override fun toString() = "interval" }
    object TListener : JetType() { override fun toString() = "listener" }
    object TCommand  : JetType() { override fun toString() = "command" }

    object TUnknown  : JetType() { override fun toString() = "unknown" }
    data class TNullable(val innerType: JetType) : JetType() {
        override fun toString() = "$innerType?"
    }

    data class TList(val elementType: JetType) : JetType() {
        override fun toString() = "list<$elementType>"
    }

    data class TModule(val fields: Map<String, JetType>) : JetType() {
        override fun toString() = "module"
    }

    data class TCallable(
        val returnType: JetType,
        val signatures: List<CallSignature> = emptyList(),
    ) : JetType() {
        override fun toString() = "callable<$returnType>"
    }

    fun isNumeric() = this == TInt || this == TFloat

    fun isNullable() = this == TNull || this is TNullable

    fun withoutNull(): JetType = when (this) {
        is TNullable -> innerType.withoutNull()
        else -> this
    }

    fun accepts(other: JetType): Boolean = matchScore(other) != null

    fun matchScore(other: JetType): Int? {
        if (this == TUnknown || other == TUnknown) return 100

        if (this == other) return 0

        if (this is TNullable) {
            if (other == TNull) return 0
            return innerType.matchScore(other)?.plus(10)
        }

        if (other == TNull) return null

        if (this is TList && other is TList) {
            return elementType.matchScore(other.elementType)?.plus(1)
        }

        if (this.isNumeric() && other.isNumeric()) {
            return 1
        }

        return null
    }
}

data class CallSignature(
    val paramTypes: List<JetType> = emptyList(),
    val requiredCount: Int = paramTypes.size,
    val variadicType: JetType? = null,
    val returnType: JetType? = null,
) {
    init {
        require(requiredCount in 0..paramTypes.size) {
            "requiredCount must be between 0 and ${paramTypes.size}"
        }
    }

    fun accepts(args: List<JetType>): Boolean = matchScore(args) != null

    fun matchScore(args: List<JetType>): Int? {
        if (variadicType == null) {
            if (args.size !in requiredCount..paramTypes.size) return null
        } else if (args.size < requiredCount || args.size < paramTypes.size) {
            return null
        }

        var score = 0
        for (i in paramTypes.indices) {
            val argType = args.getOrNull(i) ?: break
            val matchScore = paramTypes[i].matchScore(argType) ?: return null
            score += matchScore
        }

        if (variadicType != null) {
            for (i in paramTypes.size until args.size) {
                val matchScore = variadicType.matchScore(args[i]) ?: return null
                score += matchScore
            }
        }

        return score
    }

    fun describe(): String {
        val fixed = paramTypes.mapIndexed { index, type ->
            if (index < requiredCount) type.toString() else "$type?"
        }.toMutableList()
        if (variadicType != null) fixed += "$variadicType..."
        return "(${fixed.joinToString(", ")})"
    }
}

fun signature(
    vararg paramTypes: JetType,
    requiredCount: Int = paramTypes.size,
    variadicType: JetType? = null,
    returnType: JetType? = null,
): CallSignature = CallSignature(paramTypes.toList(), requiredCount, variadicType, returnType)

fun callable(returnType: JetType, vararg signatures: CallSignature): JetType.TCallable =
    JetType.TCallable(
        returnType,
        signatures.map { signature ->
            if (signature.returnType != null) signature
            else signature.copy(returnType = returnType)
        },
    )

fun paramsToCallSignature(params: List<Param>): CallSignature = CallSignature(
    paramTypes = params.map { it.typeName?.toJetTypeOrNull() ?: JetType.TUnknown },
    requiredCount = params.count { it.default == null },
)

fun TypeRef.toJetTypeOrNull(): JetType? = when (name) {
    "int"    -> JetType.TInt
    "float"  -> JetType.TFloat
    "string" -> JetType.TString
    "bool"   -> JetType.TBool
    "object" -> JetType.TObject
    "null"   -> JetType.TNull
    "var"    -> JetType.TUnknown
    "list"   -> {
        val elementType = typeArgRef?.toJetTypeOrNull() ?: return null
        JetType.TList(elementType)
    }
    else -> null
}

fun TypeRef.toJetType(): JetType = toJetTypeOrNull() ?: JetType.TUnknown

fun JetType.asNullable(): JetType = when (this) {
    JetType.TNull -> JetType.TNull
    is JetType.TNullable -> this
    else -> JetType.TNullable(this)
}
