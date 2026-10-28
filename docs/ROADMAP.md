# Roadmap

Cada fase termina con algo **usable**, no con "casi".

## Fase 0 — Definición (actual)

- [x] Repositorio, licencia GPL-3.0
- [x] Arquitectura y protocolo documentados
- [x] Estructura de carpetas
- [ ] Toolchain instalado y build vacío que compila

## Fase 1 — MVP de vídeo (JPEG)

**Objetivo:** ver la pantalla del PC en el móvil a 10–15 fps.

- [ ] Host: captura GDI (`BitBlt`) del monitor primario
- [ ] Host: escalado a 1280 px de ancho máximo
- [ ] Host: codificación JPEG con WIC
- [ ] Host: servidor TCP + handshake + PIN
- [ ] Host: `SendInput` para mover y hacer clic
- [ ] Android: pantalla de conexión (IP + PIN)
- [ ] Android: recepción y dibujo de frames
- [ ] Android: gestos básicos (mover, clic, clic derecho, scroll)
- [ ] Prueba real: PC por WiFi + móvil en la misma red

## Fase 2 — Cliente Windows y teclado

- [ ] Cliente Windows (misma UI que Android, en C++)
- [ ] Teclado físico desde Android (IME)
- [ ] Selección de monitor
- [ ] Foreground service en Android (no perder sesión al cambiar de app)
- [ ] Reconexión automática

## Fase 3 — H.264 por hardware

- [ ] Host: Desktop Duplication (DXGI) en vez de GDI
- [ ] Host: H.264 con Media Foundation (GPU)
- [ ] Android: `MediaCodec` asíncrono
- [ ] Control de bitrate adaptativo según RTT
- [ ] Objetivo: 1080p30 con < 60 ms de latencia

## Fase 4 — Seguridad

- [ ] Handshake CHP/2 con X25519
- [ ] AES-256-GCM por paquete
- [ ] Emparejamiento por QR (sin escribir IP)
- [ ] Huella de host recordada (TOFU) y aviso si cambia
- [ ] Bloqueo tras 3 PIN fallidos

## Fase 5 — Comodidades

- [ ] Portapapeles bidireccional
- [ ] Transferencia de archivos (arrastrar y soltar)
- [ ] Audio del PC al móvil (Opus)
- [ ] Atajos de teclado configurables (Ctrl+Alt+Supr, Alt+Tab)
- [ ] Modo "solo ver" (sin control)

## Fase 6 — Distribución

- [ ] Descubrimiento mDNS (`_chapulin._tcp`)
- [ ] Wake-on-LAN
- [ ] Instalador MSI y firma de código
- [ ] APK en F-Droid / releases de GitHub
- [ ] Relay opcional para conexiones fuera de la LAN (auto-hospedado)
- [ ] Documentación de usuario en español
