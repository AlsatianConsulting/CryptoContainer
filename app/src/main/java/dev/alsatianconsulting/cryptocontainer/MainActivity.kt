package dev.alsatianconsulting.cryptocontainer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.alsatianconsulting.cryptocontainer.service.MountService
import dev.alsatianconsulting.cryptocontainer.ui.AESCryptScreen
import dev.alsatianconsulting.cryptocontainer.ui.UsbDriveScreen
import dev.alsatianconsulting.cryptocontainer.ui.VeraCryptScreen
import dev.alsatianconsulting.cryptocontainer.ui.theme.CryptoContainerTheme
import dev.alsatianconsulting.cryptocontainer.MountController
import dev.alsatianconsulting.cryptocontainer.usb.ACTION_USB_PERMISSION
import dev.alsatianconsulting.cryptocontainer.usb.UsbDriveManager
import dev.alsatianconsulting.cryptocontainer.usb.UsbDriveState
import dev.alsatianconsulting.cryptocontainer.util.contentDisplayName
import dev.alsatianconsulting.cryptocontainer.viewmodel.ShareAction
import dev.alsatianconsulting.cryptocontainer.viewmodel.ShareViewModel

class MainActivity : ComponentActivity() {
    private val shareViewModel: ShareViewModel by viewModels()
    private lateinit var usbDriveManager: UsbDriveManager

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) usbDriveManager.onPermissionGranted(device)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device != null) usbDriveManager.onDeviceAttached(device)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null) usbDriveManager.onDeviceDetached(device)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbDriveManager = UsbDriveManager(applicationContext)
        usbDriveManager.checkExistingDevices()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )

        // Register for USB permission + detach broadcasts
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        handleIncomingIntent(intent)
        setContent {
            CryptoContainerTheme {
                CryptoContainerApp(
                    onStartService   = { startForegroundService(Intent(this, MountService::class.java)) },
                    onStopService    = { stopService(Intent(this, MountService::class.java)) },
                    shareViewModel   = shareViewModel,
                    usbDriveManager  = usbDriveManager
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Throwable) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (device != null) usbDriveManager.onDeviceAttached(device)
            }

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

enum class MainTab(val label: String) {
    VeraCrypt("VeraCrypt"),
    AESCrypt("AESCrypt"),
    UsbDrive("USB Drive")
}

@Composable
fun CryptoContainerApp(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    shareViewModel: ShareViewModel,
    usbDriveManager: UsbDriveManager
) {
    var selectedTab by remember { mutableStateOf(MainTab.VeraCrypt) }
    val usbState by usbDriveManager.state.collectAsState()
    // Auto-navigate to USB tab when a drive is detected or an error state is shown
    LaunchedEffect(usbState) {
        if (usbState !is UsbDriveState.Idle) selectedTab = MainTab.UsbDrive
    }
    val sharedUris by shareViewModel.sharedUris.observeAsState(emptyList())
    val shareAction by shareViewModel.shareAction.observeAsState()
    val mountedVolumeState by MountController.vera.volumeState.collectAsState(initial = null)

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
                        onClick  = { selectedTab = tab },
                        text     = { Text(tab.label) }
                    )
                }
            }

            when (selectedTab) {
                MainTab.VeraCrypt -> VeraCryptScreen(
                    modifier      = Modifier.fillMaxSize(),
                    onStartService = onStartService,
                    onStopService  = onStopService,
                    manager       = MountController.vera,
                    sharedUris    = sharedUris,
                    shareAction   = shareAction,
                    clearShared   = shareViewModel::clearShared
                )
                MainTab.AESCrypt -> AESCryptScreen(
                    modifier    = Modifier.fillMaxSize(),
                    manager     = MountController.aes,
                    sharedUris  = sharedUris,
                    shareAction = shareAction,
                    clearShared = shareViewModel::clearShared
                )
                MainTab.UsbDrive -> UsbDriveScreen(
                    modifier        = Modifier.fillMaxSize(),
                    usbDriveManager = usbDriveManager
                )
            }
        }
    }

    if (sharedUris.isNotEmpty() && shareAction == null) {
        AlertDialog(
            onDismissRequest = { shareViewModel.clearShared() },
            confirmButton    = {},
            text = {
                Column(
                    verticalArrangement   = Arrangement.spacedBy(12.dp),
                    horizontalAlignment   = Alignment.CenterHorizontally
                ) {
                    Text("Choose Share Action", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (sharedUris.size == 1) "1 shared item is ready."
                        else "${sharedUris.size} shared items are ready.",
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
                    if (mountedVolumeState != null) {
                        Button(onClick = { shareViewModel.selectShareAction(ShareAction.VERACRYPT_IMPORT) }) {
                            Text("Share Into Open VeraCrypt Container")
                        }
                    }
                    Button(onClick = { shareViewModel.clearShared() }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}
