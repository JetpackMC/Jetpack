package dev.jetpack.engine.runtime.module

import dev.jetpack.engine.runtime.nativeapi.NativeBridge

class PaperModule {
    fun spec(): ModuleSpec = ModuleSpec(
        name = "paper",
        value = NativeBridge.packageModule("io.papermc.paper"),
        fields = emptyMap(),
        dynamic = true,
    )
}
