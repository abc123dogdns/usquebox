package com.usquebox.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.usquebox.MainActivity
import com.usquebox.R
import com.usquebox.UsqueBoxApp
import com.usquebox.data.ConfigStore
import com.usquebox.data.ProxyMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mobile.Mobile
import mobile.TunnelListener
import org.json.JSONObject

class UsqueVpnService : VpnService() {

    private lateinit var configStore: ConfigStore
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunnelStopped = false
    private var tunPfd: android.os.ParcelFileDescriptor? = null
    private var tunFd: Int = -1
    private var udpFd: Long = -1

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startTunnel()
            ACTION_DISCONNECT -> stopTunnel()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (!tunnelStopped) {
            tunnelStopped = true
            stopTunnelInternalBlocking()
        }
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onRevoke() {
        if (!tunnelStopped) {
            tunnelStopped = true
            stopTunnelInternalBlocking()
        }
        serviceScope.cancel()
    }

    private fun startTunnel() {
        val configJson = configStore.configJson
        if (configJson.isNullOrBlank()) {
            _tunnelState.value = TunnelState.Error("No configuration set")
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification(R.string.notification_connecting))

        _tunnelState.value = TunnelState.Connecting

        val parsed = parseVpnConfig(configJson)
        if (parsed == null) {
            _tunnelState.value = TunnelState.Error("Failed to parse config")
            stopSelf()
            return
        }

        val builder = Builder().setMtu(parsed.mtu).setSession("UsqueBox")

        if (parsed.enableIPv4 && parsed.ipv4Address != null) {
            builder.addAddress(parsed.ipv4Address, 32)
            builder.addRoute("0.0.0.0", 0)
            builder.allowFamily(android.system.OsConstants.AF_INET)
        }
        if (parsed.enableIPv6 && parsed.ipv6Address != null) {
            builder.addAddress(parsed.ipv6Address, 128)
            builder.addRoute("::", 0)
            builder.allowFamily(android.system.OsConstants.AF_INET6)
        }

        for (dns in parsed.dnsServers) {
            builder.addDnsServer(dns)
        }

        // Per-app proxy: configure app routing on the VPN builder
        val selfPkg = packageName
        val proxyMode = configStore.proxyMode
        val selectedApps = configStore.selectedApps
        when (proxyMode) {
            ProxyMode.BYPASS -> {
                // Blacklist: proxy everything EXCEPT selected apps
                val disallowed = mutableSetOf(selfPkg)
                for (pkg in selectedApps) {
                    disallowed.add(pkg)
                }
                for (pkg in disallowed) {
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (_: Exception) {}
                }
                Log.d(TAG, "Per-app BYPASS: ${disallowed.size} apps excluded")
            }
            ProxyMode.PROXY_ONLY -> {
                // Whitelist: ONLY proxy selected apps
                // Do NOT call addDisallowedApplication here — Android 14+ Builder
                // treats allowed/disallowed as mutually exclusive modes.
                // VpnService.protect() on the MASQUE UDP socket is sufficient
                // to prevent VPN routing loops for our own traffic.
                for (pkg in selectedApps) {
                    if (pkg != selfPkg) {
                        try {
                            builder.addAllowedApplication(pkg)
                        } catch (_: Exception) {}
                    }
                }
                Log.d(TAG, "Per-app PROXY_ONLY: ${selectedApps.size} apps included")
            }
            ProxyMode.GLOBAL -> {
                builder.addDisallowedApplication(selfPkg)
                Log.d(TAG, "Global mode: all traffic proxied")
            }
        }

        // Clean up any leftover PFD from a failed prior attempt that never got
        // handed to the Go engine. In the normal stop path tunPfd is already
        // detached (nulled) by snapshotAndClearFds, so this is a no-op then;
        // the TUN fd is owned and closed by Go, never by us once started.
        tunPfd?.close()
        tunPfd = null
        val pfd = builder.establish()
        if (pfd == null) {
            _tunnelState.value = TunnelState.Error("Failed to establish VPN interface")
            stopSelf()
            return
        }
        tunPfd = pfd
        tunFd = pfd.fd

        if (udpFd >= 0) {
            Mobile.closeSocket(udpFd)
        }
        udpFd = Mobile.createUDPSocket()
        if (udpFd < 0) {
            _tunnelState.value = TunnelState.Error("Failed to create UDP socket")
            stopTunnelInternalAsync()
            stopSelf()
            return
        }
        protect(udpFd.toInt())

        val listener = object : TunnelListener {
            override fun onStateChange(state: String?) {
                Log.d(TAG, "State change: $state")
                _tunnelState.value = when (state) {
                    "connecting" -> TunnelState.Connecting
                    "connected" -> TunnelState.Connected()
                    "reconnecting" -> TunnelState.Reconnecting()
                    "stopped" -> TunnelState.Stopped
                    "error" -> TunnelState.Error("Tunnel error")
                    else -> _tunnelState.value
                }
                updateNotification()
            }

            override fun onTraffic(sent: Long, recv: Long) {
                val current = _tunnelState.value
                _tunnelState.value = when (current) {
                    is TunnelState.Connected -> current.copy(bytesSent = sent, bytesRecv = recv)
                    is TunnelState.Reconnecting -> current.copy(bytesSent = sent, bytesRecv = recv)
                    else -> current
                }
            }
        }

        Mobile.registerListener(listener)
        val err = Mobile.startTunnel(tunFd.toLong(), udpFd, configJson)
        if (err.isNotEmpty()) {
            Mobile.unregisterListener()
            _tunnelState.value = TunnelState.Error(err)
            stopTunnelInternalAsync()
            stopSelf()
        }
    }

