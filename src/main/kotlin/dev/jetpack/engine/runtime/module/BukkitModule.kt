package dev.jetpack.engine.runtime.module

import dev.jetpack.engine.runtime.nativeapi.NativeBridge

class BukkitModule {
    fun spec(): ModuleSpec = ModuleSpec(
        name = "bukkit",
        value = NativeBridge.packageModule("org.bukkit"),
        fields = emptyMap(),
        dynamic = true,
    )
}
