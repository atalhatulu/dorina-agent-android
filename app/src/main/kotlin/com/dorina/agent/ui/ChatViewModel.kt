package com.dorina.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dorina.agent.AgentState
import com.dorina.agent.LocalAgentManager
import com.dorina.agent.ToolResult
import com.dorina.agent.speech.SpeechManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: Sender,
    val text: String,
    val toolResult: ToolResult? = null,
    val timestamp: Long = System.currentTimeMillis()
)

enum class Sender { USER, AGENT, SYSTEM }

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val agentManager = LocalAgentManager(application)
    private val memoryFile = File(application.getExternalFilesDir(null), "chat_history.json")

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _agentStatusText = MutableStateFlow<String?>(null)
    val agentStatusText: StateFlow<String?> = _agentStatusText.asStateFlow()

    private val _isTtsEnabled = MutableStateFlow(true)
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    private var speechManager: SpeechManager? = null

    init {
        // Geçmiş konuşmaları yükle
        loadHistory()

        if (_messages.value.isEmpty()) {
            _messages.value = listOf(
                ChatMessage(
                    sender = Sender.AGENT,
                    text = "Merhaba! Ben Dorina. Yerel AI Ajanınız hazır.\n" +
                           "AI Motoru: ${agentManager.activeEngineName}\n\n" +
                           "Sana nasıl yardımcı olabilirim?\n" +
                           "Örn: pil kaç, hava nasıl, instagram'ı aç, ping at, not al"
                )
            )
            saveHistory()
        }

        speechManager = SpeechManager(
            context = application,
            onSpeechResult = { text ->
                _isListening.value = false
                processQuery(text)
            },
            onError = { error ->
                _isListening.value = false
                _agentStatusText.value = error
            }
        )
    }

    fun processQuery(query: String) {
        if (query.isBlank()) return

        val userMsg = ChatMessage(sender = Sender.USER, text = query)
        _messages.value = _messages.value + userMsg
        saveHistory()

        viewModelScope.launch {
            agentManager.processQuery(query).collect { state ->
                when (state) {
                    is AgentState.Thinking -> {
                        _agentStatusText.value = state.message
                    }
                    is AgentState.ExecutingTool -> {
                        _agentStatusText.value = "🔧 ${state.toolName} çalıştırılıyor..."
                    }
                    is AgentState.Completed -> {
                        _agentStatusText.value = null
                        val agentMsg = ChatMessage(
                            sender = Sender.AGENT,
                            text = state.answer,
                            toolResult = state.toolResult
                        )
                        _messages.value = _messages.value + agentMsg
                        saveHistory()

                        if (_isTtsEnabled.value) {
                            speechManager?.speak(state.answer)
                        }
                    }
                    is AgentState.Error -> {
                        _agentStatusText.value = null
                        _messages.value = _messages.value + ChatMessage(
                            sender = Sender.SYSTEM,
                            text = "Hata: ${state.errorMessage}"
                        )
                        saveHistory()
                    }
                }
            }
        }
    }

    fun toggleVoiceListening() {
        if (_isListening.value) {
            speechManager?.stopListening()
            _isListening.value = false
        } else {
            _isListening.value = true
            speechManager?.startListening()
        }
    }

    fun toggleTts() {
        _isTtsEnabled.value = !_isTtsEnabled.value
    }

    fun clearHistory() {
        _messages.value = emptyList()
        memoryFile.delete()
        _messages.value = listOf(
            ChatMessage(
                sender = Sender.AGENT,
                text = "Hafıza temizlendi. Yeni bir konuşmaya başlayalım!"
            )
        )
        saveHistory()
    }

    // ── Kalıcı Hafıza (dosyaya kaydet/yükle) ──

    private fun saveHistory() {
        try {
            val jsonArr = JSONArray()
            for (msg in _messages.value) {
                val obj = JSONObject().apply {
                    put("id", msg.id)
                    put("sender", msg.sender.name)
                    put("text", msg.text)
                    put("timestamp", msg.timestamp)
                    msg.toolResult?.let {
                        put("toolResult", JSONObject().apply {
                            put("toolName", it.toolName)
                            put("success", it.success)
                            put("result", it.result)
                        })
                    }
                }
                jsonArr.put(obj)
            }
            memoryFile.writeText(jsonArr.toString(2))
        } catch (e: Exception) {
            // sessiz geç
        }
    }

    private fun loadHistory() {
        try {
            if (!memoryFile.exists()) return
            val jsonStr = memoryFile.readText()
            val jsonArr = JSONArray(jsonStr)
            val messages = mutableListOf<ChatMessage>()
            for (i in 0 until jsonArr.length()) {
                val obj = jsonArr.getJSONObject(i)
                val toolResult = if (obj.has("toolResult")) {
                    val t = obj.getJSONObject("toolResult")
                    ToolResult(t.getString("toolName"), t.getBoolean("success"), t.getString("result"))
                } else null
                messages.add(ChatMessage(
                    id = obj.getString("id"),
                    sender = Sender.valueOf(obj.getString("sender")),
                    text = obj.getString("text"),
                    toolResult = toolResult,
                    timestamp = obj.optLong("timestamp", 0L)
                ))
            }
            if (messages.isNotEmpty()) {
                _messages.value = messages
            }
        } catch (e: Exception) {
            // bozulursa temizle
            memoryFile.delete()
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveHistory()
        speechManager?.destroy()
    }
}
