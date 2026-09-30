package com.simplelink.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.simplelink.app.control.RemoteControlAccessibilityService
import com.simplelink.app.control.RemoteControlCommand
import com.simplelink.app.media.ProjectionForegroundService
import com.simplelink.app.rescue.AppHashProvider
import com.simplelink.app.rescue.RescueProtocol
import com.simplelink.app.rescue.RescueSmsReceiver
import com.simplelink.app.session.NearbyMode
import com.simplelink.app.session.Role
import com.simplelink.app.session.SessionEvent
import com.simplelink.app.session.SessionUiState
import com.simplelink.app.ui.RemoteSessionScreen
import com.simplelink.app.ui.SimpleLinkRoot
import com.simplelink.app.ui.SimpleLinkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private val controller by lazy { (application as SimpleLinkApp).sessionController }
    private val projectionManager by lazy { getSystemService(MediaProjectionManager::class.java) }

    private var pendingNearby: Pair<NearbyMode, String>? = null
    private var pendingRescueCode: String? = null
    private var pendingRescueRequestId: String? = null
    private var pendingRescuePhone: String? = null
    private var remoteViewerActive = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            startProjectionService()
            lifecycleScope.launch {
                val ready = withTimeoutOrNull(5_000) {
                    ProjectionForegroundService.ready.first { it }
                } != null
                if (ready) controller.onProjectionPermissionGranted(data) else controller.projectionDenied()
            }
        } else {
            controller.projectionDenied()
        }
    }

    private val nearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        val pending = pendingNearby
        pendingNearby = null
        if (granted && pending != null) startNearby(pending.first, pending.second)
    }

    private val rescuePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        val phone = contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)
        } ?: return@registerForActivityResult
        pendingRescuePhone = phone
        openSmsComposer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        handleRescueIntent(intent)
        observeEvents()

        setContent {
            SimpleLinkTheme {
                val state by controller.state.collectAsState()
                val videoTrack by controller.remoteVideoTrack.collectAsState()
                val remoteSize by controller.remoteScreenSize.collectAsState()
                val remoteInputState by controller.remoteInputState.collectAsState()
                val remoteViewer = state is SessionUiState.Remote && (state as SessionUiState.Remote).role == Role.VIEWER

                LaunchedEffect(remoteViewer) {
                    remoteViewerActive = remoteViewer
                    applyRemoteViewerSystemUi(remoteViewer)
                }

                BackHandler(enabled = state !is SessionUiState.Home) {
                    if (!remoteViewer) {
                        controller.backHome()
                    }
                }

                SimpleLinkRoot(
                    state = state,
                    controller = controller,
                    onEnableControl = controller::prepareControlSetup
                ) { role ->
                    val status = (state as? SessionUiState.Remote)?.status.orEmpty()
                    RemoteSessionScreen(
                        role = role,
                        status = status,
                        videoTrack = if (role == Role.VIEWER) videoTrack else null,
                        screenSize = remoteSize,
                        inputState = remoteInputState,
                        eglContext = controller.webRtc.eglBase.eglBaseContext,
                        onControl = controller::sendControl,
                        onDisconnect = controller::disconnect
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRescueIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        controller.onAccessibilityReturned()
        if (remoteViewerActive) applyRemoteViewerSystemUi(true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && remoteViewerActive) {
            applyRemoteViewerSystemUi(true)
        }
    }

    private fun applyRemoteViewerSystemUi(active: Boolean) {
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (active) {
            insets.hide(WindowInsetsCompat.Type.systemBars())
            insets.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            controller.events.collect { event ->
                when (event) {
                    is SessionEvent.RequestScreenCapture -> {
                        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    }
                    is SessionEvent.EnsureNearbyPermission -> ensureNearbyPermission(event.mode, event.code)
                    is SessionEvent.PickRescueContact -> pickRescueContact(event.code, event.requestId)
                    SessionEvent.OpenInternetPanel -> openInternetPanel()
                    SessionEvent.OpenAccessibilitySettings -> openAccessibilitySettings()
                    SessionEvent.StopProjectionService -> stopService(
                        Intent(this@MainActivity, ProjectionForegroundService::class.java)
                    )
                    SessionEvent.EnsureNotificationPermission -> ensureNotificationPermission()
                }
            }
        }
    }

    private fun ensureNearbyPermission(mode: NearbyMode, code: String) {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startNearby(mode, code)
        } else {
            pendingNearby = mode to code
            nearbyPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startNearby(mode: NearbyMode, code: String) {
        when (mode) {
            NearbyMode.HOST -> controller.startNearbyHost(code)
            NearbyMode.JOIN -> controller.startNearbyJoin(code)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            rescuePermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private fun openAccessibilitySettings() {
        val component = ComponentName(this, RemoteControlAccessibilityService::class.java)
        val detailIntent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
        }
        val opened = runCatching {
            startActivity(detailIntent)
            true
        }.getOrDefault(false)

        if (!opened) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun openInternetPanel() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
        startActivity(intent)
    }

    private fun startProjectionService() {
        val intent = Intent(this, ProjectionForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun pickRescueContact(code: String, requestId: String) {
        pendingRescueCode = code
        pendingRescueRequestId = requestId
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        contactPickerLauncher.launch(intent)
    }

    private fun openSmsComposer() {
        val code = pendingRescueCode ?: return
        val requestId = pendingRescueRequestId ?: return
        val phone = pendingRescuePhone.orEmpty()
        val body = RescueProtocol.createSmsMessage(code, requestId, AppHashProvider.get(this))
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("smsto:${android.net.Uri.encode(phone)}")
            putExtra("sms_body", body)
        }
        runCatching { startActivity(intent) }
            .onSuccess {
                controller.onRescueSmsDispatched(code, requestId)
                clearPendingRescue()
            }
    }

    private fun clearPendingRescue() {
        pendingRescueCode = null
        pendingRescueRequestId = null
        pendingRescuePhone = null
    }

    private fun handleRescueIntent(intent: Intent?) {
        intent ?: return
        val extrasCode = intent.getStringExtra(RescueSmsReceiver.EXTRA_CODE)
        val extrasRequestId = intent.getStringExtra(RescueSmsReceiver.EXTRA_REQUEST_ID)
        if (extrasCode != null && extrasRequestId != null) {
            RescueProtocol.validate(
                extrasCode,
                extrasRequestId,
                intent.getStringExtra(RescueSmsReceiver.EXTRA_SENDER)
            )?.let(controller::incomingRescueRequest)
            return
        }

        val data = intent.data ?: return
        if (data.scheme != "simplelink" || data.host != "rescue") return
        val code = data.getQueryParameter("c") ?: return
        val requestId = data.getQueryParameter("r") ?: return
        RescueProtocol.validate(code, requestId, null)
            ?.let(controller::incomingRescueRequest)
    }
}
