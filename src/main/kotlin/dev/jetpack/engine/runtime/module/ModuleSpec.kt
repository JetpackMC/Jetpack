package dev.jetpack.engine.runtime.module

import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.runtime.JetValue

data class ModuleSpec(
    val name: String,
    val value: JetValue.JModule,
    val fields: Map<String, JetType>,
    val dynamic: Boolean = false,
)
