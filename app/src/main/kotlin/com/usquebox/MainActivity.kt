package com.usquebox

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.usquebox.ui.screen.NavGraph
import com.usquebox.ui.theme.UsqueBoxTheme
import com.usquebox.viewmodel.TunnelViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UsqueBoxTheme {
                val viewModel: TunnelViewModel = viewModel()
                val state by viewModel.tunnelState.collectAsState()

                val vpnPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == RESULT_OK) {
                        viewModel.onVpnPermissionGranted()
                    }
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }

                NavGraph(
                    state = state,
                    onConnect = {
                        requestNotificationPermission(notificationPermissionLauncher)
                        viewModel.connect(vpnPermissionLauncher)
                    },
                    onDisconnect = { viewModel.disconnect() },
                    viewModel = viewModel
                )
            }
        }
    }

    private fun requestNotificationPermission(
        launcher: androidx.activity.result.ActivityResultLauncher<String>
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
