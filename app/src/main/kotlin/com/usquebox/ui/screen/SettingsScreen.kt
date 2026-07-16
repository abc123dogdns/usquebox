package com.usquebox.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.usquebox.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mobile.Mobile
import org.json.JSONObject

data class NoiseConfig(
    val enabled: Boolean = true,
    val count: String = "5",
    val minSize: String = "100",
    val maxSize: String = "400",
    val delayMin: String = "10ms",
    val delayMax: String = "50ms"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentConfig: String?,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateToApps: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var configJson by remember { mutableStateOf(currentConfig ?: "") }
    var port by remember { mutableStateOf("443") }
    var useIPv6 by remember { mutableStateOf(false) }
    var useHttp2 by remember { mutableStateOf(false) }
    var sniAddress by remember { mutableStateOf("") }
    var congestionType by remember { mutableStateOf("bbr") }
    var bbrProfile by remember { mutableStateOf("standard") }
    var brutalBps by remember { mutableStateOf("0") }
    var keepalivePeriod by remember { mutableStateOf("30s") }
    var reconnectDelay by remember { mutableStateOf("1s") }

    var tunIPv4 by remember { mutableStateOf(true) }
    var tunIPv6 by remember { mutableStateOf(true) }

    var noiseEnabled by remember { mutableStateOf(true) }
    var noiseCount by remember { mutableStateOf("5") }
    var noiseMinSize by remember { mutableStateOf("100") }
    var noiseMaxSize by remember { mutableStateOf("400") }
    var noiseDelayMin by remember { mutableStateOf("10ms") }
    var noiseDelayMax by remember { mutableStateOf("50ms") }

    var preNoiseEnabled by remember { mutableStateOf(true) }
    var preNoiseCount by remember { mutableStateOf("3") }
    var preNoiseMinSize by remember { mutableStateOf("64") }
    var preNoiseMaxSize by remember { mutableStateOf("128") }
    var preNoiseDelayMin by remember { mutableStateOf("5ms") }
    var preNoiseDelayMax by remember { mutableStateOf("15ms") }

    var congestionExpanded by remember { mutableStateOf(false) }
    var bbrProfileExpanded by remember { mutableStateOf(false) }

    var isRegistering by remember { mutableStateOf(false) }
    var registerError by remember { mutableStateOf<String?>(null) }
    var jwtToken by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("") }

    var initialized by remember { mutableStateOf(false) }

    fun parseNoise(json: JSONObject, key: String): NoiseConfig {
        val n = json.optJSONObject(key) ?: return NoiseConfig()
        return NoiseConfig(
            enabled = n.optBoolean("enabled", true),
            count = n.optInt("count", 5).toString(),
            minSize = n.optInt("min_size", 100).toString(),
            maxSize = n.optInt("max_size", 400).toString(),
            delayMin = n.optString("delay_min", "10ms"),
            delayMax = n.optString("delay_max", "50ms")
        )
    }

    fun loadFromJson(json: String) {
        configJson = json
        try {
            val root = JSONObject(json)
            val outbound = if (root.has("outbound"))
                root.getJSONObject("outbound").getJSONObject("settings")
            else null
            outbound?.let { ob ->
                port = ob.optString("port", "443")
                useIPv6 = ob.optBoolean("use_ipv6", false)
                useHttp2 = ob.optBoolean("use_http2", false)
                sniAddress = ob.optString("sni_address", "")
                keepalivePeriod = ob.optString("keepalive_period", "30s")
                reconnectDelay = ob.optString("reconnect_delay", "1s")
                val cong = ob.optJSONObject("congestion")
                congestionType = cong?.optString("type", "bbr") ?: "bbr"
                bbrProfile = cong?.optString("bbr_profile", "standard") ?: "standard"
                brutalBps = cong?.optLong("brutal_bps", 0)?.toString() ?: "0"
                val noise = parseNoise(ob, "noise")
                noiseEnabled = noise.enabled; noiseCount = noise.count
                noiseMinSize = noise.minSize; noiseMaxSize = noise.maxSize
                noiseDelayMin = noise.delayMin; noiseDelayMax = noise.delayMax
                val preNoise = parseNoise(ob, "pre_noise")
                preNoiseEnabled = preNoise.enabled; preNoiseCount = preNoise.count
                preNoiseMinSize = preNoise.minSize; preNoiseMaxSize = preNoise.maxSize
                preNoiseDelayMin = preNoise.delayMin; preNoiseDelayMax = preNoise.delayMax
            }
            val inbound = root.optJSONObject("inbound")?.optJSONObject("settings")
            inbound?.let { tun ->
                tunIPv4 = tun.optBoolean("ipv4", true)
                tunIPv6 = tun.optBoolean("ipv6", true)
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(currentConfig) {
        if (!initialized && !currentConfig.isNullOrBlank()) {
            loadFromJson(currentConfig)
            initialized = true
        }
    }

    fun buildConfigJson(): String? {
        if (configJson.isBlank()) return null
        return try {
            val root = JSONObject(configJson)
            if (root.has("account") && root.has("outbound")) {
                val outbound = root.getJSONObject("outbound").getJSONObject("settings")
                outbound.put("port", port.toIntOrNull() ?: 443)
                outbound.put("use_ipv6", useIPv6)
                outbound.put("use_http2", useHttp2)
                if (sniAddress.isNotBlank()) outbound.put("sni_address", sniAddress)
                outbound.put("keepalive_period", keepalivePeriod)
                outbound.put("reconnect_delay", reconnectDelay)
                val congestion = JSONObject().apply {
                    put("type", congestionType)
                    if (congestionType == "bbr") put("bbr_profile", bbrProfile)
                    if (congestionType == "brutal") put("brutal_bps", brutalBps.toLongOrNull() ?: 0)
                }
                outbound.put("congestion", congestion)
                fun buildNoise(enabled: Boolean, count: String, minS: String, maxS: String, dMin: String, dMax: String) =
                    JSONObject().apply {
                        put("enabled", enabled)
                        put("count", count.toIntOrNull() ?: 0)
                        put("min_size", minS.toIntOrNull() ?: 0)
                        put("max_size", maxS.toIntOrNull() ?: 0)
                        put("delay_min", dMin)
                        put("delay_max", dMax)
                    }
                outbound.put("noise", buildNoise(noiseEnabled, noiseCount, noiseMinSize, noiseMaxSize, noiseDelayMin, noiseDelayMax))
                outbound.put("pre_noise", buildNoise(preNoiseEnabled, preNoiseCount, preNoiseMinSize, preNoiseMaxSize, preNoiseDelayMin, preNoiseDelayMax))

                val inbound = root.optJSONObject("inbound")
                if (inbound != null) {
                    var tunSettings = inbound.optJSONObject("settings")
                    if (tunSettings == null) {
                        tunSettings = JSONObject()
                        inbound.put("settings", tunSettings)
                    }
                    tunSettings.put("ipv4", tunIPv4)
                    tunSettings.put("ipv6", tunIPv6)
                }

                root.toString(2)
            } else configJson
        } catch (_: Exception) { configJson }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        configJson = ""; port = "443"; useIPv6 = false; useHttp2 = false
                        sniAddress = ""; congestionType = "bbr"; bbrProfile = "standard"; brutalBps = "0"
                        keepalivePeriod = "30s"; reconnectDelay = "1s"
                        tunIPv4 = true; tunIPv6 = true
                        noiseEnabled = true; noiseCount = "5"; noiseMinSize = "100"; noiseMaxSize = "400"
                        noiseDelayMin = "10ms"; noiseDelayMax = "50ms"
                        preNoiseEnabled = true; preNoiseCount = "3"; preNoiseMinSize = "64"; preNoiseMaxSize = "128"
                        preNoiseDelayMin = "5ms"; preNoiseDelayMax = "15ms"
                        registerError = null
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear all")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Account ──
            SectionTitle("Account")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Register New Account", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = { Text("Device Name (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = jwtToken,
                        onValueChange = { jwtToken = it },
                        label = { Text("ZeroTrust JWT (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))

                    if (isRegistering) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.height(24.dp).width(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Registering...", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Button(
                            onClick = {
                                registerError = null
                                isRegistering = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        Mobile.registerAccount(jwtToken, deviceName)
                                    }
                                    isRegistering = false
                                    if (result.startsWith("error:")) {
                                        registerError = result.removePrefix("error: ")
                                    } else {
                                        loadFromJson(result)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Register")
                        }
                    }

                    registerError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Import Config JSON", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = configJson,
                        onValueChange = { configJson = it },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        placeholder = { Text("Or paste config.json from usque register") },
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val clip = clipboard.getText()?.text
                                if (!clip.isNullOrBlank()) loadFromJson(clip)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Paste")
                        }
                        OutlinedButton(
                            onClick = { if (configJson.isNotBlank()) loadFromJson(configJson) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Parse")
                        }
                    }
                }
            }

            // ── TUN Interface ──
            SectionTitle("TUN Interface")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Controls which protocol stacks the VPN tunnel accepts. " +
                        "Disabled stacks are unreachable by the kernel (no leak).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    SwitchRow("Enable IPv4", tunIPv4) { tunIPv4 = it }
                    SwitchRow("Enable IPv6", tunIPv6) { tunIPv6 = it }
                }
            }

            // ── Connection ──
            SectionTitle("Connection")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = port, onValueChange = { port = it },
                        label = { Text("Port") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = sniAddress, onValueChange = { sniAddress = it },
                        label = { Text("SNI Address (optional)") },
                        placeholder = { Text("auto-detected if empty") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    SwitchRow("Use IPv6", useIPv6) { useIPv6 = it }
                    SwitchRow("Use HTTP/2 (fallback)", useHttp2) { useHttp2 = it }
                }
            }

            // ── Protocol ──
            SectionTitle("Protocol")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ExposedDropdownMenuBox(expanded = congestionExpanded, onExpandedChange = { congestionExpanded = it }) {
                        OutlinedTextField(
                            value = congestionType.uppercase(),
                            onValueChange = {}, readOnly = true,
                            label = { Text("Congestion Controller") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = congestionExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        DropdownMenu(expanded = congestionExpanded, onDismissRequest = { congestionExpanded = false }) {
                            listOf("reno", "bbr", "brutal").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.uppercase()) },
                                    onClick = { congestionType = option; congestionExpanded = false }
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = congestionType == "bbr") {
                        ExposedDropdownMenuBox(expanded = bbrProfileExpanded, onExpandedChange = { bbrProfileExpanded = it }) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = bbrProfile.replaceFirstChar { it.uppercase() },
                                onValueChange = {}, readOnly = true,
                                label = { Text("BBR Profile") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bbrProfileExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            )
                            DropdownMenu(expanded = bbrProfileExpanded, onDismissRequest = { bbrProfileExpanded = false }) {
                                listOf("conservative", "standard", "aggressive").forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.replaceFirstChar { it.uppercase() }) },
                                        onClick = { bbrProfile = option; bbrProfileExpanded = false }
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = congestionType == "brutal") {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = brutalBps,
                                onValueChange = { brutalBps = it },
                                label = { Text("Brutal BPS (bytes/sec)") },
                                placeholder = { Text("e.g. 5000000 for 5MB/s") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                        }
                    }
                }
            }

            // ── Noise ──
            SectionTitle("Noise Injection")
            NoiseCard("Noise (post-connect)", noiseEnabled, noiseCount, noiseMinSize, noiseMaxSize, noiseDelayMin, noiseDelayMax) {
                noiseEnabled = it.enabled; noiseCount = it.count; noiseMinSize = it.minSize
                noiseMaxSize = it.maxSize; noiseDelayMin = it.delayMin; noiseDelayMax = it.delayMax
            }
            NoiseCard("Pre-noise (pre-QUIC)", preNoiseEnabled, preNoiseCount, preNoiseMinSize, preNoiseMaxSize, preNoiseDelayMin, preNoiseDelayMax) {
                preNoiseEnabled = it.enabled; preNoiseCount = it.count; preNoiseMinSize = it.minSize
                preNoiseMaxSize = it.maxSize; preNoiseDelayMin = it.delayMin; preNoiseDelayMax = it.delayMax
            }

            // ── Advanced ──
            SectionTitle("Advanced")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = keepalivePeriod, onValueChange = { keepalivePeriod = it },
                        label = { Text("Keepalive Period") },
                        placeholder = { Text("30s") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = reconnectDelay, onValueChange = { reconnectDelay = it },
                        label = { Text("Reconnect Delay") },
                        placeholder = { Text("1s") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Per-App Proxy ──
            SectionTitle("Per-App Proxy")

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToApps() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("App Rules", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Configure which apps use the VPN",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("›", style = MaterialTheme.typography.headlineMedium)
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    val json = buildConfigJson()
                    if (!json.isNullOrBlank()) onSave(json)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = configJson.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NoiseCard(
    title: String,
    enabled: Boolean, count: String, minSize: String, maxSize: String, delayMin: String, delayMax: String,
    onUpdate: (NoiseConfig) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SwitchRow(title, enabled) { onUpdate(NoiseConfig(it, count, minSize, maxSize, delayMin, delayMax)) }

            AnimatedVisibility(visible = enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallField("Count", count, Modifier.weight(1f)) { onUpdate(NoiseConfig(enabled, it, minSize, maxSize, delayMin, delayMax)) }
                        SmallField("Min Size", minSize, Modifier.weight(1f)) { onUpdate(NoiseConfig(enabled, count, it, maxSize, delayMin, delayMax)) }
                        SmallField("Max Size", maxSize, Modifier.weight(1f)) { onUpdate(NoiseConfig(enabled, count, minSize, it, delayMin, delayMax)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallField("Delay Min", delayMin, Modifier.weight(1f)) { onUpdate(NoiseConfig(enabled, count, minSize, maxSize, it, delayMax)) }
                        SmallField("Delay Max", delayMax, Modifier.weight(1f)) { onUpdate(NoiseConfig(enabled, count, minSize, maxSize, delayMin, it)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