    private fun stopTunnel() {
        if (tunnelStopped) return
        tunnelStopped = true
        _tunnelState.value = TunnelState.Stopped
        updateNotification()
        stopTunnelInternalAsync()
        stopSelf()
    }

    /**
     * Snapshot and reset the tunnel fds so any subsequent teardown sees cleared
     * state, and returns the captured UDP fd for Kotlin-side cleanup.
     *
     * NOTE on fd ownership:
     *  - The TUN fd is handed to Go via StartTunnel and owned by the Go engine
     *    (FdAdapter.Close closes it inside StopTunnel). Kotlin MUST NOT close
     *    tunPfd — double-closing a released fd can silently close an unrelated
     *    socket (fd reuse disaster). We detach it here.
     *  - The UDP fd is created by Kotlin (createUDPSocket) and merely borrowed
     *    by Go (MaintainTunnel never closes a caller-supplied UDPConn), so
     *    Kotlin owns and closes it.
     *
     * @Synchronized guards against concurrent teardown entry (e.g. user-initiated
     * disconnect racing a system onRevoke) so two threads can't both snapshot a
     * non-null fd and double-close.
     */
    @Synchronized
    private fun snapshotAndClearFds(): FdSnapshot {
        val udpFdToClose = udpFd
        udpFd = -1
        // Detach the TUN PFD from Kotlin ownership; Go's StopTunnel closes the
        // underlying fd via FdAdapter.Close (raw close(2)). detachFd() drops the
        // ParcelFileDescriptor's fdsan ownership tag so Go's raw close doesn't
        // leave a stale owner record that trips fdsan when the fd number is
        // later reused. After this the PFD no longer owns/closes the fd; we must
        // never close it from Kotlin — only null our reference.
        tunPfd?.detachFd()
        tunPfd = null
        tunFd = -1
        return FdSnapshot(udpFdToClose)
    }

    private fun teardownGoEngine(snap: FdSnapshot) {
        try {
            Mobile.stopTunnel()
        } catch (_: Exception) {}
        try {
            Mobile.unregisterListener()
        } catch (_: Exception) {}
        // Only the UDP fd is Kotlin-owned; the TUN fd is closed by Go above.
        if (snap.udpFd >= 0) {
            try {
                Mobile.closeSocket(snap.udpFd)
            } catch (_: Exception) {}
        }
        _tunnelState.value = TunnelState.Stopped
    }

    /**
     * Runs the blocking teardown (Mobile.stopTunnel, UDP fd close) on the IO
     * dispatcher. Mobile.stopTunnel blocks until the Go engine fully exits;
     * running it on the main thread risks ANR.
     */
    private fun stopTunnelInternalAsync() {
        val snap = snapshotAndClearFds()
        try {
            serviceScope.launch { teardownGoEngine(snap) }
        } catch (_: Exception) {
            teardownGoEngine(snap)
        }
    }

