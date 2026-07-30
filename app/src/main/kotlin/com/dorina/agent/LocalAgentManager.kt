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

    // ── Bellek (konuşma geçmişi) ──
    private val conversationHistory = mutableListOf<Pair<String, String>>()  // (user, assistant)
    private val MAX_HISTORY = 6  // son 3 diyalogu hatırla

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
        ToolDef("open_app", "Bir uygulamayı açar", "{\"app_name\": \"WhatsApp\"}"),
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

    // ── ANA AGENT LOOP ──

    suspend fun processQuery(userInput: String): Flow<AgentState> = flow {
        emit(AgentState.Thinking("Anlamaya çalışıyorum..."))

        // 1. Önce Kural Motoru (hızlı, LLM gerektirmez)
        val ruleResult = simulateRuleFallback(userInput)
        val ruleToolCall = parseJsonToolCall(ruleResult)
        if (ruleToolCall != null) {
            val (toolName, args) = ruleToolCall
            emit(AgentState.ExecutingTool(toolName, args))
            val toolResult = ToolRegistry.executeTool(context, toolName, args)

            val summary = summarizeWithLLM(userInput, toolName, toolResult)
            conversationHistory.add(userInput to summary)
            trimHistory()

            emit(AgentState.Completed(summary, toolResult))
            return@flow
        }

        // 2. LLM İLE KARAR VERME (sınıflandırma + tool seçimi)
        if (isInitialized && llmInference != null || checkOllamaConnection()) {
            val llmDecision = askLLM(userInput)
            val toolCall = parseJsonToolCall(llmDecision)

            if (toolCall != null) {
                val (toolName, args) = toolCall
                emit(AgentState.ExecutingTool(toolName, args))
                val toolResult = ToolRegistry.executeTool(context, toolName, args)

                val summary = summarizeWithLLM(userInput, toolName, toolResult, llmDecision)
                conversationHistory.add(userInput to summary)
                trimHistory()

                emit(AgentState.Completed(summary, toolResult))
                return@flow
            }

            // LLM tool çağırmadı ama anlamlı cevap verdi
            if (llmDecision.isNotBlank() && llmDecision.length > 8) {
                conversationHistory.add(userInput to llmDecision)
                trimHistory()
                emit(AgentState.Completed(llmDecision, null))
                return@flow
            }
        }

        // 3. Hiçbir şey işe yaramadı
        emit(AgentState.Completed(
            "Şu anda ne yapacağımı bilemedim.\n" +
            "Şunları deneyebilirsin:\n" +
            "• \"Pil kaç\" — pil durumu\n" +
            "• \"Hava nasıl\" — hava durumu\n" +
            "• \"WhatsApp'ı aç\" — uygulama aç\n" +
            "• \"ping 8.8.8.8\" — ağ testi\n" +
            "• \"not al: toplantı 15:00\" — hatırlatıcı",
            null
        ))
    }.flowOn(Dispatchers.IO)

    // ── LLM Karar Verme ──

    private fun askLLM(userInput: String): String {
        // Geçmişi ekle
        val historyBlock = conversationHistory.takeLast(4).joinToString("\n") { (u, a) ->
            "Kullanıcı: $u\nDorina: $a"
        }

        val toolList = availableTools.joinToString("\n") {
            "  • ${it.name} - ${it.description} (args: ${it.args})"
        }

        val prompt = """
            $historyBlock
            
            Kullanıcı: $userInput
            
            Sen Dorina'sın. Kullanıcının isteğini anla ve en uygun aracı seç.
            
            ARAÇLAR:
            $toolList

            KURALLAR:
            - Sadece yukarıdaki araçlardan birini seç
            - Cevap SADECE JSON olmalı: {"tool": "...", "args": {...}}
            - Hiçbir araç uygun değilse normal Türkçe cevap ver
            
            CEVAP:
        """.trimIndent()

        // MediaPipe dene
        if (isInitialized && llmInference != null) {
            try {
                return llmInference?.generateResponse(prompt) ?: ""
            } catch (_: Exception) {}
        }

        // Ollama dene
        return queryOllama(prompt) ?: ""
    }

    // ── LLM ile Özetleme ──

    private fun summarizeWithLLM(
        userQuery: String,
        toolName: String,
        result: ToolResult,
        llmHint: String? = null
    ): String {
        if (result.success) {
            // LLM varsa sonucu doğal dile çevir
            val prompt = """
                Kullanıcı: $userQuery
                Araç: $toolName
                ${if (llmHint != null) "Seçim: $llmHint" else ""}
                Sonuç: ${result.result}
                
                Kullanıcıya sonucu kısa ve doğal Türkçe ile söyle. Aracın adını söyleme, direkt cevap ver.
            """.trimIndent()

            if (isInitialized && llmInference != null) {
                try {
                    val r = llmInference?.generateResponse(prompt) ?: ""
                    if (r.length > 10) return r
                } catch (_: Exception) {}
            }

            val ollamaR = queryOllama(prompt)
            if (ollamaR != null && ollamaR.length > 10) return ollamaR

            // LLM yoksa formatlı düz metin
            return formatToolResult(toolName, result)
        } else {
            return "❌ $toolName hatası: ${result.result}"
        }
    }

    private fun formatToolResult(toolName: String, result: ToolResult): String {
        return when (toolName) {
            "get_battery" -> result.result.replace("Mevcut batarya seviyesi: %", "Pil seviyen: %")
            "get_wifi_status" -> result.result.replace("Ağ Durumu:", "Bağlantı:")
            "device_info" -> result.result
            "terminal" -> {
                val lines = result.result.lines().filter { it.isNotBlank() }
                lines.joinToString("\n")
            }
            else -> result.result
        }
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
        return try {
            val url = URL(OLLAMA_URL)
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

    // ── JSON Ayrıştırıcı ──

    private fun parseJsonToolCall(response: String): Pair<String, Map<String, String>>? {
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
                            argsMap[key] = argsObj.optString(key, "")
                        }
                    }
                    return Pair(toolName, argsMap)
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun trimHistory() {
        while (conversationHistory.size > MAX_HISTORY) {
            conversationHistory.removeFirst()
        }
    }

    // ── Kural Motoru (gelişmiş) ──

    private fun simulateRuleFallback(userInput: String): String {
        val lower = userInput.lowercase().trim()
            .replace("[.!?,;]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

        // Hava durumu
        if (setOf("hava", "weather", "sıcaklık", "derece", "yağmur", "kar", "rüzgar", "bulut", "güneş").any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "curl -s 'wttr.in/Istanbul?format=%C+%t+%w'"}}"""
        }

        // Ping
        if ("ping" in lower && "pingle" !in lower) {
            val target = when {
                "8.8.8.8" in lower -> "8.8.8.8"
                "google" in lower -> "google.com"
                else -> "1.1.1.1"
            }
            return """{"tool": "terminal", "args": {"command": "ping -c 4 $target"}}"""
        }

        // Tarih/Saat
        if (setOf("tarih", "saat", "zaman", "gün", "date", "time", "bugün günlerden").any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "date '+%A, %d %B %Y - %H:%M:%S'"}}"""
        }

        // Depolama
        if (setOf("depolama", "hafıza", "disk", "sd kart", "boş alan", "kullanılan", "storage", "gb", "bayt").any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "df -h /sdcard /data 2>/dev/null; echo '---RAM---'; free -h 2>/dev/null"}}"""
        }

        // CPU
        if (setOf("cpu", "işlemci", "processor", "çekirdek").any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "cat /proc/cpuinfo 2>/dev/null | grep -E 'processor|model name|Hardware' | head -10"}}"""
        }

        // RAM
        if (setOf("ram", "bellek", "memory").any { it in lower }) {
            return """{"tool": "device_info", "args": {}}"""
        }

        // Ağ / IP
        if (setOf("ağ", "network", "ip", "mac", "bağlantı durumu", "modem", "router").any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "ip addr show 2>/dev/null | grep -E 'inet|link' || ifconfig"}}"""
        }

        // Kimler bağlı
        if (setOf("kimler", "kim bağlı", "oturum", "online", "who").any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "who 2>/dev/null; echo '---PROC---'; ps -A 2>/dev/null | head -10"}}"""
        }

        // Uygulama listesi
        if (setOf("uygulama listele", "yüklü uygulama", "paket listele", "hangi uygulama", "program listele").any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "pm list packages 2>/dev/null | awk -F: '{print \$2}' | sort | head -40"}}"""
        }

        // Dosya listele
        if (setOf("dosya listele", "klasör", "dizin", "göster", "neler var", "ls").any { it in lower }) {
            val path = if ("indir" in lower || "download" in lower) "/sdcard/Download" else "/sdcard"
            return """{"tool": "terminal", "args": {"command": "ls -la '$path' 2>/dev/null | head -30"}}"""
        }

        // Uptime
        if (setOf("uptime", "çalışma süresi", "açık kalma", "ne zamandır").any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "uptime"}}"""
        }

        // ── Özel Araçlar ──

        // Pil
        if (setOf("şarj", "pil", "batarya", "battery", "yüzde", "dolum", "enerji", "charge").any { it in lower }) {
            return """{"tool": "get_battery", "args": {}}"""
        }

        // Cihaz bilgisi
        if (setOf("cihaz", "model", "telefon", "sistem", "özellik", "bilgi", "info", "donanım", "hardware", "android").any { it in lower }) {
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

        // Not oku
        if (setOf("notlar", "notlarım", "hafıza", "listele").any { it in lower }) {
            return """{"tool": "read_notes", "args": {}}"""
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

        // Uygulama Açma
        val acmaRegex = Regex("""(.{2,25})['’]?(i|ı|yi|yı|e|a|ye|ya|ü|u|yu|yü)?\s*(aç|başlat|çalıştır|gir|start|launch|open)\b""", RegexOption.IGNORE_CASE)
        val match = acmaRegex.find(lower)
        if (match != null) {
            var appName = match.groupValues[1].trim()
            appName = appName.replace(Regex("""['’]?(i|ı|yi|yı|e|a|ye|ya|u|ü|yu|yü)$""", RegexOption.IGNORE_CASE), "").trim()
            if (appName.length in 2..25) {
                return """{"tool": "open_app", "args": {"app_name": "$appName"}}"""
            }
        }

        // Alternatif uygulama açma
        if (setOf("aç", "açar", "açılsın", "başlat", "çalıştır", "gir", "girelim").any { it in lower }) {
            val words = lower.split(" ")
            val acIndex = words.indexOfFirst { it in setOf("aç", "açar", "açılsın", "açsana", "başlat", "başlatır", "çalıştır", "gir", "girelim") }
            if (acIndex >= 1) {
                var appName = words.subList(0, acIndex).joinToString(" ")
                appName = appName.replace(Regex("""['’]?(i|ı|yi|yı|e|a|ye|ya|u|ü|yu|yü)$""", RegexOption.IGNORE_CASE), "").trim()
                if (appName.length in 2..25) {
                    return """{"tool": "open_app", "args": {"app_name": "$appName"}}"""
                }
            }
        }

        return ""
    }
}

sealed class AgentState {
    data class Thinking(val message: String) : AgentState()
    data class ExecutingTool(val toolName: String, val args: Map<String, String>) : AgentState()
    data class Completed(val answer: String, val toolResult: ToolResult?) : AgentState()
    data class Error(val errorMessage: String) : AgentState()
}
