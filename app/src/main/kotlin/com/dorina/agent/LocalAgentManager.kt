package com.dorina.agent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class LocalAgentManager(private val context: Context) {

    private var llmInference: LlmInference? = null
    private var isInitialized = false
    var activeEngineName: String = "Detecting..."
        private set

    private val OLLAMA_URL = "http://127.0.0.1:11434/api/generate"

    private val SYSTEM_PROMPT = """
        Sen Dorina'sın, S24 Ultra cihazında çalışan çok yetenekli, akıllı ve otonom bir yerel yapay zeka asistanısın.
        Görevin, kullanıcının isteklerini anlamak ve gerekirse aşağıdaki araçları (tools) kullanarak onlara yardımcı olmaktır.
        
        AVAILABLE TOOLS (Respond ONLY in JSON if calling a tool):
        1. device_info - Get CPU, RAM, storage, battery, and network status. Args: {}
        2. get_battery - Get battery percentage. Args: {}
        3. get_wifi_status - Check network/Wi-Fi connectivity. Args: {}
        4. run_safe_command - Execute terminal commands. Args: {"command": "ping 1.1.1.1"}
        5. read_file - Read file from app storage. Args: {"file_name": "notes.txt"}
        6. write_note - Save a note to persistent local memory. Args: {"text": "Remember to check battery level"}
        7. read_notes - Read all saved memory notes. Args: {}
        8. open_camera - Launch the device camera. Args: {}
        9. toggle_flash - Turn the flashlight on or off. Args: {"state": "on" veya "off"}
        10. open_app - Uygulama açar. Args: {"app_name": "WhatsApp"} (ÖNEMLİ: Uygulama ismine ASLA ek koyma. "snapchat'i" değil "snapchat" yaz).

        RESPONSE FORMAT RULES:
        - Eğer fiziksel bir araç kullanman gerekiyorsa (uygulama açma, flaş yakma, not yazma vb.), YALNIZCA aşağıdaki gibi ham JSON çıktısı ver. Başka hiçbir şey yazma:
        {
          "tool": "tool_name",
          "args": {
            "param": "value"
          }
        }
        - Eğer araç kullanman GEREKMİYORSA, kullanıcıya doğrudan doğal, zeki ve samimi bir Türkçe ile cevap ver.
    """.trimIndent()

    init {
        autoDiscoverAndInitializeModel()
    }

    private fun autoDiscoverAndInitializeModel() {
        val searchPaths = listOf(
            "/sdcard/Download/gemma-2b-it-gpu-int4.bin",
            "/sdcard/Download/gemma-2b-it-cpu.bin",
            "/sdcard/Download/gemma.bin",
            "${context.getExternalFilesDir(null)}/gemma-2b-it-gpu-int4.bin",
            "${context.getExternalFilesDir(null)}/gemma-2b-it-cpu.bin"
        )

        for (path in searchPaths) {
            val file = File(path)
            if (file.exists() && file.length() > 50_000_000) {
                if (initializeMediaPipe(path)) {
                    activeEngineName = "MediaPipe GenAI (${file.name})"
                    return
                }
            }
        }

        // Test if Ollama is running
        val ollamaOk = checkOllamaConnection()
        if (ollamaOk) {
            activeEngineName = "Ollama Local (gemma:2b)"
        } else {
            activeEngineName = "Local Rule-Engine (Offline Fallback)"
        }
    }

    fun initializeMediaPipe(modelPath: String): Boolean {
        return try {
            val file = File(modelPath)
            if (!file.exists()) return false

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .setTopK(40)
                .setTemperature(0.7f)
                .setResultListener { partialResult, done -> }
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            activeEngineName = "MediaPipe GenAI (${file.name})"
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize MediaPipe model at $modelPath")
            false
        }
    }

    suspend fun processQuery(userInput: String): Flow<AgentState> = flow {
        emit(AgentState.Thinking("Sorgu işleniyor [$activeEngineName]..."))

        val fullPrompt = "$SYSTEM_PROMPT\n\nUser: $userInput\nAssistant:"

        val rawResponse = when {
            isInitialized && llmInference != null -> {
                try {
                    llmInference?.generateResponse(fullPrompt) ?: ""
                } catch (e: Exception) {
                    queryOllamaFallback(fullPrompt) ?: simulateRuleFallback(userInput)
                }
            }
            else -> {
                queryOllamaFallback(fullPrompt) ?: simulateRuleFallback(userInput)
            }
        }

        val toolCall = parseToolCall(rawResponse)

        if (toolCall != null) {
            val toolName = toolCall.first
            val args = toolCall.second

            emit(AgentState.ExecutingTool(toolName, args))

            val toolResult = ToolRegistry.executeTool(context, toolName, args)

            emit(AgentState.Thinking("Araç sonucu özetleniyor..."))

            val summarizePrompt = """
                User Query: $userInput
                Executed Tool: $toolName
                Tool Output: ${toolResult.result}
                
                Please summarize the result nicely in Turkish for the user.
            """.trimIndent()

            val finalAnswer = when {
                isInitialized && llmInference != null -> {
                    try {
                        llmInference?.generateResponse(summarizePrompt) ?: toolResult.result
                    } catch (e: Exception) {
                        toolResult.result
                    }
                }
                else -> {
                    queryOllamaFallback(summarizePrompt) ?: "Araç Sonucu ($toolName):\n${toolResult.result}"
                }
            }

            emit(AgentState.Completed(finalAnswer, toolResult))
        } else {
            emit(AgentState.Completed(rawResponse, null))
        }
    }.flowOn(Dispatchers.IO)

    private fun checkOllamaConnection(): Boolean {
        return try {
            val url = URL("http://127.0.0.1:11434/api/tags")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (e: Exception) {
            false
        }
    }

    private fun queryOllamaFallback(prompt: String): String? {
        return try {
            val url = URL(OLLAMA_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 3000
            conn.readTimeout = 15000
            conn.doOutput = true

            val jsonBody = JSONObject().apply {
                put("model", "gemma:2b")
                put("prompt", prompt)
                put("stream", false)
            }

            conn.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(responseStr)
                activeEngineName = "Ollama Local (gemma:2b)"
                responseJson.optString("response", null)
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.w("Ollama local server not reachable: ${e.message}")
            null
        }
    }

    private fun parseToolCall(response: String): Pair<String, Map<String, String>>? {
        return try {
            val trimmed = response.trim()
            val jsonStart = trimmed.indexOf('{')
            val jsonEnd = trimmed.lastIndexOf('}')
            if (jsonStart != -1 && jsonEnd > jsonStart) {
                val jsonStr = trimmed.substring(jsonStart, jsonEnd + 1)
                val jsonObj = JSONObject(jsonStr)
                if (jsonObj.has("tool")) {
                    val toolName = jsonObj.getString("tool")
                    val argsMap = mutableMapOf<String, String>()
                    if (jsonObj.has("args")) {
                        val argsObj = jsonObj.getJSONObject("args")
                        argsObj.keys().forEach { key ->
                            argsMap[key] = argsObj.getString(key)
                        }
                    }
                    return Pair(toolName, argsMap)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun simulateRuleFallback(userInput: String): String {
        val lower = userInput.lowercase()
        return when {
            lower.contains("şarj") || lower.contains("pil") || lower.contains("batarya") -> {
                """{"tool": "get_battery", "args": {}}"""
            }
            lower.contains("sistem") || lower.contains("cihaz") || lower.contains("ram") || lower.contains("depolama") -> {
                """{"tool": "device_info", "args": {}}"""
            }
            lower.contains("internet") || lower.contains("wifi") || lower.contains("bağlantı") -> {
                """{"tool": "get_wifi_status", "args": {}}"""
            }
            lower.contains("not et") || lower.contains("hatırla") || lower.contains("kaydet") -> {
                """{"tool": "write_note", "args": {"text": "$userInput"}}"""
            }
            lower.contains("notlar") || lower.contains("hafıza") -> {
                """{"tool": "read_notes", "args": {}}"""
            }
            lower.contains("ping") -> {
                val ip = if (lower.contains("8.8.8.8")) "8.8.8.8" else "1.1.1.1"
                """{"tool": "run_safe_command", "args": {"command": "ping -c 2 $ip"}}"""
            }
            lower.contains("tarih") || lower.contains("saat") -> {
                """{"tool": "run_safe_command", "args": {"command": "date"}}"""
            }
            lower.contains("kamera") || lower.contains("fotoğraf") -> {
                """{"tool": "open_camera", "args": {}}"""
            }
            lower.contains("flaş") || lower.contains("fener") || lower.contains("ışık") || lower.contains("flash") -> {
                val state = if (lower.contains("kapat")) "off" else "on"
                """{"tool": "toggle_flash", "args": {"state": "$state"}}"""
            }
            lower.contains("aç") && !lower.contains("flaş") && !lower.contains("fener") && !lower.contains("kamera") -> {
                // Basit kural tabanlı uygulama açma mantığı (Uygulama ismini tahmin eder)
                val words = lower.split(" ")
                val appNameIndex = words.indexOf("aç") - 1
                val appName = if (appNameIndex >= 0) words[appNameIndex] else "Bilinmeyen"
                """{"tool": "open_app", "args": {"app_name": "$appName"}}"""
            }
            else -> "Merhaba! Ben Dorina. S24 Ultra cihazınızda yerel AI Ajanınız hizmetinizde. Nasıl yardımcı olabilirim?"
        }
    }
}

sealed class AgentState {
    data class Thinking(val message: String) : AgentState()
    data class ExecutingTool(val toolName: String, val args: Map<String, String>) : AgentState()
    data class Completed(val answer: String, val toolResult: ToolResult?) : AgentState()
    data class Error(val errorMessage: String) : AgentState()
}