    /**
     * Same teardown as the async variant but waits for completion. Used by
     * onDestroy/onRevoke (invoked on the main and Binder threads respectively),
     * which MUST release the Go engine and UDP fd before the process tears
     * down — an unawaited async launch can be cancelled by the subsequent
     * serviceScope.cancel(), leaking the Go goroutine and the UDP fd.
     *
     * Bounded by [STOP_TIMEOUT] so that even an unexpected Go-side stall cannot
     * hold the lifecycle callback long enough to trigger an ANR/SIGKILL.
     */
    private fun stopTunnelInternalBlocking() {
        val snap = snapshotAndClearFds()
        runBlocking {
            withTimeoutOrNull(STOP_TIMEOUT) {
                withContext(Dispatchers.IO) { teardownGoEngine(snap) }
            } ?: Log.w(TAG, "Tunnel stop timed out after ${STOP_TIMEOUT}ms; continuing teardown")
        }
    }

    private data class FdSnapshot(val udpFd: Long)

    private fun buildNotification(stateRes: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, UsqueBoxApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(stateRes))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val stateRes = when (_tunnelState.value) {
            is TunnelState.Connected -> R.string.notification_connected
            is TunnelState.Reconnecting -> R.string.notification_reconnecting
            else -> R.string.notification_connecting
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(stateRes))
    }

    private data class VpnConfig(
        val ipv4Address: String?,
        val ipv6Address: String?,
        val enableIPv4: Boolean,
        val enableIPv6: Boolean,
        val dnsServers: List<String>,
        val mtu: Int
    )

    private fun parseVpnConfig(json: String): VpnConfig? {
        return try {
            val root = JSONObject(json)
            val account = root.optJSONObject("account")
            val ipv4Addr = account?.optString("ipv4")?.ifEmpty { null }
            val ipv6Addr = account?.optString("ipv6")?.ifEmpty { null }

            val inbound = root.optJSONObject("inbound")
            val tunSettings = inbound?.optJSONObject("settings")

            val enableIPv4 = tunSettings?.optBoolean("ipv4", true) ?: true
            val enableIPv6 = tunSettings?.optBoolean("ipv6", true) ?: true
            val mtu = tunSettings?.optInt("mtu", 1280) ?: 1280

            val dnsList = mutableListOf<String>()
            val dnsArray = tunSettings?.optJSONArray("dns")
            if (dnsArray != null) {
                for (i in 0 until dnsArray.length()) {
                    dnsArray.optString(i)?.let { dnsList.add(it) }
                }
            }
            if (dnsList.isEmpty()) {
                if (enableIPv4) {
                    dnsList.add("1.1.1.1")
                    dnsList.add("8.8.8.8")
                }
                if (enableIPv6) {
                    dnsList.add("2606:4700:4700::1111")
                }
            }

            VpnConfig(
                ipv4Address = ipv4Addr,
                ipv6Address = ipv6Addr,
                enableIPv4 = enableIPv4 && ipv4Addr != null,
                enableIPv6 = enableIPv6 && ipv6Addr != null,
                dnsServers = dnsList,
                mtu = mtu
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse VPN config", e)
            null
        }
    }

    companion object {
        private const val TAG = "UsqueVpnService"
        private const val NOTIFICATION_ID = 1
        // Upper bound for the blocking stop in lifecycle callbacks. The Go-side
        // stop completes in milliseconds; this only guards against an unexpected
        // stall so onDestroy/onRevoke can't ANR or get SIGKILL'd.
        private const val STOP_TIMEOUT = 5_000L
        const val ACTION_CONNECT = "com.usquebox.CONNECT"
        const val ACTION_DISCONNECT = "com.usquebox.DISCONNECT"

        private val _tunnelState = MutableStateFlow<TunnelState>(TunnelState.Stopped)
        val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

        var instance: UsqueVpnService? = null
            private set
    }
}

sealed class TunnelState {
    data object Stopped : TunnelState()
    data object Connecting : TunnelState()
    data class Connected(
        val bytesSent: Long = 0,
        val bytesRecv: Long = 0
    ) : TunnelState()
    data class Reconnecting(
        val bytesSent: Long = 0,
        val bytesRecv: Long = 0
    ) : TunnelState()
    data class Error(val message: String) : TunnelState()
}
