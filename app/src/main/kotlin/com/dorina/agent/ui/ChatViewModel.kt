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

        // Hoş geldin mesajı
        _messages.value = listOf(
            ChatMessage(
                sender = Sender.AGENT,
                text = "Merhaba! Ben Dorina. Yerel AI Ajanınız hazır. Nasıl yardımcı olabilirim?"
            )
        )
    }

    fun processQuery(query: String) {
        if (query.isBlank()) return

        val userMsg = ChatMessage(sender = Sender.USER, text = query)
        _messages.value = _messages.value + userMsg

        viewModelScope.launch {
            agentManager.processQuery(query).collect { state ->
                when (state) {
                    is AgentState.Thinking -> {
                        _agentStatusText.value = state.message
                    }
                    is AgentState.ExecutingTool -> {
                        _agentStatusText.value = "Araç çalıştırılıyor: ${state.toolName}..."
                    }
                    is AgentState.Completed -> {
                        _agentStatusText.value = null
                        val agentMsg = ChatMessage(
                            sender = Sender.AGENT,
                            text = state.answer,
                            toolResult = state.toolResult
                        )
                        _messages.value = _messages.value + agentMsg

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

    override fun onCleared() {
        super.onCleared()
        speechManager?.destroy()
    }
}
