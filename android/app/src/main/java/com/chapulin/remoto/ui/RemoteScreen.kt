package com.chapulin.remoto.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chapulin.remoto.R
import com.chapulin.remoto.net.HostTarget
import com.chapulin.remoto.net.Keymap
import com.chapulin.remoto.net.RemoteClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun RemoteScreen(target: HostTarget, onExit: () -> Unit) {
    val client = remember { RemoteClient() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("Conectando a ${target.address}:${target.port}…") }
    var connected by remember { mutableStateOf(false) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    var keyboardVisible by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val currentFrame by rememberUpdatedState(frame)
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    var fabX by remember { mutableFloatStateOf(with(density) { (config.screenWidthDp.dp.toPx()) - 260f }) }
    var fabY by remember { mutableFloatStateOf(with(density) { 260f }) }
    var fabOpen by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf<String?>(null) }

    fun sendCtrlAltDel() {
        client.sendKey(Keymap.CTRL, true)
        client.sendKey(Keymap.ALT, true)
        client.sendKey(0x53, true)
        client.sendKey(0x53, false)
        client.sendKey(Keymap.ALT, false)
        client.sendKey(Keymap.CTRL, false)
    }

    fun sendBack() {
        client.sendKey(Keymap.ALT, true)
        client.sendKey(Keymap.LEFT, true)
        client.sendKey(Keymap.LEFT, false)
        client.sendKey(Keymap.ALT, false)
    }

    fun sendHome() {
        client.sendKey(Keymap.WIN, true)
        client.sendKey(Keymap.WIN, false)
    }

    fun sendRecents() {
        client.sendKey(Keymap.ALT, true)
        client.sendKey(Keymap.TAB, true)
        client.sendKey(Keymap.TAB, false)
        client.sendKey(Keymap.ALT, false)
    }

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun saveFrame() {
        val bmp = frame ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val name = "ChapulinRemoto_$timestamp.png"
                val resolver = context.contentResolver

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Chapulin Remoto")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let {
                        val out: OutputStream? = resolver.openOutputStream(it)
                        out?.use { stream -> bmp.compress(Bitmap.CompressFormat.PNG, 100, stream) }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(it, values, null, null)
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val folder = File(dir, "Chapulin Remoto")
                    if (!folder.exists()) folder.mkdirs()
                    val file = File(folder, name)
                    file.outputStream().use { stream -> bmp.compress(Bitmap.CompressFormat.PNG, 100, stream) }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Captura guardada en Imágenes/Chapulin Remoto", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "No se pudo guardar: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val listener = remember {
        object : RemoteClient.Listener {
            override fun onConnected(hostName: String) {
                connected = true
                status = "Conectado · $hostName"
            }

            override fun onFrame(bitmap: Bitmap) {
                frame = bitmap
            }

            override fun onClosed(reason: String) {
                connected = false
                status = reason
            }
        }
    }

    LaunchedEffect(target) {
        val failure = withContext(Dispatchers.IO) {
            try {
                client.connect(target.address, target.port, target.pin, listener)
                null
            } catch (t: Throwable) {
                t.message ?: "No se pudo conectar"
            }
        }
        if (failure != null) {
            connected = false
            status = failure
        }
    }

    DisposableEffect(Unit) {
        onDispose { client.close() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { container = it }
            .onKeyEvent { event ->
                if (!connected) return@onKeyEvent false
                val isDown = event.type == KeyEventType.KeyDown
                val scancode = mapKeyToScancode(event.nativeKeyEvent)
                if (scancode != null) {
                    client.sendKey(scancode, isDown)
                    return@onKeyEvent true
                }
                if (event.type == KeyEventType.KeyDown) {
                    val native = event.nativeKeyEvent
                    val char = native.unicodeChar
                    if (char > 0 && char != 0x10) {
                        client.sendText(char)
                        return@onKeyEvent true
                    }
                }
                false
            }
            .pointerInput(container, connected) {
                if (!connected || container == IntSize.Zero) return@pointerInput

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startedAt = System.currentTimeMillis()
                    var moved = false
                    var pointerCount = 1

                    var ev: androidx.compose.ui.input.pointer.PointerEvent
                    do {
                        ev = awaitPointerEvent()
                        pointerCount = maxOf(pointerCount, ev.changes.count { it.pressed })
                        val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                        val distance = (change.position - down.position).getDistance()
                        if (!moved && distance > viewConfiguration.touchSlop) moved = true
                        if (moved && pointerCount == 1) {
                            mapPoint(change.position, container, currentFrame, scale, offsetX, offsetY)
                                ?.let { (x, y) -> client.sendMove(x, y) }
                        }
                        change.consume()
                    } while (ev.changes.any { it.pressed })

                    if (!moved) {
                        val point = mapPoint(down.position, container, currentFrame, scale, offsetX, offsetY)
                        if (point != null) {
                            val held = System.currentTimeMillis() - startedAt
                            val button = if (held >= 550 || pointerCount >= 2) 1 else 0
                            client.sendClick(button, point.first, point.second)
                        }
                    }
                }
            }
    ) {
        frame?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Pantalla remota",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
                contentScale = ContentScale.Fit
            )
        }

        if (frame == null && connected) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = ChapulinGreen
            )
        }

        // Bolsita flotante arrastrable: el unico control de la sesion.
        if (connected && frame != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(fabX.toInt(), fabY.toInt()) }
                        .pointerInput(Unit) {
                            detectDragGestures { _, dragAmount ->
                                fabX += dragAmount.x
                                fabY += dragAmount.y
                            }
                        }
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        AnimatedVisibility(visible = fabOpen) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Color(0xF0101820),
                                modifier = Modifier.padding(bottom = 8.dp).width(300.dp)
                            ) {
                                LazyColumn(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Controles rapidos de sesion (antes en la barra superior).
                                    item { SectionHeader("Controles de sesión", expanded = section == "quick") { section = if (section == "quick") null else "quick" } }
                                    if (section == "quick") {
                                        item { MenuItem(Icons.Filled.PowerSettingsNew, "Salir de la sesión") { client.close(); onExit() } }
                                        item { MenuItem(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") { sendBack() } }
                                        item { MenuItem(Icons.Filled.Home, "Inicio") { sendHome() } }
                                        item { MenuItem(Icons.Filled.WebAsset, "Aplicaciones recientes") { sendRecents() } }
                                        item { MenuItem(Icons.Filled.Keyboard, if (keyboardVisible) "Ocultar teclado" else "Mostrar teclado") { keyboardVisible = !keyboardVisible } }
                                        item { MenuItem(Icons.Filled.Screenshot, "Tomar captura") { saveFrame() } }
                                        item { MenuItem(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Alejar") { scale = (scale - 0.25f).coerceAtLeast(1f) } }
                                        item { MenuItem(Icons.Filled.CenterFocusStrong, "Ajustar a pantalla") { scale = 1f; offsetX = 0f; offsetY = 0f } }
                                        item { MenuItem(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Acercar") { scale = (scale + 0.25f).coerceAtMost(5f) } }
                                    }

                                    item { SectionHeader("Acciones", expanded = section == "acciones") { section = if (section == "acciones") null else "acciones" } }
                                    if (section == "acciones") {
                                        item { MenuItem(Icons.Filled.RecordVoiceOver, "Grabar sesión") { toast("Grabar sesión (requiere soporte del host)") } }
                                        item { MenuItem(Icons.Filled.Security, "Modo privacidad") { toast("Modo privacidad (requiere soporte del host)") } }
                                        item { MenuItem(Icons.Filled.RestartAlt, "Reiniciar ordenador") { toast("Reiniciar ordenador (requiere soporte del host)") } }
                                        item { MenuItem(Icons.Filled.WifiTethering, "Túnel TCP") { toast("Túnel TCP (requiere soporte del host)") } }
                                        item { MenuItem(Icons.Filled.VpnKey, "Ctrl+Alt+Del") { sendCtrlAltDel(); toast("Ctrl+Alt+Del enviado") } }
                                        item { MenuItem(Icons.Filled.AddToHomeScreen, "Solicitar elevación de permisos") { toast("Solicitar elevación (requiere soporte del host)") } }
                                        item { MenuItem(Icons.Filled.Screenshot, "Tomar captura") { saveFrame() } }
                                        item { MenuItem(Icons.Filled.SwapHoriz, "Aceptar archivos") { toast("Aceptar archivos (requiere soporte del host)") } }
                                        item { MenuItem(Icons.Filled.SwapHoriz, "Rechazar archivos") { toast("Rechazar archivos (requiere soporte del host)") } }
                                        item { MenuItem(Icons.Filled.SwapHoriz, "Iniciar transferencia de archivos") { toast("Transferencia de archivos (requiere soporte del host)") } }
                                    }

                                    item { SectionHeader("Monitor remoto", expanded = section == "monitor") { section = if (section == "monitor") null else "monitor" } }
                                    if (section == "monitor") {
                                        item { MenuItem(Icons.Filled.CropFree, "Modo pantalla completa") { scale = 1f; offsetX = 0f; offsetY = 0f } }
                                        item { MenuItem(Icons.Filled.Tv, "Monitor 1") { toast("Cambio de monitor (requiere soporte del host)") } }
                                    }

                                    item { SectionHeader("Opciones de entrada", expanded = section == "input") { section = if (section == "input") null else "input" } }
                                    if (section == "input") {
                                        item { MenuItem(Icons.Filled.Keyboard, "Mostrar teclado especial") { keyboardVisible = !keyboardVisible } }
                                        item { MenuItem(Icons.Filled.Keyboard, "Teclado normal") { keyboardVisible = false } }
                                        item { MenuItem(Icons.Filled.TouchApp, "Emulación de ratón") { toast("Emulación de ratón (requiere soporte)") } }
                                        item { MenuItem(Icons.Filled.TouchApp, "Emulación de panel táctil") { toast("Emulación táctil (requiere soporte)") } }
                                        item { MenuItem(Icons.Filled.SwapHoriz, "Transmitir gestos") { toast("Transmitir gestos (requiere soporte)") } }
                                    }

                                    item { SectionHeader("Ajustes de sesión", expanded = section == "session") { section = if (section == "session") null else "session" } }
                                    if (section == "session") {
                                        item { MenuItem(Icons.Filled.Keyboard, "Entrada de datos") { keyboardVisible = true } }
                                        item { MenuItem(Icons.Filled.Computer, "Presentación") { scale = 1f; offsetX = 0f; offsetY = 0f } }
                                        item { MenuItem(Icons.Filled.VolumeUp, "Sonido") { toast("Sonido (requiere soporte del host)") } }
                                        item { MenuItem(Icons.Filled.Security, "Permisos") { toast("Permisos (requiere soporte del host)") } }
                                    }
                                }
                            }
                        }
                        FilledIconButton(
                            onClick = { fabOpen = !fabOpen },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xE6101820), contentColor = ChapulinGreen),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = "Acciones", modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = keyboardVisible && connected,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
        ) {
            QuickKeyboard(
                onKey = { code, down -> client.sendKey(code, down) },
                onChar = { c ->
                    val scan = Keymap.forChar(c)
                    if (scan != null) {
                        client.sendKey(scan, true)
                        client.sendKey(scan, false)
                    } else {
                        client.sendText(c.code)
                    }
                }
            )
        }

        if (!connected) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xEE101820))) {
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painter = painterResource(id = R.drawable.logo_chapulin), contentDescription = null, modifier = Modifier.size(76.dp))
                    Text(text = status, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                    TextButton(onClick = { client.close(); onExit() }) {
                        Text("Volver", color = ChapulinGreen)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Icon(if (expanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown, contentDescription = null, tint = Color.White)
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = ChapulinGreen, modifier = Modifier.size(18.dp))
        Text(text, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun QuickKeyboard(onKey: (Int, Boolean) -> Unit, onChar: (Char) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        color = Color(0xF0101820)
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val row1 = listOf('1','2','3','4','5','6','7','8','9','0')
            val row2 = listOf('q','w','e','r','t','y','u','i','o','p')
            val row3 = listOf('a','s','d','f','g','h','j','k','l')
            val row4 = listOf('z','x','c','v','b','n','m')
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item { K("Esc") { onKey(Keymap.ESC,true); onKey(Keymap.ESC,false) } }
                items(row1) { c -> K("$c") { onChar(c) } }
                item { K("⌫") { onKey(Keymap.BACKSPACE,true); onKey(Keymap.BACKSPACE,false) } }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item { K("Tab") { onKey(Keymap.TAB,true); onKey(Keymap.TAB,false) } }
                items(row2) { c -> K("$c") { onChar(c) } }
                item { K("↵") { onKey(Keymap.ENTER,true); onKey(Keymap.ENTER,false) } }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Spacer(Modifier.width(10.dp)) }
                items(row3) { c -> K("$c") { onChar(c) } }
                item { Spacer(Modifier.width(10.dp)) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item { K("⇧") { onKey(Keymap.SHIFT,true); onKey(Keymap.SHIFT,false) } }
                items(row4) { c -> K("$c") { onChar(c) } }
                item { Spacer(Modifier.weight(1f)) }
                item { K("Espacio") { onChar(' ') } }
                item { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun K(label: String, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF1F2E3D), contentColor = Color.White),
        modifier = Modifier.size(38.dp)
    ) {
        Text(label, fontSize = 12.sp)
    }
}

private fun mapKeyToScancode(event: KeyEvent): Int? {
    return when (event.keyCode) {
        KeyEvent.KEYCODE_BACK -> Keymap.ESC
        KeyEvent.KEYCODE_ENTER -> Keymap.ENTER
        KeyEvent.KEYCODE_DEL -> Keymap.BACKSPACE
        KeyEvent.KEYCODE_TAB -> Keymap.TAB
        KeyEvent.KEYCODE_SPACE -> Keymap.SPACE
        KeyEvent.KEYCODE_DPAD_LEFT -> Keymap.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Keymap.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> Keymap.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Keymap.DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> Keymap.HOME
        KeyEvent.KEYCODE_MOVE_END -> Keymap.END
        KeyEvent.KEYCODE_PAGE_UP -> Keymap.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> Keymap.PAGE_DOWN
        KeyEvent.KEYCODE_FORWARD_DEL -> Keymap.DELETE
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> Keymap.CTRL
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> Keymap.ALT
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> Keymap.SHIFT
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> Keymap.WIN
        else -> null
    }
}

private fun mapPoint(
    position: Offset,
    container: IntSize,
    frame: Bitmap?,
    scale: Float,
    offsetX: Float,
    offsetY: Float
): Pair<Int, Int>? {
    if (frame == null || frame.width <= 0 || frame.height <= 0) return null
    if (container.width <= 0 || container.height <= 0) return null

    val baseScale = min(
        container.width.toFloat() / frame.width,
        container.height.toFloat() / frame.height
    )
    val drawnWidth = frame.width * baseScale * scale
    val drawnHeight = frame.height * baseScale * scale
    val ox = (container.width - drawnWidth) / 2f + offsetX
    val oy = (container.height - drawnHeight) / 2f + offsetY

    val nx = ((position.x - ox) / drawnWidth).coerceIn(0f, 1f)
    val ny = ((position.y - oy) / drawnHeight).coerceIn(0f, 1f)

    return (nx * 65535f).roundToInt() to (ny * 65535f).roundToInt()
}
