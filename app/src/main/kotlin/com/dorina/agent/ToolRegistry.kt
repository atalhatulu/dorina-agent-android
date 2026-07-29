package com.dorina.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ToolRegistry {

    private val ALLOWED_COMMANDS = listOf("ping", "uptime", "date", "echo", "whoami", "uname", "netstat", "ps")

    suspend fun executeTool(context: Context, toolName: String, args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        try {
            when (toolName.lowercase()) {
                "device_info" -> {
                    val battery = getBatteryLevel(context)
                    val storageMB = getAvailableStorageMB()
                    val wifiInfo = getWifiStatus(context)
                    val info = """
                        Cihaz Model: ${Build.MANUFACTURER} ${Build.MODEL}
                        Android Sürümü: API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})
                        Batarya Seviyesi: %$battery
                        Boş Depolama: ${storageMB / 1024} GB ($storageMB MB)
                        Bağlantı: $wifiInfo
                    """.trimIndent()
                    ToolResult(toolName, true, info)
                }

                "get_battery" -> {
                    val battery = getBatteryLevel(context)
                    ToolResult(toolName, true, "Mevcut batarya seviyesi: %$battery")
                }

                "get_wifi_status" -> {
                    val status = getWifiStatus(context)
                    ToolResult(toolName, true, "Ağ Durumu: $status")
                }

                "run_safe_command" -> {
                    val rawCmd = args["command"] ?: args["cmd"] ?: ""
                    if (rawCmd.isBlank()) {
                        return@withContext ToolResult(toolName, false, "Hata: Komut parametresi boş.")
                    }

                    val firstWord = rawCmd.trim().split("\\s+".toRegex()).firstOrNull() ?: ""
                    if (firstWord !in ALLOWED_COMMANDS) {
                        return@withContext ToolResult(
                            toolName,
                            false,
                            "Güvenlik Engeli: '$firstWord' komutuna izin yok. İzin verilen komutlar: $ALLOWED_COMMANDS"
                        )
                    }

                    val process = Runtime.getRuntime().exec(rawCmd)
                    val output = process.inputStream.bufferedReader().readText()
                    val error = process.errorStream.bufferedReader().readText()
                    process.waitFor()

                    val resultText = if (output.isNotBlank()) output else error
                    ToolResult(toolName, true, resultText.trim())
                }

                "read_file" -> {
                    val fileName = args["file_name"] ?: args["path"] ?: ""
                    if (fileName.isBlank()) {
                        return@withContext ToolResult(toolName, false, "Hata: Dosya adı belirtilmedi.")
                    }

                    val appFilesDir = context.getExternalFilesDir(null) ?: context.filesDir
                    val targetFile = File(appFilesDir, fileName)

                    if (!targetFile.exists()) {
                        ToolResult(toolName, false, "Dosya bulunamadı: ${targetFile.name}")
                    } else {
                        val content = targetFile.readText()
                        ToolResult(toolName, true, "Dosya İçeriği (${targetFile.name}):\n$content")
                    }
                }

                "write_note" -> {
                    val noteText = args["text"] ?: args["content"] ?: ""
                    if (noteText.isBlank()) {
                        return@withContext ToolResult(toolName, false, "Hata: Not içeriği boş.")
                    }

                    val appFilesDir = context.getExternalFilesDir(null) ?: context.filesDir
                    val notesFile = File(appFilesDir, "dorina_memory_notes.txt")
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    notesFile.appendText("[$timestamp] $noteText\n")

                    ToolResult(toolName, true, "Not başarıyla hafızaya kaydedildi: '$noteText'")
                }

                "read_notes" -> {
                    val appFilesDir = context.getExternalFilesDir(null) ?: context.filesDir
                    val notesFile = File(appFilesDir, "dorina_memory_notes.txt")

                    if (!notesFile.exists() || notesFile.length() == 0L) {
                        ToolResult(toolName, true, "Henüz kaydedilmiş not bulunmuyor.")
                    } else {
                        val content = notesFile.readText()
                        ToolResult(toolName, true, "Kaydedilen Notlar:\n$content")
                    }
                }

                else -> ToolResult(toolName, false, "Bilinmeyen araç: $toolName")
            }
        } catch (e: Exception) {
            ToolResult(toolName, false, "Araç çalıştırma hatası: ${e.localizedMessage}")
        }
    }

    private fun getBatteryLevel(context: Context): Int {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else -1
    }

    private fun getAvailableStorageMB(): Long {
        val path = Environment.getDataDirectory()
        val stat = StatFs(path.path)
        val blockSize = stat.blockSizeLong
        val availableBlocks = stat.availableBlocksLong
        return (availableBlocks * blockSize) / (1024 * 1024)
    }

    private fun getWifiStatus(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "Bağlantı Yok"
        val caps = cm.getNetworkCapabilities(network) ?: return "Bilinmiyor"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi (Aktif)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobil Veri (Aktif)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet (Aktif)"
            else -> "Bağlı"
        }
    }
}
