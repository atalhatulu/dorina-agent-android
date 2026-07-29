package com.dorina.agent

sealed class ToolRequest {
    object DeviceInfo : ToolRequest()
    data class SafeCommand(val command: String) : ToolRequest()
    data class ReadFile(val fileName: String) : ToolRequest()
    object GetBattery : ToolRequest()
}

data class ToolResult(
    val toolName: String,
    val success: Boolean,
    val result: String
)
