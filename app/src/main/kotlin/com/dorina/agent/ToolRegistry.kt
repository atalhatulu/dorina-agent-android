package com.dorina.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object ToolRegistry {

    // ── Destructive command patterns (from dorina-agent/security.py) ──
    private val DESTRUCTIVE_PATTERNS = listOf(
        "rm -rf /", "rm -rf /*", "rm -rf --no-preserve-root",
        "mkfs", "dd if=", "> /dev/sd", "> /dev/block",
        ":(){ :|:& };:",  // fork bomb
        "chmod 777 /", "chown -R",
        "mv / /dev/null",
        "wget http://", "curl http://", "bash <(", "sh <(",
    )

    private val INDIRECT_DESTRUCTIVE = listOf(
        Regex("""(python|perl|ruby|node)\s+.*(shutil\.rmtree|os\.remove|os\.unlink|File\.delete)"""),
        Regex("""(python|perl|ruby|node)\s+.*(rm\s+-rf|exec\(|system\(|subprocess)"""),
        Regex("""shutil\.rmtree\('"]['"]/['"]['"]"""),
        Regex("""os\.system\('"]['"]rm\s+-rf"""),
    )

    private fun isDestructive(command: String): Boolean {
        val cmdLower = command.lowercase().trim()

        for (pattern in DESTRUCTIVE_PATTERNS) {
            if (pattern in cmdLower) return true
        }

        for (regex in INDIRECT_DESTRUCTIVE) {
            if (regex.containsMatchIn(cmdLower)) return true
        }

        return false
    }

    suspend fun executeTool(context: Context, toolName: String, args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        try {
            when (toolName.lowercase()) {
                "terminal" -> {
                    val rawCmd = args["command"] ?: args["cmd"] ?: ""
                    if (rawCmd.isBlank()) {
                        return@withContext ToolResult(toolName, false, "Hata: Komut parametresi boş.")
                    }

                    if (isDestructive(rawCmd)) {
                        return@withContext ToolResult(
                            toolName, false,
                            "Güvenlik Engeli: Bu komut potansiyel olarak tehlikeli algılandı ve engellendi.\n" +
                            "Komut: $rawCmd"
                        )
                    }

                    val timeoutSec = args["timeout"]?.toIntOrNull() ?: 30

                    try {
                        val result = withTimeout(timeoutSec * 1000L) {
                            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", rawCmd))
                            val output = process.inputStream.bufferedReader().readText()
                            val error = process.errorStream.bufferedReader().readText()
                            val exited = process.waitFor(30, TimeUnit.SECONDS)
                            val exitCode = if (exited) process.exitValue() else -1

                            buildString {
                                if (output.isNotBlank()) append(output.trim())
                                if (error.isNotBlank()) {
                                    if (isNotEmpty()) append("\n")
                                    append("STDERR: ").append(error.trim())
                                }
                                if (exitCode != 0) {
                                    append("\n(Exit code: $exitCode)")
                                }
                            }.ifBlank { "Komut çalıştı (exit: $exitCode)" }
                        }
                        ToolResult(toolName, true, result)
                    } catch (e: TimeoutCancellationException) {
                        ToolResult(toolName, false, "Komut ${timeoutSec}s içinde tamamlanamadı — zaman aşımı.")
                    }
                }

                "device_info" -> {
                    val battery = getBatteryLevel(context)
                    val storageMB = getAvailableStorageMB()
                    val wifiInfo = getWifiStatus(context)
                    val info = buildString {
                        appendLine("Cihaz: ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine("Android: API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                        appendLine("Pil: %$battery")
                        appendLine("Boş Depolama: ${storageMB / 1024} GB ($storageMB MB)")
                        append("Bağlantı: $wifiInfo")
                    }
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

                "open_camera" -> {
                    val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        ToolResult(toolName, true, "Kamera başarıyla açıldı.")
                    } else {
                        ToolResult(toolName, false, "Kamera uygulaması bulunamadı.")
                    }
                }

                "toggle_flash" -> {
                    val state = args["state"] ?: "on"
                    val turnOn = state.lowercase() == "on"
                    try {
                        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        val cameraId = cameraManager.cameraIdList[0]
                        cameraManager.setTorchMode(cameraId, turnOn)
                        ToolResult(toolName, true, if (turnOn) "Flaş başarıyla açıldı." else "Flaş başarıyla kapatıldı.")
                    } catch (e: Exception) {
                        ToolResult(toolName, false, "Flaş kontrol edilemedi: ${e.message}")
                    }
                }

                "open_app" -> {
                    val appName = args["app_name"] ?: ""
                    if (appName.isBlank()) {
                        return@withContext ToolResult(toolName, false, "Lütfen açmak istediğiniz uygulamanın adını belirtin.")
                    }

                    // Türkçe ek temizleyici (örn: "instagram'ı" → "instagram", "whatsapp'a" → "whatsapp")
                    val cleanAppName = appName.replace(
                        Regex("""['’]?(i|ı|yi|yı|e|a|ye|ya|u|ü|yu|yü|nin|nın|nın|nİn)$""", RegexOption.IGNORE_CASE),
                        ""
                    ).trim()

                    val pm = context.packageManager
                    val packages = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                    var found = false

                    for (app in packages) {
                        val name = pm.getApplicationLabel(app).toString()
                        if (name.contains(cleanAppName, ignoreCase = true)) {
                            val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                                found = true
                                return@withContext ToolResult(toolName, true, "$name başarıyla açıldı.")
                            }
                        }
                    }
                    if (!found) {
                        ToolResult(toolName, false, "$cleanAppName isimli uygulama bulunamadı.")
                    } else {
                        ToolResult(toolName, false, "Bilinmeyen bir hata oluştu.")
                    }
                }

                else -> ToolResult(toolName, false, "Bilinmeyen araç: $toolName")
            }
        } catch (e: Exception) {
            ToolResult(toolName, false, "Araç çalıştırma hatası: ${e.localizedMessage ?: e.message ?: "Bilinmeyen hata"}")
        }
    }

    private fun getBatteryLevel(context: Context): Int {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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
