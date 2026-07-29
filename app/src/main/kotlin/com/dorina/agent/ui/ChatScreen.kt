package com.dorina.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val messages by viewModel.messages.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val agentStatusText by viewModel.agentStatusText.collectAsState()
    val isTtsEnabled by viewModel.isTtsEnabled.collectAsState()

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E676))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Dorina Agent", color = Color.White, fontSize = 18.sp)
                        }
                        Text(
                            text = "S24 Ultra • Local AI Engine",
                            color = Color(0xFFAAAAAA),
                            fontSize = 11.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleTts() }) {
                        Text(if (isTtsEnabled) "🔊" else "🔇", fontSize = 20.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF121212)
                )
            )
        },
        containerColor = Color(0xFF0A0A0C)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {

            // Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                items(messages, key = { it.id }) { msg ->
                    ChatMessageBubble(msg)
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

            // Agent Status Banner
            AnimatedVisibility(
                visible = agentStatusText != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color(0xFF8E44AD),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = agentStatusText ?: "",
                            color = Color(0xFFE0E0E0),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Input Bar
            Surface(
                color = Color(0xFF16161A),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Dorina'ya sorun...", color = Color.Gray) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF22222A),
                            unfocusedContainerColor = Color(0xFF1A1A20),
                            focusedBorderColor = Color(0xFF8E44AD),
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    if (textInput.isBlank()) {
                        IconButton(
                            onClick = { viewModel.toggleVoiceListening() },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isListening) Color(0xFFE74C3C) else Color(0xFF8E44AD)
                                )
                        ) {
                            Text(if (isListening) "🎙️" else "🎤", fontSize = 20.sp)
                        }
                    } else {
                        IconButton(
                            onClick = {
                                val query = textInput
                                textInput = ""
                                viewModel.processQuery(query)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF8E44AD))
                        ) {
                            Text("➔", color = Color.White, fontSize = 20.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessageBubble(message: ChatMessage) {
    val isUser = message.sender == Sender.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isUser) {
                        Brush.horizontalGradient(listOf(Color(0xFF6C5CE7), Color(0xFF8E44AD)))
                    } else {
                        Brush.horizontalGradient(listOf(Color(0xFF25252E), Color(0xFF1E1E26)))
                    }
                )
                .padding(14.dp)
        ) {
            Column {
                Text(
                    text = message.text,
                    color = Color.White,
                    fontSize = 15.sp
                )

                message.toolResult?.let { tool ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFF121216),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "🛠️ Executed: ${tool.toolName}",
                                color = Color(0xFF00E676),
                                fontSize = 11.sp
                            )
                            Text(
                                text = tool.result,
                                color = Color(0xFFCCCCCC),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
