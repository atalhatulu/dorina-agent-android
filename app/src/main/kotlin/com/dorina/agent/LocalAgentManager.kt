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
        Sen Dorina'sın, cihazda çalışan çok yetenekli, akıllı ve otonom bir yerel yapay zeka asistanısın.
        Görevin, kullanıcının isteklerini anlamak ve gerekirse aşağıdaki araçları kullanarak onlara yardımcı olmaktır.

        ## KULLANILABİLİR ARAÇLAR

        1. **terminal** — Shell komutu çalıştırır. Her türlü sistem bilgisi, dosya işlemi, ağ kontrolü için kullanılır.
           Args: {"command": "komut satırı", "timeout": 30}
           Örnek: {"tool": "terminal", "args": {"command": "uname -a && uptime"}}
           Örnek: {"tool": "terminal", "args": {"command": "dumpsys battery"}}
           Örnek: {"tool": "terminal", "args": {"command": "ls -la /sdcard/Download/"}}

        2. **device_info** — Cihazın RAM, depolama, CPU, pil ve ağ bilgilerini getirir.
           Args: {}
           Örnek: {"tool": "device_info", "args": {}}

        3. **get_battery** — Telefonun pil (şarj) yüzdesini getirir.
           Args: {}
           Örnek: {"tool": "get_battery", "args": {}}

        4. **get_wifi_status** — İnternet/Wi-Fi bağlantı durumunu kontrol eder.
           Args: {}
           Örnek: {"tool": "get_wifi_status", "args": {}}

        5. **read_file** — Uygulama klasöründen dosya okur.
           Args: {"file_name": "dosya_adı.txt"}
           Örnek: {"tool": "read_file", "args": {"file_name": "notes.txt"}}

        6. **write_note** — Hafızaya yeni bir not kaydeder.
           Args: {"text": "not içeriği"}
           Örnek: {"tool": "write_note", "args": {"text": "Toplantı saat 14:00"}}

        7. **read_notes** — Kaydedilen tüm notları okur.
           Args: {}
           Örnek: {"tool": "read_notes", "args": {}}

        8. **open_camera** — Kamerayı açar.
           Args: {}
           Örnek: {"tool": "open_camera", "args": {}}

        9. **toggle_flash** — Flaş ışığını (fener) açar veya kapatır.
           Args: {"state": "on"} veya {"state": "off"}
           Örnek: {"tool": "toggle_flash", "args": {"state": "on"}}

        10. **open_app** — Yüklü bir uygulamayı açar.
            Args: {"app_name": "Uygulama Adı"}
            Örnek: {"tool": "open_app", "args": {"app_name": "WhatsApp"}}

        ## ÖNEMLİ KURALLAR

        - Bir araç kullanman gerekiyorsa, SADECE aşağıdaki gibi ham JSON çıktısı ver, başka hiçbir şey yazma:
          {"tool": "araç_adı", "args": {...}}

        - Birden fazla araç gerekiyorsa, ilk aracı JSON olarak döndür. Sonuç sana geri geldiğinde sıradaki aracı çağır.

        - Araç kullanman gerekmiyorsa (sohbet, soru-cevap), doğrudan doğal ve samimi Türkçe ile cevap ver. Asla JSON formatı kullanma.

        - terminal aracı ile neredeyse her şeyi yapabilirsin: dosyaları oku, sistem bilgisi al, ağ durumunu kontrol et, uygulama listesini görüntüle.
          Örnek: "bugün hava nasıl" → {"tool": "terminal", "args": {"command": "curl -s wttr.in/Istanbul?format=3"}}
          Örnek: "depolama ne kadar boş" → {"tool": "terminal", "args": {"command": "df -h /sdcard"}}
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

        val fullPrompt = "$SYSTEM_PROMPT\n\nKullanıcı: $userInput\nDorina:"

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
                Kullanıcı Sorgusu: $userInput
                Çalıştırılan Araç: $toolName
                Araç Çıktısı: ${toolResult.result}

                Lütfen sonucu kullanıcıya güzel bir dille Türkçe özetle. Eğer işlem başarısız olduysa açıkla.
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
            // Terminal / shell komutları
            lower.contains("ping") -> {
                val target = when {
                    "8.8.8.8" in lower -> "8.8.8.8"
                    "google" in lower -> "google.com"
                    else -> "1.1.1.1"
                }
                """{"tool": "terminal", "args": {"command": "ping -c 4 $target"}}"""
            }
            lower.contains("tarih") || lower.contains("saat") || lower.contains("zaman") -> {
                """{"tool": "terminal", "args": {"command": "date '+%Y-%m-%d %H:%M:%S'"}}"""
            }
            lower.contains("unut") && (lower.contains("ma") || lower.contains("me")) -> {
                """{"tool": "terminal", "args": {"command": "uptime"}}"""
            }
            lower.contains("kim") && (lower.contains("login") || lower.contains("bağlı") || lower.contains("online")) -> {
                """{"tool": "terminal", "args": {"command": "who; echo '---'; ps -A 2>/dev/null | head -20"}}"""
            }
            lower.contains("depolama") || lower.contains("hafıza") || lower.contains("disk") || lower.contains("sd kart") -> {
                """{"tool": "terminal", "args": {"command": "df -h /sdcard /data 2>/dev/null"}}"""
            }
            lower.contains("işlem") || lower.contains("cpu") || lower.contains("processor") -> {
                """{"tool": "terminal", "args": {"command": "cat /proc/cpuinfo 2>/dev/null | head -20"}}"""
            }
            lower.contains("ram") || lower.contains("bellek") || lower.contains("memory") -> {
                """{"tool": "terminal", "args": {"command": "free -h 2>/dev/null || cat /proc/meminfo 2>/dev/null | head -10"}}"""
            }
            lower.contains("ağ") || lower.contains("network") || lower.contains("ip") -> {
                """{"tool": "terminal", "args": {"command": "ip addr show 2>/dev/null || ifconfig 2>/dev/null"}}"""
            }
            lower.contains("uygulama") || lower.contains("yüklü") || lower.contains("liste") -> {
                """{"tool": "terminal", "args": {"command": "pm list packages 2>/dev/null | head -30"}}"""
            }
            lower.contains("dosya") && (lower.contains("göster") || lower.contains("listele") || lower.contains("ls")) -> {
                """{"tool": "terminal", "args": {"command": "ls -la /sdcard/ 2>/dev/null"}}"""
            }

            // Şarj / pil
            lower.contains("şarj") || lower.contains("pil") || lower.contains("batarya") || lower.contains("battery") -> {
                """{"tool": "get_battery", "args": {}}"""
            }

            // Cihaz bilgisi
            lower.contains("sistem") || lower.contains("cihaz") || lower.contains("model") || lower.matches(Regex(".*(bilgi|info|özellik).*")) -> {
                """{"tool": "device_info", "args": {}}"""
            }

            // Wi-Fi / internet
            lower.contains("internet") || lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("bağlantı") -> {
                """{"tool": "get_wifi_status", "args": {}}"""
            }

            // Not alma
            lower.contains("not et") || lower.contains("hatırla") || lower.contains("kaydet") || lower.contains("not al") -> {
                """{"tool": "write_note", "args": {"text": "$userInput"}}"""
            }

            // Notları oku
            lower.contains("notlar") || lower.contains("hafıza") || lower.contains("notlarım") -> {
                """{"tool": "read_notes", "args": {}}"""
            }

            // Kamera
            lower.contains("kamera") || lower.contains("fotoğraf") || lower.contains("çek") -> {
                """{"tool": "open_camera", "args": {}}"""
            }

            // Flaş
            lower.contains("flaş") || lower.contains("fener") || lower.contains("ışık") || lower.contains("flash") -> {
                val state = if (lower.contains("kapat") || lower.contains("söndür")) "off" else "on"
                """{"tool": "toggle_flash", "args": {"state": "$state"}}"""
            }

            // Uygulama açma
            lower.contains("aç") && !lower.contains("flaş") && !lower.contains("fener") && !lower.contains("kamera") && !lower.contains("ışık") -> {
                val words = lower.split(" ")
                val appNameIndex = words.indexOf("aç") - 1
                val appName = if (appNameIndex >= 0) words[appNameIndex] else "Bilinmeyen"
                """{"tool": "open_app", "args": {"app_name": "$appName"}}"""
            }

            // Varsayılan
            else -> "Merhaba! Ben Dorina. Yerel AI Ajanınız hizmetinizde. Nasıl yardımcı olabilirim?"
        }
    }
}

sealed class AgentState {
    data class Thinking(val message: String) : AgentState()
    data class ExecutingTool(val toolName: String, val args: Map<String, String>) : AgentState()
    data class Completed(val answer: String, val toolResult: ToolResult?) : AgentState()
    data class Error(val errorMessage: String) : AgentState()
}
