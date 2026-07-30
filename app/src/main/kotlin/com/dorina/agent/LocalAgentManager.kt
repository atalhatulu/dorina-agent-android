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

    private val OLLAMA_CHAT_URL = "http://127.0.0.1:11434/api/chat"
    private val OLLAMA_GEN_URL = "http://127.0.0.1:11434/api/generate"

    // ── Agent State ──
    private val conversationHistory = mutableListOf<ChatMessage>()
    private val MAX_HISTORY = 10  // son 5 diyalog

    data class ChatMessage(val role: String, val content: String)  // role: user / assistant / tool

    data class ToolDef(
        val name: String,
        val description: String,
        val args: String
    )

    private val availableTools = listOf(
        ToolDef("get_battery", "Pil/seviye öğrenme", "{}"),
        ToolDef("get_wifi_status", "Wi-Fi/internet bağlantı durumu", "{}"),
        ToolDef("device_info", "Cihaz model, RAM, depolama bilgisi", "{}"),
        ToolDef("terminal", "Her türlü shell/terminal komutu. ping, dosya işlemleri, curl, sistem bilgisi", "{\"command\": \"...\"}"),
        ToolDef("open_camera", "Kamerayı açar", "{}"),
        ToolDef("toggle_flash", "Feneri açar/kapatır", "{\"state\": \"on/off\"}"),
        ToolDef("open_app", "Bir uygulamayı açar", "{\"app_name\": \"...\"}"),
        ToolDef("write_note", "Not kaydeder", "{\"text\": \"...\"}"),
        ToolDef("read_notes", "Kayıtlı notları okur", "{}"),
        ToolDef("read_file", "Dosya okur", "{\"file_name\": \"...\"}")
    )

    init {
        autoDiscoverAndInitializeModel()
    }

    // ── Model Keşfi ──
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
                if (initializeMediaPipe(path)) return
            }
        }
        activeEngineName = if (checkOllamaConnection()) "Ollama" else "Kural Motoru"
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
                .setResultListener { _, _ -> }
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            activeEngineName = "MediaPipe (${file.name})"
            true
        } catch (e: Exception) {
            Timber.e(e, "MediaPipe init failed at $modelPath")
            false
        }
    }

    // ── REACT AGENT LOOP ──

    suspend fun processQuery(userInput: String): Flow<AgentState> = flow {
        emit(AgentState.Thinking("Düşünüyorum..."))

        // Kullanıcı mesajını ekle
        conversationHistory.add(ChatMessage("user", userInput))

        var maxSteps = 8
        var stepCount = 0
        var finalAnswer: String? = null

        while (stepCount < maxSteps && finalAnswer == null) {
            stepCount++
            emit(AgentState.Thinking("Adım $stepCount/$maxSteps..."))

            // 1. THINK: LLM'den karar al
            val llmResponse = askLLM()
            if (llmResponse.isBlank()) {
                // LLM yoksa kural motoruna düş
                val ruleResult = simulateRuleFallback(userInput)
                val toolCall = parseToolCall(ruleResult)
                if (toolCall != null) {
                    val (toolName, args) = toolCall
                    emit(AgentState.ExecutingTool(toolName, args))
                    val toolResult = ToolRegistry.executeTool(context, toolName, args)
                    conversationHistory.add(ChatMessage("tool", "[$toolName] ${toolResult.result}"))
                    val summary = formatSimpleResult(toolName, toolResult)
                    conversationHistory.add(ChatMessage("assistant", summary))
                    trimHistory()
                    emit(AgentState.Completed(summary, toolResult))
                    return@flow
                }
                // Hiçbir şey bulamadı
                val fallbackMsg = "Ne yapacağımı bilemedim. Şunları deneyebilirsin: pil kaç, hava nasıl, WhatsApp'ı aç, ping at, not al"
                conversationHistory.add(ChatMessage("assistant", fallbackMsg))
                trimHistory()
                emit(AgentState.Completed(fallbackMsg, null))
                return@flow
            }

            // 2. PARSE: Tool çağrısı mı, yoksa direkt cevap mı?
            val toolCall = parseToolCall(llmResponse)

            if (toolCall != null) {
                // ACT: Tool'u çalıştır
                val (toolName, args) = toolCall
                emit(AgentState.ExecutingTool(toolName, args))
                val toolResult = ToolRegistry.executeTool(context, toolName, args)

                // OBSERVE: Sonucu geçmişe ekle (LLM bir sonraki adımda görecek)
                val observation = "[TOOL: $toolName]\n${toolResult.result}"
                conversationHistory.add(ChatMessage("assistant", llmResponse))
                conversationHistory.add(ChatMessage("tool", observation))
                trimHistory()

                if (!toolResult.success) {
                    // Hata durumunda LLM'e bildir, düzeltmesini iste
                    conversationHistory.add(ChatMessage("user", "Araç '$toolName' hata verdi: ${toolResult.result}. Lütfen düzelt veya alternatif bir yol dene."))
                }
            } else {
                // FINAL ANSWER: LLM direkt cevap verdi, döngüyü bitir
                val cleanAnswer = llmResponse
                    .replace(Regex("<thinking>.*?</thinking>", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("<thought>.*?</thought>", RegexOption.DOT_MATCHES_ALL), "")
                    .trim()

                conversationHistory.add(ChatMessage("assistant", cleanAnswer))
                trimHistory()
                emit(AgentState.Completed(cleanAnswer, null))
                return@flow
            }
        }

        // Maksimum adım aşıldı
        val timeoutMsg = "Çok adımlı bir işlem ama tamamlayamadım. Lütfen daha basit bir şekilde iste."
        conversationHistory.add(ChatMessage("assistant", timeoutMsg))
        trimHistory()
        emit(AgentState.Completed(timeoutMsg, null))
    }.flowOn(Dispatchers.IO)

    // ── LLM Sorgulama ──

    private fun askLLM(): String {
        // Prompt'u oluştur
        val toolDescriptions = availableTools.joinToString("\n") { t ->
            "  - ${t.name}: ${t.description} (args: ${t.args})"
        }

        // Konuşma geçmişini formatla
        val historyText = conversationHistory.joinToString("\n") { msg ->
            when (msg.role) {
                "user" -> "Kullanıcı: ${msg.content}"
                "assistant" -> "Dorina: ${msg.content}"
                "tool" -> "[Gözlem]\n${msg.content}"
                else -> "${msg.content}"
            }
        }

        val systemPrompt = """
Sen Dorina'sın — Android cihazda çalışan basit ama zeki bir AI asistan.

KULLANILABİLİR ARAÇLAR:
$toolDescriptions

KURALLAR:
1. Kullanıcının isteğini anla.
2. Eğer bir araç kullanman gerekiyorsa, JSON formatında yanıt ver:
   {"tool": "tool_adi", "args": {"param": "deger"}}
3. Araç gerekiyorsa SADECE JSON döndür, fazla metin yazma.
4. Araç kullanmadan cevap verebiliyorsan direkt Türkçe cevap ver.
5. Tool çalıştıysa ve sonuç geldiyse, o sonuca göre kullanıcıya güzel bir cevap hazırla.
6. Eğer kullanıcının isteği için birden fazla araç gerekiyorsa, sırayla her adımda birini çağır.

KARAR VERME:
- Eğer bir araç çağırman gerekiyorsa → {"tool": "...", "args": {...}}
- Eğer cevap hazırsa veya araç gerekmiyorsa → direkt Türkçe metin

KONUŞMA GEÇMİŞİ:
$historyText

Kullanıcı son mesajı: ${conversationHistory.lastOrNull { it.role == "user" }?.content ?: ""}

Şimdi karar ver:
""".trimIndent()

        // MediaPipe dene
        if (isInitialized && llmInference != null) {
            try {
                val result = llmInference?.generateResponse(systemPrompt) ?: ""
                if (result.length > 5) return result
            } catch (e: Exception) {
                Timber.w("MediaPipe error: ${e.message}")
            }
        }

        // Ollama dene
        val ollamaResult = queryOllama(systemPrompt)
        if (ollamaResult != null) return ollamaResult

        return ""
    }

    // ── Tool Call Parse ──
    private fun parseToolCall(response: String): Pair<String, Map<String, String>>? {
        try {
            val trimmed = response.trim()
            // JSON object ara
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
                            argsMap[key] = argsObj.optString(key, "")
                        }
                    }
                    return Pair(toolName, argsMap)
                }
            }

            // Bazen LLM sadece tool ismi yazabilir
            val toolMatch = Regex("""\{\s*"tool"\s*:\s*"(\w+)"\s*\}""").find(trimmed)
            if (toolMatch != null) {
                return Pair(toolMatch.groupValues[1], emptyMap())
            }
        } catch (e: Exception) {
            Timber.w("parseToolCall error: ${e.message}")
        }
        return null
    }

    // ── Ollama ──
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
        } catch (_: Exception) { false }
    }

    private fun queryOllama(prompt: String): String? {
        // Önce chat API dene (daha iyi context yönetimi)
        val chatResult = queryOllamaChat(prompt)
        if (chatResult != null) return chatResult
        // Fallback: generate API
        return queryOllamaGenerate(prompt)
    }

    private fun queryOllamaChat(systemPrompt: String): String? {
        return try {
            val url = URL(OLLAMA_CHAT_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 30000
            conn.doOutput = true
            // Messages array oluştur
            val messagesArray = org.json.JSONArray()
            // System prompt
            val systemMsg = JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            }
            messagesArray.put(systemMsg)
            // Geçmiş konuşmalar
            for (msg in conversationHistory) {
                val role = when (msg.role) {
                    "user" -> "user"
                    "assistant" -> "assistant"
                    "tool" -> "user" // tool output'u user message olarak ekle
                    else -> "user"
                }
                val msgObj = JSONObject().apply {
                    put("role", role)
                    put("content", msg.content)
                }
                messagesArray.put(msgObj)
            }
            val jsonBody = JSONObject().apply {
                put("model", "gemma:2b")
                put("messages", messagesArray)
                put("stream", false)
                put("options", JSONObject().apply {
                    put("temperature", 0.7)
                    put("num_predict", 1024)
                })
            }
            conn.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }
            if (conn.responseCode == 200) {
                val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(responseStr)
                responseJson.getJSONObject("message").optString("content", null)
            } else null
        } catch (e: Exception) {
            Timber.w("Ollama chat: ${e.message}")
            null
        }
    }

    private fun queryOllamaGenerate(prompt: String): String? {
        return try {
            val url = URL(OLLAMA_GEN_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 3000
            conn.readTimeout = 30000
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
                responseJson.optString("response", null)
            } else null
        } catch (e: Exception) {
            Timber.w("Ollama: ${e.message}")
            null
        }
    }

    // ── Kural Motoru (fallback) ──
    private fun simulateRuleFallback(userInput: String): String {
        val lower = userInput.lowercase().trim()
            .replace("[.!?,;:]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

        // Hava durumu
        if (setOf("hava", "weather", "sıcaklık", "derece", "yağmur", "kar", "rüzgar", "bulut", "güneş").any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "curl -s 'wttr.in/Istanbul?format=%C+%t+%w'"}}"""
        }
        // Pil
        if (setOf("şarj", "pil", "batarya", "battery", "yüzde", "dolum", "enerji", "charge").any { it in lower }) {
            return """{"tool": "get_battery", "args": {}}"""
        }
        // Cihaz bilgisi
        if (setOf("cihaz", "model", "telefon", "sistem", "özellik", "bilgi", "info", "donanım", "hardware").any { it in lower }) {
            return """{"tool": "device_info", "args": {}}"""
        }
        // Wi-Fi
        if (setOf("wifi", "wi-fi", "internet", "bağlantı", "network").any { it in lower }) {
            return """{"tool": "get_wifi_status", "args": {}}"""
        }
        // Not al
        if (setOf("not et", "hatırla", "kaydet", "not al", "not tut", "unutma", "yaz", "aklımda").any { it in lower }) {
            return """{"tool": "write_note", "args": {"text": "$userInput"}}"""
        }
        // Kamera
        if (setOf("kamera", "fotoğraf", "çek", "selfie", "foto", "resim").any { it in lower }) {
            return """{"tool": "open_camera", "args": {}}"""
        }
        // Flaş
        if (setOf("flaş", "fener", "ışık", "flash", "torch", "lamba", "el feneri").any { it in lower }) {
            val state = if (lower.contains("kapat") || lower.contains("söndür") || lower.contains("off")) "off" else "on"
            return """{"tool": "toggle_flash", "args": {"state": "$state"}}"""
        }
        // Uygulama açma (gelişmiş regex ile)
        val acmaRegex = Regex("""(.{2,25})['’]?(i|ı|yi|yı|e|a|ye|ya|ü|u|yu|yü)?\s*(aç|başlat|çalıştır|gir|start|launch|open)\b""", RegexOption.IGNORE_CASE)
        val match = acmaRegex.find(lower)
        if (match != null) {
            var appName = match.groupValues[1].trim()
            appName = appName.replace(Regex("""['’]?(i|ı|yi|yı|e|a|ye|ya|u|ü|yu|yü)$""", RegexOption.IGNORE_CASE), "").trim()
            if (appName.length in 2..25) {
                return """{"tool": "open_app", "args": {"app_name": "$appName"}}"""
            }
        }

        return ""
    }

    private fun formatSimpleResult(toolName: String, result: ToolResult): String {
        return when (toolName) {
            "get_battery" -> result.result.replace("Mevcut batarya seviyesi: %", "Pil seviyen: %")
            "get_wifi_status" -> result.result.replace("Ağ Durumu:", "Bağlantı:")
            "device_info" -> result.result
            "open_app" -> result.result
            "toggle_flash" -> result.result
            "terminal" -> result.result.lines().filter { it.isNotBlank() }.joinToString("\n")
            else -> result.result
        }
    }

    private fun trimHistory() {
        while (conversationHistory.size > MAX_HISTORY) {
            conversationHistory.removeFirst()
        }
    }
}

sealed class AgentState {
    data class Thinking(val message: String) : AgentState()
    data class ExecutingTool(val toolName: String, val args: Map<String, String>) : AgentState()
    data class Completed(val answer: String, val toolResult: ToolResult?) : AgentState()
    data class Error(val errorMessage: String) : AgentState()
}
