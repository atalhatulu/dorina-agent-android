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

        if (checkOllamaConnection()) {
            activeEngineName = "Ollama Local (gemma:2b)"
        } else {
            activeEngineName = "Kural Motoru (Offline)"
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

    // ── Ana Sorgu İşleme ──

    suspend fun processQuery(userInput: String): Flow<AgentState> = flow {
        emit(AgentState.Thinking("Sorgu işleniyor..."))

        // 1. ÖNCE kural motoru dene (hızlı ve güvenilir)
        val ruleResult = simulateRuleFallback(userInput)
        val ruleToolCall = parseJsonToolCall(ruleResult)

        if (ruleToolCall != null) {
            // Kural motoru bir tool buldu → çalıştır
            val (toolName, args) = ruleToolCall
            emit(AgentState.ExecutingTool(toolName, args))
            val toolResult = ToolRegistry.executeTool(context, toolName, args)
            emit(AgentState.Thinking("Sonuç işleniyor..."))

            // LLM varsa sonucu özetlet, yoksa direkt göster
            val answer = summarizeWithLLM(userInput, toolName, toolResult)
            emit(AgentState.Completed(answer, toolResult))
            return@flow
        }

        // 2. Kural motoru bir şey bulamadı → LLM'e sor (varsa)
        emit(AgentState.Thinking("Anlamaya çalışıyorum..."))

        if (isInitialized && llmInference != null) {
            // MediaPipe ile basit sınıflandırma
            val llmResponse = try {
                llmInference?.generateResponse(buildClassifyPrompt(userInput)) ?: ""
            } catch (e: Exception) {
                queryOllamaOrFallback(userInput)
            }

            val llmToolCall = parseJsonToolCall(llmResponse)
            if (llmToolCall != null) {
                val (toolName, args) = llmToolCall
                emit(AgentState.ExecutingTool(toolName, args))
                val toolResult = ToolRegistry.executeTool(context, toolName, args)
                val answer = summarizeWithLLM(userInput, toolName, toolResult, llmResponse)
                emit(AgentState.Completed(answer, toolResult))
                return@flow
            }

            // LLM JSON döndürmedi ama anlamlı bir cevap verdi mi?
            if (llmResponse.isNotBlank() && llmResponse.length > 10) {
                emit(AgentState.Completed(llmResponse, null))
                return@flow
            }
        }

        // 3. Ollama dene
        val ollamaResponse = queryOllama(userInput)
        if (ollamaResponse != null) {
            val ollamaToolCall = parseJsonToolCall(ollamaResponse)
            if (ollamaToolCall != null) {
                val (toolName, args) = ollamaToolCall
                emit(AgentState.ExecutingTool(toolName, args))
                val toolResult = ToolRegistry.executeTool(context, toolName, args)
                val answer = summarizeWithLLM(userInput, toolName, toolResult)
                emit(AgentState.Completed(answer, toolResult))
                return@flow
            }
            if (ollamaResponse.isNotBlank() && ollamaResponse.length > 10) {
                emit(AgentState.Completed(ollamaResponse, null))
                return@flow
            }
        }

        // 4. Hiçbir şey işe yaramadı
        emit(AgentState.Completed(
            "Anlayamadım. Ne yapmamı istersin? " +
            "Örnek: pil kaç, hava durumu, Instagram'ı aç, ping at, not al",
            null
        ))
    }.flowOn(Dispatchers.IO)

    // ── LLM Sınıflandırma Promptu (basit, JSON odaklı) ──

    private fun buildClassifyPrompt(userInput: String): String {
        return """
            Kullanıcı: $userInput

            Yukarıdaki isteğe göre uygun aracı seç ve SADECE JSON çıktısı ver.
            Eğer hiçbir araç uygun değilse, doğrudan Türkçe cevap ver.

            ARAÇLAR:
            {"tool": "get_battery", "args": {}} — pil/şarj/batarya soruları için
            {"tool": "get_wifi_status", "args": {}} — internet/wifi bağlantı soruları için
            {"tool": "device_info", "args": {}} — cihaz/model/sistem bilgisi için
            {"tool": "terminal", "args": {"command": "curl -s wttr.in/Istanbul?format=3"}} — hava durumu için
            {"tool": "terminal", "args": {"command": "..."}} — her türlü terminal komutu, ping, sistem bilgisi, dosya listesi
            {"tool": "open_camera", "args": {}} — kamerayı açmak için
            {"tool": "toggle_flash", "args": {"state": "on"}} — feneri yakmak için
            {"tool": "open_app", "args": {"app_name": "WhatsApp"}} — uygulama açmak için
            {"tool": "write_note", "args": {"text": "not"}} — not almak için
            {"tool": "read_notes", "args": {}} — notları okumak için
            {"tool": "read_file", "args": {"file_name": "dosya.txt"}} — dosya okumak için

            CEVAP:
        """.trimIndent()
    }

    // ── LLM ile Özetleme ──

    private fun summarizeWithLLM(
        userQuery: String,
        toolName: String,
        result: ToolResult,
        llmHint: String? = null
    ): String {
        val prompt = """
            Kullanıcı: $userQuery
            Araç: $toolName
            ${if (llmHint != null) "Ham Cevap: $llmHint" else ""}
            Sonuç: ${result.result}

            Kullanıcıya sonucu kısa ve doğal Türkçe ile söyle. Başarısızsa hatayı açıkla.
        """.trimIndent()

        if (isInitialized && llmInference != null) {
            try {
                val response = llmInference?.generateResponse(prompt) ?: ""
                if (response.isNotBlank() && response.length > 10) return response
            } catch (_: Exception) {}
        }

        // Ollama ile dene
        val ollamaResponse = queryOllama(prompt)
        if (ollamaResponse != null && ollamaResponse.length > 10) return ollamaResponse

        // LLM yoksa direkt sonucu göster
        return if (result.success) {
            "✅ $toolName:\n${result.result}"
        } else {
            "❌ $toolName hatası: ${result.result}"
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
        } catch (e: Exception) { false }
    }

    private fun queryOllama(prompt: String): String? {
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
                activeEngineName = "Ollama"
                responseJson.optString("response", null)
            } else null
        } catch (e: Exception) {
            Timber.w("Ollama error: ${e.message}")
            null
        }
    }

    private fun queryOllamaOrFallback(input: String): String {
        return queryOllama(buildClassifyPrompt(input)) ?: ""
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
                            argsMap[key] = argsObj.getString(key)
                        }
                    }
                    return Pair(toolName, argsMap)
                }
            }
            null
        } catch (e: Exception) { null }
    }

    // ── Gelişmiş Kural Motoru ──

    private fun simulateRuleFallback(userInput: String): String {
        val lower = userInput.lowercase().trim()
            .replace("[.!?,;]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

        // ── Terminal komutları ──

        if (lower.contains("ping") && !lower.contains("pingle") && !lower.contains("ping at")) {
            val target = when {
                "8.8.8.8" in lower -> "8.8.8.8"
                "google" in lower -> "google.com"
                "1.1.1.1" in lower -> "1.1.1.1"
                else -> "1.1.1.1"
            }
            return """{"tool": "terminal", "args": {"command": "ping -c 4 $target"}}"""
        }

        // Hava durumu
        val havaKelime = setOf("hava", "weather", "sıcaklık", "derece", "yağmur", "kar", "rüzgar", "bulut")
        if (havaKelime.any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "curl -s 'wttr.in/Istanbul?format=%C+%t+%w&m' 2>/dev/null"}}"""
        }

        // Tarih / saat
        val zamanKelime = setOf("tarih", "saat", "zaman", "gün", "ay", "yıl", "date", "time", "bugün günlerden")
        if (zamanKelime.any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "date '+%A, %d %B %Y - %H:%M:%S' 2>/dev/null || date"}}"""
        }

        // Depolama / disk
        val depolamaKelime = setOf("depolama", "hafıza", "disk", "sd kart", "boş alan", "kullanılan alan",
            "storage", "memory", "space", "gb")
        if (depolamaKelime.any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "df -h /sdcard /data 2>/dev/null; echo '---'; free -h 2>/dev/null"}}"""
        }

        // İşlemci / CPU
        if (lower.contains("cpu") || lower.contains("işlemci") || lower.contains("processor") || lower.contains("çekirdek")) {
            return """{"tool": "terminal", "args": {"command": "cat /proc/cpuinfo 2>/dev/null | grep -E 'processor|model name|Hardware' | head -10"}}"""
        }

        // RAM / bellek
        if (lower.contains("ram") || lower.contains("bellek") || lower.contains("memory")) {
            return """{"tool": "device_info", "args": {}}"""
        }

        // Ağ / IP
        val agKelime = setOf("ağ", "network", "ip", "mac adres", "ağ ayarları", "bağlantı durumu")
        if (agKelime.any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "ip addr show 2>/dev/null | grep -E 'inet|link' || ifconfig 2>/dev/null"}}"""
        }

        // Uygulama listesi
        val uygulamaKelime = setOf("uygulama listele", "yüklü uygulama", "paket listele", "hangi uygulama")
        if (uygulamaKelime.any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "pm list packages 2>/dev/null | awk -F: '{print \$2}' | sort | head -40"}}"""
        }

        // Dosya listele
        val dosyaKelime = setOf("dosya listele", "klasör", "dizin", "ls", "göster", "neler var")
        if (dosyaKelime.any { it in lower }) {
            val path = if ("indir" in lower || "download" in lower) "/sdcard/Download" else "/sdcard"
            return """{"tool": "terminal", "args": {"command": "ls -la '$path' 2>/dev/null | head -30"}}"""
        }

        // Kimler bağlı / login
        val kimlerKelime = setOf("kimler", "kim bağlı", "kim online", "oturum", "who")
        if (kimlerKelime.any { it in lower }) {
            return """{"tool": "terminal", "args": {"command": "who 2>/dev/null; echo '---'; ps -A 2>/dev/null | head -10"}}"""
        }

        // Sistem çalışma süresi
        if (lower.contains("uptime") || lower.contains("çalışma süresi") || lower.contains("ne zamandır açık") || lower.contains("açık kalma")) {
            return """{"tool": "terminal", "args": {"command": "uptime; cat /proc/uptime 2>/dev/null | awk '{print \$1/86400 \" gün\"}'"}}"""
        }

        // ── Özel araçlar ──

        // Pil / şarj (Genişletilmiş)
        val pilKelime = setOf("şarj", "pil", "batarya", "battery", "yüzde", "%", "charge", "power", "enerji", "dolum")
        if (pilKelime.any { it in lower }) {
            return """{"tool": "get_battery", "args": {}}"""
        }

        // Cihaz bilgisi
        val cihazKelime = setOf("cihaz", "model", "telefon", "sistem", "özellik", "bilgi", "info", "spec",
            "donanım", "hardware", "telefonun özellik", "android")
        if (cihazKelime.any { it in lower }) {
            return """{"tool": "device_info", "args": {}}"""
        }

        // Wi-Fi / internet
        val wifiKelime = setOf("wifi", "wi-fi", "internet", "bağlantı", "ağ durumu", "network", "modem")
        if (wifiKelime.any { it in lower }) {
            return """{"tool": "get_wifi_status", "args": {}}"""
        }

        // Not al
        val notAlKelime = setOf("not et", "hatırla", "kaydet", "not al", "not tut", "aklımda kalsın",
            "unutma", "yaz", "not defteri")
        if (notAlKelime.any { it in lower }) {
            return """{"tool": "write_note", "args": {"text": "$userInput"}}"""
        }

        // Notları oku
        val notOkuKelime = setOf("notlar", "notlarım", "notlarımı göster", "notlarımı oku", "hafıza", "listele")
        if (notOkuKelime.any { it in lower }) {
            return """{"tool": "read_notes", "args": {}}"""
        }

        // Kamera
        val kameraKelime = setOf("kamera", "fotoğraf", "çek", "selfie", "foto", "resim", "fotoğraf çek")
        if (kameraKelime.any { it in lower }) {
            return """{"tool": "open_camera", "args": {}}"""
        }

        // Flaş / fener
        val flasKelime = setOf("flaş", "fener", "ışık", "flash", "torch", "lamba", "el feneri")
        if (flasKelime.any { it in lower }) {
            val state = if (lower.contains("kapat") || lower.contains("söndür") || lower.contains("off")) "off" else "on"
            return """{"tool": "toggle_flash", "args": {"state": "$state"}}"""
        }

        // ── Uygulama Açma (gelişmiş) ──
        // "x'i aç", "x i aç", "x aç", "x başlat", "x çalıştır", "x e gir"
        val acmaRegex = Regex("""(.{2,30})['’]?(i|ı|yi|yı|e|a|ye|ya|ü|u|yu|yü)?\s*(aç|başlat|çalıştır|gir|start|launch|open)\b""", RegexOption.IGNORE_CASE)
        val match = acmaRegex.find(lower)
        if (match != null) {
            var appName = match.groupValues[1].trim()
            // Temizlik
            appName = appName.replace(Regex("""['’]?(i|ı|yi|yı|e|a|ye|ya|u|ü|yu|yü)$""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (appName.length >= 2 && appName.length <= 30) {
                return """{"tool": "open_app", "args": {"app_name": "$appName"}}"""
            }
        }
        // Alternatif: "aç" kelimesi içeren cümleler
        val acik = lower.contains("aç") || lower.contains("başlat") || lower.contains("çalıştır") || lower.contains("gir")
        if (acik) {
            val words = lower.split(" ")
            val acIndex = words.indexOfFirst { it in listOf("aç", "açar", "açılsın", "açıver", "açsana", "başlat", "başlatır", "çalıştır", "gir", "girelim") }
            if (acIndex >= 1) {
                var appName = words.subList(0, acIndex).joinToString(" ")
                appName = appName.replace(Regex("""['’]?(i|ı|yi|yı|e|a|ye|ya|u|ü|yu|yü)$""", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (appName.length >= 2 && appName.length <= 30) {
                    return """{"tool": "open_app", "args": {"app_name": "$appName"}}"""
                }
            }
        }

        // ── Tanımlanamadı ──
        return ""
    }
}

sealed class AgentState {
    data class Thinking(val message: String) : AgentState()
    data class ExecutingTool(val toolName: String, val args: Map<String, String>) : AgentState()
    data class Completed(val answer: String, val toolResult: ToolResult?) : AgentState()
    data class Error(val errorMessage: String) : AgentState()
}
