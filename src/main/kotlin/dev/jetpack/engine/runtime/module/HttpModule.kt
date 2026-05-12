package dev.jetpack.engine.runtime.module

import com.google.gson.Gson
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

class HttpModule {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val gson = Gson()

    fun spec(): ModuleSpec = ModuleSpec(
        name = "Http",
        value = asValue(),
        fields = mapOf(
            "get" to callable(
                JetType.TObject,
                signature(JetType.TString, JetType.TObject, requiredCount = 1),
            ),
            "post" to callable(
                JetType.TObject,
                signature(JetType.TString, JetType.TObject, JetType.TObject, requiredCount = 2),
            ),
            "put" to callable(
                JetType.TObject,
                signature(JetType.TString, JetType.TObject, JetType.TObject, requiredCount = 2),
            ),
            "delete" to callable(
                JetType.TObject,
                signature(JetType.TString, JetType.TObject, requiredCount = 1),
            ),
        ),
    )

    fun asValue(): JModule = JModule(
        mutableMapOf(
            "get" to JBuiltin { args -> withContext(Dispatchers.IO) { get(args) } },
            "post" to JBuiltin { args -> withContext(Dispatchers.IO) { post(args) } },
            "put" to JBuiltin { args -> withContext(Dispatchers.IO) { put(args) } },
            "delete" to JBuiltin { args -> withContext(Dispatchers.IO) { delete(args) } },
        ),
    )

    private fun get(args: List<JetValue>): JetValue {
        val url = (args[0] as JString).value
        val headers = args.getOrNull(1) as? JObject
        return send(buildRequest("GET", url, headers, null))
    }

    private fun post(args: List<JetValue>): JetValue {
        val url = (args[0] as JString).value
        val body = args[1] as JObject
        val headers = args.getOrNull(2) as? JObject
        return send(buildRequest("POST", url, headers, body))
    }

    private fun put(args: List<JetValue>): JetValue {
        val url = (args[0] as JString).value
        val body = args[1] as JObject
        val headers = args.getOrNull(2) as? JObject
        return send(buildRequest("PUT", url, headers, body))
    }

    private fun delete(args: List<JetValue>): JetValue {
        val url = (args[0] as JString).value
        val headers = args.getOrNull(1) as? JObject
        return send(buildRequest("DELETE", url, headers, null))
    }

    private fun buildRequest(
        method: String,
        url: String,
        headers: JObject?,
        body: JObject?,
    ): HttpRequest {
        val bodyPublisher = if (body != null) {
            BodyPublishers.ofString(serializeBody(body))
        } else {
            BodyPublishers.noBody()
        }

        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .method(method, bodyPublisher)

        if (body != null) {
            builder.header("Content-Type", "application/json")
        }

        headers?.fields?.forEach { (key, value) ->
            if (value is JString) builder.header(key, value.value)
        }

        return builder.build()
    }

    private fun send(request: HttpRequest): JObject {
        val response = try {
            client.send(request, BodyHandlers.ofString())
        } catch (e: Exception) {
            throw RuntimeException("Http request failed: ${e.message}")
        }
        val status = response.statusCode()
        return JObject(
            mutableMapOf(
                "status" to JInt(status),
                "body" to JString(response.body() ?: ""),
                "ok" to JBool(status in 200..299),
            )
        )
    }

    private fun serializeBody(obj: JObject): String =
        gson.toJson(toNativeMap(obj))

    private fun toNativeValue(value: JetValue): Any? = when (value) {
        is JNull -> null
        is JBool -> value.value
        is JInt -> value.value
        is JFloat -> value.value
        is JString -> value.value
        is JList -> value.elements.map { toNativeValue(it) }
        is JObject -> toNativeMap(value)
        else -> throw RuntimeException("Http request body contains a non-serializable value of type '${value.typeName()}'")
    }

    private fun toNativeMap(obj: JObject): Map<String, Any?> =
        obj.fields.mapValues { (_, v) -> toNativeValue(v) }
}
