package com.chapulin.remoto.ui

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

enum class InputMode {
    TOUCH, TRACKPAD
}

@Composable
fun RemoteScreen(target: HostTarget, onExit: () -> Unit) {
    val client = remember { RemoteClient() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("Conectando a ${target.address}:${target.port}…") }
    var connected by remember { mutableStateOf(false) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    var quickKeyboardVisible by remember { mutableStateOf(false) }
    var nativeKeyboardVisible by remember { mutableStateOf(false) }
    var nativeText by remember { mutableStateOf("") }

    var inputMode by remember { mutableStateOf(InputMode.TOUCH) }
    var cursorX by remember { mutableIntStateOf(32767) }
    var cursorY by remember { mutableIntStateOf(32767) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val currentFrame by rememberUpdatedState(frame)
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    var fabX by remember { mutableFloatStateOf(with(density) { (config.screenWidthDp.dp.toPx()) - 220f }) }
    var fabY by remember { mutableFloatStateOf(with(density) { 220f }) }
    var fabOpen by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun sendSequence(textToSend: String) {
        scope.launch(Dispatchers.IO) {
            for (ch in textToSend) {
                val scan = Keymap.forChar(ch)
                if (scan != null) {
                    client.sendKey(scan, true)
                    client.sendKey(scan, false)
                } else {
                    client.sendText(ch.code)
                }
                delay(30)
            }
        }
    }

    fun sendCtrlAltDel() {
        client.sendKey(Keymap.CTRL, true)
        client.sendKey(Keymap.ALT, true)
        client.sendKey(Keymap.DELETE, true)
        client.sendKey(Keymap.DELETE, false)
        client.sendKey(Keymap.ALT, false)
        client.sendKey(Keymap.CTRL, false)
    }

    fun sendBack() {
        client.sendKey(Keymap.ESC, true)
        client.sendKey(Keymap.ESC, false)
    }

    fun sendHome() {
        client.sendKey(Keymap.WIN, true)
        client.sendKey(Keymap.WIN, false)
    }

    fun sendRecents() {
        client.sendKey(Keymap.WIN, true)
        client.sendKey(Keymap.TAB, true)
        client.sendKey(Keymap.TAB, false)
        client.sendKey(Keymap.WIN, false)
    }

    fun sendShowDesktop() {
        client.sendKey(Keymap.WIN, true)
        client.sendKey(Keymap.forChar('d') ?: 0x20, true)
        client.sendKey(Keymap.forChar('d') ?: 0x20, false)
        client.sendKey(Keymap.WIN, false)
    }

    fun sendLockPc() {
        client.sendKey(Keymap.WIN, true)
        client.sendKey(Keymap.forChar('l') ?: 0x26, true)
        client.sendKey(Keymap.forChar('l') ?: 0x26, false)
        client.sendKey(Keymap.WIN, false)
    }

    fun sendTaskManager() {
        client.sendKey(Keymap.CTRL, true)
        client.sendKey(Keymap.SHIFT, true)
        client.sendKey(Keymap.ESC, true)
        client.sendKey(Keymap.ESC, false)
        client.sendKey(Keymap.SHIFT, false)
        client.sendKey(Keymap.CTRL, false)
    }

    fun sendProjectScreen() {
        client.sendKey(Keymap.WIN, true)
        client.sendKey(Keymap.forChar('p') ?: 0x19, true)
        client.sendKey(Keymap.forChar('p') ?: 0x19, false)
        client.sendKey(Keymap.WIN, false)
    }

    fun sendRestartPc() {
        toast("Enviando orden de reinicio al PC...")
        scope.launch(Dispatchers.IO) {
            client.sendKey(Keymap.WIN, true)
            client.sendKey(Keymap.forChar('r') ?: 0x13, true)
            client.sendKey(Keymap.forChar('r') ?: 0x13, false)
            client.sendKey(Keymap.WIN, false)
            delay(300)
            sendSequence("shutdown /r /t 5\n")
        }
    }

    fun sendShutdownPc() {
        toast("Enviando orden de apagado al PC...")
        scope.launch(Dispatchers.IO) {
            client.sendKey(Keymap.WIN, true)
            client.sendKey(Keymap.forChar('r') ?: 0x13, true)
            client.sendKey(Keymap.forChar('r') ?: 0x13, false)
            client.sendKey(Keymap.WIN, false)
            delay(300)
            sendSequence("shutdown /s /t 5\n")
        }
    }

    fun sendClipboardToPc() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrEmpty()) {
                sendSequence(text)
                toast("Texto enviado al PC")
            } else {
                toast("Portapapeles del celular vacío")
            }
        } catch (t: Throwable) {
            toast("No se pudo leer portapapeles: ${t.message}")
        }
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
                    toast("Captura guardada en Imágenes/Chapulin Remoto")
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    toast("No se pudo guardar captura: ${t.message}")
                }
            }
        }
    }

    val listener = remember(scope) {
        object : RemoteClient.Listener {
            override fun onConnected(hostName: String) {
                scope.launch(Dispatchers.Main) {
                    connected = true
                    status = "Conectado · $hostName"
                }
            }

            override fun onFrame(bitmap: Bitmap) {
                scope.launch(Dispatchers.Main) {
                    frame = bitmap
                }
            }

            override fun onClosed(reason: String) {
                scope.launch(Dispatchers.Main) {
                    connected = false
                    status = reason
                }
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
            .padding(top = 24.dp)
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
            .pointerInput(container, connected, inputMode) {
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

                        if (inputMode == InputMode.TOUCH) {
                            val distance = (change.position - down.position).getDistance()
                            if (!moved && distance > viewConfiguration.touchSlop) moved = true
                            if (moved && pointerCount == 1) {
                                mapPoint(change.position, container, currentFrame, scale, offsetX, offsetY)
                                    ?.let { (x, y) -> client.sendMove(x, y) }
                            }
                        } else {
                            val delta = change.position - change.previousPosition
                            if (delta.x != 0f || delta.y != 0f) {
                                moved = true
                                if (pointerCount == 1) {
                                    val sensitivity = 120f
                                    cursorX = (cursorX + (delta.x / container.width) * sensitivity * 65535f).roundToInt().coerceIn(0, 65535)
                                    cursorY = (cursorY + (delta.y / container.height) * sensitivity * 65535f).roundToInt().coerceIn(0, 65535)
                                    client.sendMove(cursorX, cursorY)
                                } else if (pointerCount >= 2) {
                                    val wheelDelta = (-delta.y * 5).roundToInt().coerceIn(-1200, 1200)
                                    if (wheelDelta != 0) client.sendWheel(wheelDelta)
                                }
                            }
                        }
                        change.consume()
                    } while (ev.changes.any { it.pressed })

                    if (!moved) {
                        if (inputMode == InputMode.TOUCH) {
                            val point = mapPoint(down.position, container, currentFrame, scale, offsetX, offsetY)
                            if (point != null) {
                                val held = System.currentTimeMillis() - startedAt
                                val button = if (held >= 500 || pointerCount >= 2) 1 else 0
                                client.sendClick(button, point.first, point.second)
                            }
                        } else {
                            val button = if (pointerCount >= 2) 1 else 0
                            client.sendClick(button, cursorX, cursorY)
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

        BasicTextField(
            value = nativeText,
            onValueChange = { newText ->
                if (newText.length < nativeText.length) {
                    val count = nativeText.length - newText.length
                    repeat(count) {
                        client.sendKey(Keymap.BACKSPACE, true)
                        client.sendKey(Keymap.BACKSPACE, false)
                    }
                } else if (newText.length > nativeText.length) {
                    val added = newText.substring(nativeText.length)
                    for (ch in added) {
                        val scan = Keymap.forChar(ch)
                        if (scan != null) {
                            client.sendKey(scan, true)
                            client.sendKey(scan, false)
                        } else {
                            client.sendText(ch.code)
                        }
                    }
                }
                nativeText = newText
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
        )

        if (connected && frame != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.offset { IntOffset(fabX.toInt(), fabY.toInt()) }
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        AnimatedVisibility(visible = fabOpen) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Color(0xF0101820),
                                modifier = Modifier.padding(bottom = 8.dp).width(310.dp)
                            ) {
                                LazyColumn(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    item { SectionHeader("Controles rápidos", expanded = section == "quick") { section = if (section == "quick") null else "quick" } }
                                    if (section == "quick") {
                                        item { MenuItem(Icons.Filled.PowerSettingsNew, "Salir de la sesión") { client.close(); onExit() } }
                                        item { MenuItem(Icons.AutoMirrored.Filled.ArrowBack, "Atrás (Esc)") { sendBack() } }
                                        item { MenuItem(Icons.Filled.Home, "Inicio (Menú Windows)") { sendHome() } }
                                        item { MenuItem(Icons.Filled.WebAsset, "Aplicaciones recientes (Win+Tab)") { sendRecents() } }
                                        item { MenuItem(Icons.Filled.Keyboard, "Teclado rápido") { quickKeyboardVisible = !quickKeyboardVisible } }
                                        item { MenuItem(Icons.Filled.Keyboard, "Teclado del celular") { nativeKeyboardVisible = true; focusRequester.requestFocus(); keyboardController?.show() } }
                                        item { MenuItem(Icons.Filled.Screenshot, "Tomar captura de pantalla") { saveFrame() } }
                                        item { MenuItem(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Alejar zoom") { scale = (scale - 0.25f).coerceAtLeast(0.5f) } }
                                        item { MenuItem(Icons.Filled.CenterFocusStrong, "Ajustar a pantalla") { scale = 1f; offsetX = 0f; offsetY = 0f } }
                                        item { MenuItem(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Acercar zoom") { scale = (scale + 0.25f).coerceAtMost(4f) } }
                                    }

                                    item { SectionHeader("Acciones del sistema", expanded = section == "acciones") { section = if (section == "acciones") null else "acciones" } }
                                    if (section == "acciones") {
                                        item { MenuItem(Icons.Filled.Security, "Bloquear PC (Modo privacidad)") { sendLockPc(); toast("PC Bloqueado") } }
                                        item { MenuItem(Icons.Filled.VpnKey, "Enviar Ctrl+Alt+Del") { sendCtrlAltDel(); toast("Ctrl+Alt+Del enviado") } }
                                        item { MenuItem(Icons.Filled.WebAsset, "Administrador de tareas") { sendTaskManager() } }
                                        item { MenuItem(Icons.Filled.Home, "Mostrar Escritorio (Win+D)") { sendShowDesktop() } }
                                        item { MenuItem(Icons.Filled.ContentPaste, "Pegar texto del portapapeles") { sendClipboardToPc() } }
                                        item { MenuItem(Icons.Filled.RestartAlt, "Reiniciar ordenador") { sendRestartPc() } }
                                        item { MenuItem(Icons.Filled.PowerSettingsNew, "Apagar ordenador") { sendShutdownPc() } }
                                    }

                                    item { SectionHeader("Monitor y Pantalla", expanded = section == "monitor") { section = if (section == "monitor") null else "monitor" } }
                                    if (section == "monitor") {
                                        item { MenuItem(Icons.Filled.CropFree, "Centrar vista") { scale = 1f; offsetX = 0f; offsetY = 0f } }
                                        item { MenuItem(Icons.Filled.Tv, "Cambiar pantalla / Proyectar (Win+P)") { sendProjectScreen() } }
                                    }

                                    item { SectionHeader("Modos de Entrada", expanded = section == "input") { section = if (section == "input") null else "input" } }
                                    if (section == "input") {
                                        item { MenuItem(Icons.Filled.TouchApp, if (inputMode == InputMode.TOUCH) "Modo Táctil ✓" else "Modo Táctil") { inputMode = InputMode.TOUCH; toast("Modo Táctil activo") } }
                                        item { MenuItem(Icons.Filled.Mouse, if (inputMode == InputMode.TRACKPAD) "Modo Panel Táctil (Trackpad) ✓" else "Modo Panel Táctil (Trackpad)") { inputMode = InputMode.TRACKPAD; toast("Modo Trackpad activo") } }
                                        item { MenuItem(Icons.Filled.Keyboard, "Abrir teclado del celular") { nativeKeyboardVisible = true; focusRequester.requestFocus(); keyboardController?.show() } }
                                        item { MenuItem(Icons.Filled.Keyboard, "Mostrar barra de teclas especiales") { quickKeyboardVisible = !quickKeyboardVisible } }
                                    }

                                    item { SectionHeader("Ajustes de Vídeo y Sonido", expanded = section == "session") { section = if (section == "session") null else "session" } }
                                    if (section == "session") {
                                        item { MenuItem(Icons.Filled.HighQuality, "Calidad Alta (1080p, 30fps)") { client.setQuality(1920, 80, 30); toast("Calidad Alta") } }
                                        item { MenuItem(Icons.Filled.Speed, "Calidad Media (720p, 20fps)") { client.setQuality(1280, 60, 20); toast("Calidad Media") } }
                                        item { MenuItem(Icons.Filled.Speed, "Calidad Económica (480p, 15fps)") { client.setQuality(854, 40, 15); toast("Calidad Económica") } }
                                        item { MenuItem(Icons.Filled.VolumeUp, "Subir volumen PC") { client.sendKey(Keymap.VOL_UP, true); client.sendKey(Keymap.VOL_UP, false) } }
                                        item { MenuItem(Icons.Filled.VolumeDown, "Bajar volumen PC") { client.sendKey(Keymap.VOL_DOWN, true); client.sendKey(Keymap.VOL_DOWN, false) } }
                                        item { MenuItem(Icons.Filled.VolumeMute, "Silenciar PC") { client.sendKey(Keymap.VOL_MUTE, true); client.sendKey(Keymap.VOL_MUTE, false) } }
                                        item { MenuItem(Icons.Filled.PlayArrow, "Play / Pausa") { client.sendKey(Keymap.MEDIA_PLAY, true); client.sendKey(Keymap.MEDIA_PLAY, false) } }
                                        item { MenuItem(Icons.Filled.SkipNext, "Siguiente canción") { client.sendKey(Keymap.MEDIA_NEXT, true); client.sendKey(Keymap.MEDIA_NEXT, false) } }
                                        item { MenuItem(Icons.Filled.SkipPrevious, "Canción anterior") { client.sendKey(Keymap.MEDIA_PREV, true); client.sendKey(Keymap.MEDIA_PREV, false) } }
                                    }
                                }
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = Color(0xE6101820),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .size(56.dp)
                                .pointerInput(container) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        var isDrag = false
                                        do {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            val delta = change.position - change.previousPosition
                                            if (delta.getDistance() > 0.5f) {
                                                isDrag = true
                                                val maxX = (container.width - 150).toFloat().coerceAtLeast(0f)
                                                val maxY = (container.height - 150).toFloat().coerceAtLeast(0f)
                                                fabX = (fabX + delta.x).coerceIn(0f, maxX)
                                                fabY = (fabY + delta.y).coerceIn(0f, maxY)
                                            }
                                            change.consume()
                                        } while (event.changes.any { it.pressed })

                                        if (!isDrag) {
                                            fabOpen = !fabOpen
                                        }
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Image(
                                    painter = painterResource(id = R.drawable.logo_chapulin),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = quickKeyboardVisible && connected,
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
    // Modificadoras pegajosas: Ctrl/Alt/Shift/Win quedan activas hasta la
    // siguiente tecla (one-shot), para poder hacer combos como Ctrl+C.
    val heldMods = remember { mutableStateListOf<Int>() }

    fun withMods(block: () -> Unit) {
        for (m in heldMods) onKey(m, true)
        block()
        for (m in heldMods.asReversed()) onKey(m, false)
        heldMods.clear()
    }

    fun toggleMod(code: Int) {
        if (!heldMods.remove(code)) heldMods.add(code)
    }

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
                item { K("Esc", active = false) { withMods { onKey(Keymap.ESC, true); onKey(Keymap.ESC, false) } } }
                items(row1) { c -> K("$c", active = false) { withMods { onChar(c) } } }
                item { K("⌫", active = false) { withMods { onKey(Keymap.BACKSPACE, true); onKey(Keymap.BACKSPACE, false) } } }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item { K("Tab", active = false) { withMods { onKey(Keymap.TAB, true); onKey(Keymap.TAB, false) } } }
                items(row2) { c -> K("$c", active = false) { withMods { onChar(c) } } }
                item { K("↵", active = false) { withMods { onKey(Keymap.ENTER, true); onKey(Keymap.ENTER, false) } } }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item { K("Ctrl", active = Keymap.CTRL in heldMods) { toggleMod(Keymap.CTRL) } }
                items(row3) { c -> K("$c", active = false) { withMods { onChar(c) } } }
                item { K("Alt", active = Keymap.ALT in heldMods) { toggleMod(Keymap.ALT) } }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item { K("⇧", active = Keymap.SHIFT in heldMods) { toggleMod(Keymap.SHIFT) } }
                items(row4) { c -> K("$c", active = false) { withMods { onChar(c) } } }
                item { K("Win", active = Keymap.WIN in heldMods) { toggleMod(Keymap.WIN) } }
                item { K("Espacio", active = false) { withMods { onChar(' ') } } }
            }
        }
    }
}

@Composable
private fun K(label: String, active: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) Color(0xFF7CB342) else Color(0xFF1F2E3D),
            contentColor = Color.White
        ),
        modifier = Modifier.size(38.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
