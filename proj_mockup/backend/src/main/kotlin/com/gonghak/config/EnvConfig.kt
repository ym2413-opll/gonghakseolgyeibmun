package com.gonghak.config

import java.io.File

/**
 * Lightweight environment accessor: prefers real OS process environment variables,
 * and falls back to a local `.env` file when running the backend server.
 */
object EnvConfig {
    private val dotEnvValues: Map<String, String> by lazy { loadDotEnv() }

    fun get(key: String): String? {
        val processValue = System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() }
        if (processValue != null) return processValue
        if (System.getProperty("gonghak.disableDotEnv")?.equals("true", ignoreCase = true) == true) {
            return null
        }
        return dotEnvValues[key]?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun loadDotEnv(): Map<String, String> {
        val candidates = listOf(
            File(".env"),
            File("proj/backend/.env"),
            File("../.env")
        )
        val envFile = candidates.firstOrNull { it.isFile && it.canRead() } ?: return emptyMap()
        val parsed = LinkedHashMap<String, String>()
        runCatching {
            envFile.forEachLine(Charsets.UTF_8) { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val cleaned = if (line.startsWith("export ")) line.removePrefix("export ").trim() else line
                val eqIdx = cleaned.indexOf('=')
                if (eqIdx <= 0) return@forEachLine
                val k = cleaned.substring(0, eqIdx).trim()
                var v = cleaned.substring(eqIdx + 1).trim()
                if ((v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) ||
                    (v.startsWith("'") && v.endsWith("'") && v.length >= 2)
                ) {
                    v = v.substring(1, v.length - 1)
                }
                if (k.isNotEmpty()) {
                    parsed[k] = v
                }
            }
        }
        return parsed
    }
}
