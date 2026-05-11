package dev.jetpack.engine.runtime.module

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.JBool
import dev.jetpack.engine.runtime.JetValue.JBuiltin
import dev.jetpack.engine.runtime.JetValue.JFloat
import dev.jetpack.engine.runtime.JetValue.JFunction
import dev.jetpack.engine.runtime.JetValue.JInt
import dev.jetpack.engine.runtime.JetValue.JCommand
import dev.jetpack.engine.runtime.JetValue.JInterval
import dev.jetpack.engine.runtime.JetValue.JListener
import dev.jetpack.engine.runtime.JetValue.JList
import dev.jetpack.engine.runtime.JetValue.JNull
import dev.jetpack.engine.runtime.JetValue.JModule
import dev.jetpack.engine.runtime.JetValue.JObject
import dev.jetpack.engine.runtime.JetValue.JString
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

class StorageReadException(message: String) : RuntimeException(message)

class StorageService(private val storageRoot: File) {

    private val locks = ConcurrentHashMap<String, Any>()

    init {
        storageRoot.mkdirs()
    }

    fun create(storageId: String): Boolean {
        val target = resolveStorageFile(storageId) ?: return false
        return synchronized(lockFor(target)) {
            if (target.isFile) {
                return@synchronized false
            }
            writeEntries(target, emptyMap())
        }
    }

    fun destroy(storageId: String): Boolean {
        val target = resolveStorageFile(storageId) ?: return false
        return synchronized(lockFor(target)) {
            if (!target.isFile) {
                false
            } else {
                val deleted = target.delete()
                if (deleted) locks.remove(target.canonicalPath)
                deleted
            }
        }
    }

    fun exists(storageId: String): Boolean {
        val target = resolveStorageFile(storageId) ?: return false
        return target.isFile
    }

    fun has(storageId: String, key: String): Boolean {
        val target = resolveStorageFile(storageId) ?: return false
        return synchronized(lockFor(target)) {
            if (!target.isFile) {
                false
            } else {
                loadEntries(target).containsKey(key)
            }
        }
    }

    fun keys(storageId: String): List<String> {
        val target = resolveStorageFile(storageId) ?: return emptyList()
        return synchronized(lockFor(target)) {
            if (!target.isFile) {
                emptyList()
            } else {
                loadEntries(target).keys.sorted()
            }
        }
    }

    fun getAll(storageId: String): JetValue {
        val target = resolveStorageFile(storageId) ?: return JNull
        return synchronized(lockFor(target)) {
            if (!target.isFile) {
                JNull
            } else {
                JObject(loadEntries(target).toMutableMap())
            }
        }
    }

    fun set(storageId: String, key: String, value: JetValue): Boolean {
        val target = resolveStorageFile(storageId) ?: return false
        return synchronized(lockFor(target)) {
            if (!target.isFile) {
                return@synchronized false
            }

            val entries = runCatching { loadEntries(target).toMutableMap() }
                .getOrElse { return@synchronized false }

            entries[key] = value
            writeEntries(target, entries)
        }
    }

    fun get(storageId: String, key: String): JetValue {
        val target = resolveStorageFile(storageId) ?: return JNull
        return synchronized(lockFor(target)) {
            if (!target.isFile) {
                JNull
            } else {
                loadEntries(target)[key] ?: JNull
            }
        }
    }

    fun remove(storageId: String, key: String): Boolean {
        val target = resolveStorageFile(storageId) ?: return false
        return synchronized(lockFor(target)) {
            if (!target.isFile) {
                return@synchronized false
            }

            val entries = runCatching { loadEntries(target).toMutableMap() }
                .getOrElse { return@synchronized false }

            if (entries.remove(key) == null) {
                return@synchronized false
            }

            writeEntries(target, entries)
        }
    }

    fun clear(storageId: String): Boolean {
        val target = resolveStorageFile(storageId) ?: return false
        return synchronized(lockFor(target)) {
            if (!target.isFile) {
                return@synchronized false
            }

            writeEntries(target, emptyMap())
        }
    }

    private fun resolveStorageFile(storageId: String): File? {
        val normalizedId = storageId.trim().replace('\\', '/')
        if (normalizedId.isEmpty() || normalizedId.startsWith("/") || ':' in normalizedId) {
            return null
        }

        val segments = normalizedId.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            return null
        }

        var current = storageRoot.canonicalFile
        for (segment in segments.dropLast(1)) {
            current = File(current, segment)
        }

        val target = File(current, "${segments.last()}.db").canonicalFile
        if (!target.toPath().startsWith(storageRoot.canonicalFile.toPath())) {
            return null
        }

