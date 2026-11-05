package com.dreamcast.nativeapp.data

import java.io.ByteArrayInputStream
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.util.Base64
import org.json.JSONArray

/** Reads both encodings used by shared_preferences_android, without its Flutter runtime. */
object LegacyPreferences {
    private const val prefix = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"

    fun stringList(value: String): List<String> =
        runCatching {
                val payload = value.removePrefix(prefix).removePrefix("!")
                if (payload.startsWith("[")) {
                    val json = JSONArray(payload)
                    (0 until json.length()).map { json.getString(it) }
                } else {
                    val bytes = Base64.getMimeDecoder().decode(payload)
                    val stream =
                        object : ObjectInputStream(ByteArrayInputStream(bytes)) {
                            override fun resolveClass(description: ObjectStreamClass): Class<*> {
                                require(
                                    description.name in
                                        setOf(
                                            "java.util.ArrayList",
                                            "java.util.Arrays\$ArrayList",
                                            "java.lang.String",
                                            "[Ljava.lang.String;",
                                        )
                                ) {
                                    "Unsupported preference list type"
                                }
                                return super.resolveClass(description)
                            }

                            override fun resolveProxyClass(
                                interfaces: Array<out String>
                            ): Class<*> =
                                throw InvalidClassException("Unsupported preference proxy")
                        }
                    stream.use {
                        (it.readObject() as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    }
                }
            }
            .getOrDefault(emptyList())
}
