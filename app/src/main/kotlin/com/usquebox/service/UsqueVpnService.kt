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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mobile.Mobile
import mobile.TunnelListener
import org.json.JSONObject
import java.io.FileDescriptor
import java.lang.reflect.Field
import java.net.DatagramSocket

class UsqueVpnService : VpnService() {

    private lateinit var configStore: ConfigStore
    private var tunFd: Int = -1
    private var udpSocket: DatagramSocket? = null

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
        stopTunnelInternal()
        instance = null
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnelInternal()
        stopSelf()
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

        // Per-app proxy: always exclude self to prevent VPN loops
        val selfPkg = packageName
        builder.addDisallowedApplication(selfPkg)

        val proxyMode = configStore.proxyMode
        val selectedApps = configStore.selectedApps
        when (proxyMode) {
            ProxyMode.BYPASS -> {
                // Blacklist: proxy everything EXCEPT selected apps
                for (pkg in selectedApps) {
                    if (pkg != selfPkg) {
                        builder.addDisallowedApplication(pkg)
                    }
                }
                Log.d(TAG, "Per-app BYPASS: ${selectedApps.size} apps excluded")
            }
            ProxyMode.PROXY_ONLY -> {
                // Whitelist: ONLY proxy selected apps
                for (pkg in selectedApps) {
                    if (pkg != selfPkg) {
                        builder.addAllowedApplication(pkg)
                    }
                }
                Log.d(TAG, "Per-app PROXY_ONLY: ${selectedApps.size} apps included")
            }
            ProxyMode.GLOBAL -> {
                Log.d(TAG, "Global mode: all traffic proxied")
            }
        }

        val pfd = builder.establish()
        if (pfd == null) {
            _tunnelState.value = TunnelState.Error("Failed to establish VPN interface")
            stopSelf()
            return
        }
        tunFd = pfd.fd

        val socket = DatagramSocket()
        protect(socket)
        udpSocket = socket
        val udpFd = getSocketFd(socket)

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
        val err = Mobile.startTunnel(tunFd.toLong(), udpFd.toLong(), configJson)
        if (err.isNotEmpty()) {
            Mobile.unregisterListener()
            _tunnelState.value = TunnelState.Error(err)
            stopSelf()
        }
    }

    private fun stopTunnel() {
        stopTunnelInternal()
        stopSelf()
    }

    private fun stopTunnelInternal() {
        try {
            Mobile.stopTunnel()
            Mobile.unregisterListener()
        } catch (_: Exception) {}
        udpSocket?.close()
        udpSocket = null
        if (tunFd >= 0) {
            try {
                android.os.ParcelFileDescriptor.adoptFd(tunFd).close()
            } catch (_: Exception) {}
            tunFd = -1
        }
        _tunnelState.value = TunnelState.Stopped
    }

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

    private fun getSocketFd(socket: DatagramSocket): Int {
        val implField = DatagramSocket::class.java.getDeclaredField("impl")
        implField.isAccessible = true
        val impl = implField.get(socket)
        val fdField = impl.javaClass.superclass?.getDeclaredField("fd")
            ?: throw NoSuchFieldException("fd field not found on socket impl")
        fdField.isAccessible = true
        val fd = fdField.get(impl) as FileDescriptor
        val fdIntField: Field = FileDescriptor::class.java.getDeclaredField("fd")
        fdIntField.isAccessible = true
        return fdIntField.getInt(fd)
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
            val ipv4Addr = account?.optString("ipv4", null)
            val ipv6Addr = account?.optString("ipv6", null)

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