        return target
    }

    private fun lockFor(file: File): Any =
        locks.computeIfAbsent(file.canonicalPath) { Any() }

    private fun loadEntries(file: File): Map<String, JetValue> {
        val root = try {
            JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
        } catch (_: Exception) {
            throw StorageReadException("Storage '${file.name}' contains invalid JSON")
        }

        if (root.get("version")?.asInt != 1) {
            throw StorageReadException("Storage '${file.name}' has an unsupported or missing version")
        }

        val rawEntries = root.getAsJsonArray("entries")
            ?: throw StorageReadException("Storage '${file.name}' is missing entries")

        val entries = linkedMapOf<String, JetValue>()
        for (rawEntry in rawEntries) {
            val entry = rawEntry.asJsonObject
                ?: throw StorageReadException("Storage '${file.name}' contains an invalid entry")

            val key = entry.get("key")?.asString
                ?: throw StorageReadException("Storage '${file.name}' contains an entry without a valid key")

            if (!entry.has("value")) {
                throw StorageReadException("Storage '${file.name}' contains an entry without a value")
            }

            entries[key] = deserializeValue(entry.get("value"))
        }

        return entries
    }

    private fun writeEntries(file: File, entries: Map<String, JetValue>): Boolean {
        if (!file.parentFile.exists() && !file.parentFile.mkdirs()) {
            return false
        }

        val root = runCatching {
            val entriesArray = JsonArray()
            for ((key, value) in entries) {
                val entry = JsonObject()
                entry.addProperty("key", key)
                entry.add("value", serializeValue(value, IdentityHashMap()))
                entriesArray.add(entry)
            }
            JsonObject().apply {
                addProperty("version", 1)
                add("entries", entriesArray)
            }
        }.getOrElse { return false }

        val tempFile = File.createTempFile(file.nameWithoutExtension, ".tmp", file.parentFile)
        return try {
            tempFile.writeText(root.toString(), Charsets.UTF_8)
            moveAtomically(tempFile, file)
            true
        } catch (_: Exception) {
            false
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun serializeValue(value: JetValue, visiting: IdentityHashMap<Any, Boolean>): JsonElement = when (value) {
        is JNull -> JsonNull.INSTANCE
        is JBool -> JsonPrimitive(value.value)
        is JInt -> JsonPrimitive(value.value)
        is JFloat -> JsonPrimitive(value.value)
        is JString -> JsonPrimitive(value.value)
        is JFunction, is JBuiltin, is JInterval, is JListener, is JCommand, is JModule ->
            throw IllegalArgumentException("Storage only supports JSON-compatible values")
        is JList -> {
            if (visiting.put(value, true) != null) {
                throw IllegalArgumentException("Storage values must not contain cycles")
            }
            try {
                JsonArray().apply { value.elements.forEach { add(serializeValue(it, visiting)) } }
            } finally {
                visiting.remove(value)
            }
        }
        is JObject -> {
            if (value.hasDeferredMembers()) {
                throw IllegalArgumentException("Storage only supports fully materialized objects")
            }
            if (visiting.put(value, true) != null) {
                throw IllegalArgumentException("Storage values must not contain cycles")
            }
            try {
                JsonObject().apply {
                    for ((key, fieldValue) in value.fields) {
                        add(key, serializeValue(fieldValue, visiting))
                    }
                }
            } finally {
                visiting.remove(value)
            }
        }
    }

    private fun deserializeValue(element: JsonElement): JetValue = when {
        element.isJsonNull -> JNull
        element.isJsonPrimitive -> {
            val prim = element.asJsonPrimitive
            when {
                prim.isBoolean -> JBool(prim.asBoolean)
                prim.isNumber -> {
                    val str = prim.asString
                    if ('.' in str || 'e' in str || 'E' in str) JFloat(prim.asDouble)
                    else JInt(prim.asInt)
                }
                prim.isString -> JString(prim.asString)
                else -> throw StorageReadException("Storage contains an unsupported primitive type")
            }
        }
        element.isJsonArray -> {
            JList(element.asJsonArray.map { deserializeValue(it) }.toMutableList())
        }
        element.isJsonObject -> {
            val fields = linkedMapOf<String, JetValue>()
            for ((key, fieldValue) in element.asJsonObject.entrySet()) {
                fields[key] = deserializeValue(fieldValue)
            }
            JObject(fields.toMutableMap())
        }
        else -> throw StorageReadException("Storage contains an unsupported value type")
    }
}
