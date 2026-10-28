# Arquitectura

## 1. Principios de diseño

1. **Directo, sin intermediarios.** El cliente conecta al host por IP:puerto.
   No hay servidor de señalización, ni cuentas, ni telemetría.
2. **LAN primero.** Optimizado para RTT < 5 ms y 20–100 Mbps. No intentamos
   sobrevivir a una conexión 4G mala: para eso está la fase 6 (relay opcional).
3. **Un solo binario de host.** Sin dependencias externas pesadas: Win32 + D3D11
   + Media Foundation. Nada de Electron, nada de .NET.
4. **El input nunca se pierde por la cola de vídeo.** Dos canales lógicos en el
   mismo socket TCP con prioridad: los eventos de entrada van en su propio
   stream y se procesan antes que los frames.
5. **Visible y consentido.** El host muestra estado de sesión siempre.

## 2. Roles

| Rol | Plataforma | Qué hace |
|-----|-----------|----------|
| **Host** | Windows 10/11 x64 | Captura pantalla, codifica, sirve, inyecta input |
| **Cliente** | Android 8+ | Muestra vídeo, captura gestos, envía eventos |
| **Cliente** | Windows (fase 2) | Igual que Android pero en escritorio |

## 3. Módulos del host (C++)

```
windows/
├─ CMakeLists.txt
├─ src/
│  ├─ main.cpp          Punto de entrada, bandeja, ciclo de vida
│  ├─ app/
│  │  ├─ session.*      Estado de sesión, PIN, consentimiento
│  │  └─ config.*       Ajustes persistentes (puerto, calidad, monitor)
│  ├─ capture/
│  │  ├─ capturer.h     Interfaz común de captura
│  │  ├─ dxgi_capturer.* Desktop Duplication API (fase 3)
│  │  └─ gdi_capturer.*  BitBlt + escalado (MVP)
│  ├─ codec/
│  │  ├─ encoder.h
│  │  ├─ wic_jpeg.*     JPEG con WIC (MVP)
│  │  └─ mf_h264.*      H.264 por hardware con Media Foundation (fase 3)
│  ├─ net/
│  │  ├─ server.*       Listen/accept, hilos por cliente
│  │  ├─ connection.*   Framing, colas, backpressure
│  │  └─ discovery.*    mDNS `_chapulin._tcp` (fase 6)
│  ├─ input/
│  │  └─ injector.*     SendInput, mapeo de coordenadas, teclas
│  └─ ui/
│     └─ tray.*         Icono de bandeja + ventana de estado Win32
└─ tests/
```

### Flujo de un frame

```
DXGI/GDI → BGRA → (escala) → codificador → header + payload → TCP → Android
```

### Flujo de un toque

```
Android MotionEvent → normaliza a 0..65535 → InputPacket → TCP → SendInput
```

## 4. Módulos del cliente Android (Kotlin)

```
android/
└─ app/src/main/java/com/chapulin/remoto/
   ├─ MainActivity.kt        UI Compose, navegación
   ├─ ui/ConnectScreen.kt    IP/PIN, historial de hosts
   ├─ ui/RemoteScreen.kt     SurfaceView + gestos
   ├─ net/RemoteClient.kt    Socket, handshake, lectura de paquetes
   ├─ net/Packet.kt          Serialización del protocolo
   ├─ video/JpegDecoder.kt   BitmapFactory off-thread (MVP)
   ├─ video/H264Decoder.kt   MediaCodec asíncrono (fase 3)
   └─ input/GestureMapper.kt MotionEvent → eventos normalizados
```

## 5. Modelo de hilos

**Host**

| Hilo | Responsabilidad |
|------|-----------------|
| Main | UI, bandeja, mensajes Win32 |
| Capture | Captura a ritmo fijo (30 Hz objetivo, configurable) |
| Encode | Cola de frames, codifica en paralelo (1–2 workers) |
| Net TX | Envía por socket, aplica backpressure |
| Net RX | Lee paquetes de input y los encola |
| Inject | Aplica input (orden estricto, nunca bloquea a Capture) |

Regla de oro: **Capture nunca espera a la red**. Si el cliente no consume,
se descartan frames antiguos (política *drop-latest*), nunca se acumulan.

**Android**

- `RemoteClient` en coroutine `Dispatchers.IO` (socket bloqueante).
- Decodificación JPEG en `Dispatchers.Default`; dibujo en el hilo de UI.
- Gestos en el hilo de UI → se encolan a un `Channel` sin bloqueo.

## 6. Rendimiento esperado (LAN 5 GHz)

| Métrica | MVP (JPEG) | Fase 3 (H.264) |
|---------|-----------|----------------|
| Resolución | 1280×720 | 1920×1080 |
| FPS | 10–15 | 30–60 |
| Bitrate | 8–20 Mbps | 2–6 Mbps |
| Latencia extremo a extremo | 80–150 ms | 30–60 ms |
| CPU host (1080p30) | 15–25 % | 3–8 % (GPU) |

## 7. Decisiones y porqués

- **TCP en vez de UDP (MVP):** implementar control de congestión y recuperación
  de paquetes a mano es semanas de trabajo. En LAN, TCP sobra. En fase 3 se
  evalúa RTP/UDP para vídeo si la latencia lo exige.
- **JPEG en vez de PNG:** PNG sin pérdida es 5–10× más caro de codificar y
  mucho más pesado. JPEG a calidad 60–75 es el mejor coste/beneficio para MVP.
- **WIC en vez de libjpeg-turbo:** ya viene en Windows, cero dependencias.
  Si el rendimiento no llega, se cambia a libjpeg-turbo con SIMD.
- **SendInput en vez de hooks:** es la API oficial, funciona con la mayoría de
  apps. Limitación conocida: no puede inyectar en ventanas elevadas (UIPI) ni
  en escritorios seguros (UAC); para eso el host debe ejecutarse elevado.
- **Sin driver de vídeo virtual:** nada de instalar drivers firmados en el MVP.
  Capturamos el escritorio real.

## 8. Riesgos conocidos

| Riesgo | Impacto | Mitigación |
|--------|---------|-----------|
| Windows Defender bloquea el ejecutable sin firmar | Alto | Firmar en fase 6; documentar exclusión |
| UIPI bloquea input en apps elevadas | Medio | Ejecutar host como admin (opt-in) |
| DRM/overlays aparecen en negro con DXGI | Medio | Fallback a GDI + aviso |
| Android mata el socket en background | Medio | Foreground service (fase 2) |
| NAT entre redes distintas | Alto | Fuera de alcance; relay en fase 6 |
