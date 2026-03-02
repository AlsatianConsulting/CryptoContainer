package dev.alsatianconsulting.cryptocontainer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.alsatianconsulting.cryptocontainer.service.MountService
import dev.alsatianconsulting.cryptocontainer.ui.AESCryptScreen
import dev.alsatianconsulting.cryptocontainer.ui.VeraCryptScreen
import dev.alsatianconsulting.cryptocontainer.ui.theme.CryptoContainerTheme
import dev.alsatianconsulting.cryptocontainer.MountController
import dev.alsatianconsulting.cryptocontainer.util.contentDisplayName
import dev.alsatianconsulting.cryptocontainer.viewmodel.ShareAction
import dev.alsatianconsulting.cryptocontainer.viewmodel.ShareViewModel

class MainActivity : ComponentActivity() {
    private val shareViewModel: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        handleIncomingIntent(intent)
        setContent {
            CryptoContainerTheme {
                CryptoContainerApp(
                    onStartService = { startForegroundService(Intent(this, MountService::class.java)) },
                    onStopService = { stopService(Intent(this, MountService::class.java)) },
                    shareViewModel = shareViewModel
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                uri?.let { shareViewModel.setSharedUris(listOf(it)) }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                uris?.takeIf { it.isNotEmpty() }?.let { shareViewModel.setSharedUris(it) }
            }

            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                val displayName = contentDisplayName(this, uri, uri.lastPathSegment ?: "").lowercase()
                when {
                    displayName.endsWith(".hc") -> {
                        shareViewModel.setSharedUris(listOf(uri))
                        shareViewModel.selectShareAction(ShareAction.VERACRYPT_CONTAINER_FILE)
                    }

                    displayName.endsWith(".aes") -> {
                        shareViewModel.setSharedUris(listOf(uri))
                        shareViewModel.selectShareAction(ShareAction.AES_DECRYPT)
                    }
                }
            }
        }
    }
}

enum class MainTab(val label: String) { VeraCrypt("VeraCrypt"), AESCrypt("AESCrypt") }

@Composable
fun CryptoContainerApp(onStartService: () -> Unit, onStopService: () -> Unit, shareViewModel: ShareViewModel) {
    var selectedTab by remember { mutableStateOf(MainTab.VeraCrypt) }
    val sharedUris by shareViewModel.sharedUris.observeAsState(emptyList())
    val shareAction by shareViewModel.shareAction.observeAsState()

    LaunchedEffect(shareAction) {
        when (shareAction) {
            ShareAction.AES_ENCRYPT, ShareAction.AES_DECRYPT -> selectedTab = MainTab.AESCrypt
            ShareAction.VERACRYPT_CONTAINER_FILE, ShareAction.VERACRYPT_IMPORT -> selectedTab = MainTab.VeraCrypt
            null -> Unit
        }
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                MainTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab.ordinal == index,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label) }
                    )
                }
            }

            when (selectedTab) {
                MainTab.VeraCrypt -> VeraCryptScreen(
                    modifier = Modifier.fillMaxSize(),
                    onStartService = onStartService,
                    onStopService = onStopService,
                    manager = MountController.vera,
                    sharedUris = sharedUris,
                    shareAction = shareAction,
                    clearShared = shareViewModel::clearShared
                )
                MainTab.AESCrypt -> AESCryptScreen(
                    modifier = Modifier.fillMaxSize(),
                    manager = MountController.aes,
                    sharedUris = sharedUris,
                    shareAction = shareAction,
                    clearShared = shareViewModel::clearShared
                )
            }
        }
    }

    if (sharedUris.isNotEmpty() && shareAction == null) {
        AlertDialog(
            onDismissRequest = { shareViewModel.clearShared() },
            confirmButton = {},
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Choose Share Action",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (sharedUris.size == 1) {
                            "1 shared item is ready."
                        } else {
                            "${sharedUris.size} shared items are ready."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = { shareViewModel.selectShareAction(ShareAction.AES_ENCRYPT) }) {
                        Text("Encrypt Using AESCrypt")
                    }
                    Button(onClick = { shareViewModel.selectShareAction(ShareAction.AES_DECRYPT) }) {
                        Text("Decrypt Using AESCrypt")
                    }
                    Button(onClick = { shareViewModel.selectShareAction(ShareAction.VERACRYPT_CONTAINER_FILE) }) {
                        Text("Mount as VeraCrypt Container")
                    }
                    Button(onClick = { shareViewModel.clearShared() }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}
