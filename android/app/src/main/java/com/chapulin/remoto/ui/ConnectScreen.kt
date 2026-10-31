package com.chapulin.remoto.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chapulin.remoto.R
import com.chapulin.remoto.data.RecentStore
import com.chapulin.remoto.net.HostTarget
import com.chapulin.remoto.net.PhoneServer
import com.chapulin.remoto.service.ChapulinAccessibilityService
import com.chapulin.remoto.service.ScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

@Composable
fun ConnectScreen(onConnect: (HostTarget) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { RecentStore(context) }

    var pcAddress by rememberSaveable { mutableStateOf("") }
    var pcPort by rememberSaveable { mutableStateOf("47474") }
    var pcPin by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(store.load()) }

    var celularIp by remember { mutableStateOf("") }
    val celularPort = "47474"
    var celularPin by remember { mutableStateOf("") }
    var isSharingScreen by remember { mutableStateOf(ScreenCaptureService.isRunning) }
    var isClientConnected by remember { mutableStateOf(PhoneServer.isClientConnected) }
    var connectedClientIp by remember { mutableStateOf(PhoneServer.connectedClientIp) }

    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, res.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, res.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            isSharingScreen = true
        }
    }

    var isScanning by remember { mutableStateOf(false) }
    var discoveredPcs by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        recent = store.load()
        celularIp = getLocalIpAddress(context)
        val pin = String.format("%06d", Random.nextInt(999999))
        celularPin = pin
        PhoneServer.start(47474, pin)
        PhoneServer.onStatusChange = {
            isClientConnected = PhoneServer.isClientConnected
            connectedClientIp = PhoneServer.connectedClientIp
        }
    }

    fun connect(target: HostTarget) {
        store.save(target)
        recent = store.load()
        onConnect(target)
    }

    fun copyToClipboard(text: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    fun scanNetwork() {
        if (isScanning) return
        isScanning = true
        scope.launch {
            val pcs = scanLocalNetwork(context)
            discoveredPcs = pcs
            isScanning = false
            if (pcs.isEmpty()) {
                error = "No se encontraron PCs con Chapulín Remoto activo en tu red Wi-Fi"
            } else {
                error = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(top = 40.dp, start = 20.dp, end = 20.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        Image(
            painter = painterResource(id = R.drawable.logo_chapulin),
            contentDescription = "Chapulín Remoto",
            modifier = Modifier.size(70.dp)
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Chapulín Remoto",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Control Remoto para PC",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "DATOS DE MI CELULAR",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ChapulinGreen
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text("IP del Celular", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val ipShown = run {
                                val ip = celularIp.ifBlank { "Obteniendo..." }
                                if (ip.contains('.') && ip != "Obteniendo...") {
                                    val p = ip.split('.')
                                    if (p.size == 4) "${p[0]}.${p[1]}.${p[2]}.xxx" else ip
                                } else ip
                            }
                            Text(
                                ipShown,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = ChapulinGreen
                            )
                        }
                        IconButton(
                            onClick = { if (celularIp.isNotBlank()) copyToClipboard(celularIp, "IP Celular") },
                            modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, "Copiar", tint = ChapulinGreen, modifier = Modifier.size(16.dp))
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(0.6f)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text("Puerto", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                celularPort,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = ChapulinGreen
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(0.8f)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text("PIN Celular", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                celularPin.ifBlank { "------" },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = ChapulinGreen
                            )
                        }
                        IconButton(
                            onClick = { copyToClipboard(celularPin, "PIN Celular") },
                            modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, "Copiar", tint = ChapulinGreen, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                if (isClientConnected) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Computer, contentDescription = null, tint = ChapulinGreen)
                        Column {
                            Text("PC Conectada", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = ChapulinGreen)
                            Text(connectedClientIp ?: "", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (isSharingScreen) {
                                val intent = Intent(context, ScreenCaptureService::class.java).apply {
                                    action = ScreenCaptureService.ACTION_STOP
                                }
                                context.startService(intent)
                                isSharingScreen = false
                            } else {
                                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                captureLauncher.launch(mgr.createScreenCaptureIntent())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSharingScreen) MaterialTheme.colorScheme.error else ChapulinGreen
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (isSharingScreen) "Detener Transmisión" else "Transmitir Pantalla", fontSize = 12.sp)
                    }

                    if (ChapulinAccessibilityService.instance == null) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Control Remoto", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "CONECTAR AL PC",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedButton(
                        onClick = { scanNetwork() },
                        enabled = !isScanning,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ChapulinGreen)
                            Spacer(Modifier.width(6.dp))
                            Text("Buscando...", fontSize = 12.sp)
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Buscar PC en Wi-Fi", fontSize = 12.sp)
                        }
                    }
                }

                if (discoveredPcs.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("PCs encontrados en tu red:", style = MaterialTheme.typography.labelSmall, color = ChapulinGreen)
                        discoveredPcs.forEach { ip ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { pcAddress = ip }
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Filled.Computer, null, tint = ChapulinGreen, modifier = Modifier.size(20.dp))
                                    Text(ip, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                }
                                Text("Usar esta IP", style = MaterialTheme.typography.labelSmall, color = ChapulinGreen)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = pcAddress,
                    onValueChange = { pcAddress = it },
                    label = { Text("IP del PC") },
                    placeholder = { Text("Ejemplo: 192.168.1.15") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ChapulinGreen,
                        focusedLabelColor = ChapulinGreen,
                        cursorColor = ChapulinGreen
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = pcPort,
                        onValueChange = { pcPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Puerto PC") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ChapulinGreen,
                            focusedLabelColor = ChapulinGreen,
                            cursorColor = ChapulinGreen
                        ),
                        modifier = Modifier.weight(0.45f)
                    )
                    OutlinedTextField(
                        value = pcPin,
                        onValueChange = { pcPin = it.filter(Char::isDigit).take(12) },
                        label = { Text("PIN del PC") },
                        placeholder = { Text("PIN de la app de PC") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ChapulinGreen,
                            focusedLabelColor = ChapulinGreen,
                            cursorColor = ChapulinGreen
                        ),
                        modifier = Modifier.weight(0.55f)
                    )
                }

                Button(
                    onClick = {
                        val portNumber = pcPort.toIntOrNull()
                        error = when {
                            pcAddress.isBlank() -> "Escribe la IP del PC"
                            portNumber == null || portNumber !in 1..65535 -> "Puerto inválido"
                            pcPin.length < 4 -> "Escribe el PIN de la app del PC"
                            else -> {
                                connect(HostTarget(pcAddress.trim(), portNumber, pcPin.trim()))
                                null
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ChapulinGreen,
                        contentColor = ChapulinSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Conectar al PC", fontWeight = FontWeight.Bold)
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = ChapulinGreen, modifier = Modifier.size(20.dp))
            Text(
                "Asegúrate de tener abierta la aplicación Chapulín Remoto en tu PC y que ambos estén en el mismo Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (recent.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Filled.History, null, tint = ChapulinGreen, modifier = Modifier.size(18.dp))
                            Text("Recientes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { store.clear(); recent = emptyList() }) {
                            Icon(Icons.Filled.Delete, "Borrar", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.height(110.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(recent) { target ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable { connect(target) }
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Computer, null, tint = ChapulinGreen, modifier = Modifier.size(22.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(target.address, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text("Puerto ${target.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

private suspend fun scanLocalNetwork(context: Context): List<String> = withContext(Dispatchers.IO) {
    val found = mutableListOf<String>()
    val myIp = getLocalIpAddress(context)
    if (myIp.isBlank() || myIp.startsWith("127.")) return@withContext emptyList()
    val prefix = myIp.substringBeforeLast(".") + "."

    val jobs = (1..254).map { i ->
        async {
            val targetIp = "$prefix$i"
            if (targetIp == myIp) return@async
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(targetIp, 47474), 180)
                    synchronized(found) {
                        found.add(targetIp)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }
    jobs.awaitAll()
    found
}

private fun getLocalIpAddress(context: Context): String {
    try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ipInt = wifiInfo.ipAddress
        if (ipInt != 0) {
            return String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        }

        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                    return address.hostAddress ?: ""
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "192.168.1.X"
}
