package com.dorina.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.dorina.agent.ui.ChatScreen
import com.dorina.agent.ui.ChatViewModel
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Timber.i("Audio recording permission granted.")
        } else {
            Timber.w("Audio recording permission denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.plant(Timber.DebugTree())

        checkAudioPermission()
        handleAssistIntent(intent)

        setContent {
            ChatScreen(viewModel = viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAssistIntent(intent)
    }

    private fun handleAssistIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_ASSIST) {
            Timber.i("Triggered via Assistant / Power Button gesture")
            viewModel.toggleVoiceListening()
        }
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
