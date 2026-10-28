# Chapulín Remoto

Control remoto de escritorio **por red local**, sin nube, sin cuentas y sin depender de internet.

> "Sencillo, rápido y sin intermediarios." 🦗

---

## ¿Qué es?

Chapulín Remoto es una alternativa libre a AnyDesk/TeamViewer pensada para **LAN**:
el PC y el teléfono se hablan directamente, sin servidores de rendezvous y sin que
los datos salgan de tu red.

Casos de uso:

- Controlar tu PC Windows **desde el móvil Android** tumbado en el sofá.
- Usar el PC desde el móvil **en un hotspot sin datos** (habitación, campo, obra).
- Soporte técnico a un familiar **en la misma red**, sin instalar nada raro.
- Mandar órdenes a un equipo de la oficina **sin abrir puertos a internet**.

## ¿Qué NO es (todavía)?

- No sustituye a AnyDesk para conexiones **entre redes distintas** (NAT, 4G).
  Eso requeriría relay o NAT traversal: está en el roadmap, no en el MVP.
- No funciona **sin ninguna red**. Si no hay WiFi, hotspot ni USB-tethering,
  no existe canal de comunicación. "Sin internet" sí; "sin red" no.
- La versión actual **no cifra el tráfico**. Solo úsala en redes de confianza y
  con el PIN activo. El cifrado (Noise/TLS) está planificado en la fase 4.

## Características objetivo

| Fase | Función |
|------|---------|
| MVP  | Ver pantalla del PC en Android, controlar ratón con el dedo |
| 2    | Teclado, rueda, clic derecho, gestos, multi-monitor |
| 3    | Vídeo H.264 por hardware (baja latencia, poco ancho de banda) |
| 4    | Cifrado extremo a extremo, emparejamiento con PIN/QR |
| 5    | Portapapeles, transferencia de archivos, audio |
| 6    | Descubrimiento automático (mDNS), wake-on-LAN, modo desatendido |

## Arquitectura en 30 segundos

```
┌──────────────────────────┐             ┌──────────────────────────┐
│  Windows (PC anfitrión)  │             │   Android (cliente)      │
│  C++20 / Win32           │   TCP LAN   │   Kotlin / Compose       │
│                          │◄───────────►│                          │
│  Captura DXGI/GDI        │  puerto     │  MediaCodec / Bitmap     │
│  Codifica JPEG → H.264   │   47474     │  Vista + gestos táctiles │
│  Inyecta input SendInput │             │  Envía eventos al PC     │
└──────────────────────────┘             └──────────────────────────┘
              ▲
              └── Sin internet. Sin servidores. Solo tu red.
```

Detalle completo en [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md) y
[`docs/PROTOCOLO.md`](docs/PROTOCOLO.md).

## Estructura del repositorio

```
chapulin-remoto/
├─ docs/           Documentación técnica (arquitectura, protocolo, roadmap)
├─ windows/        Host y cliente de escritorio en C++20 (CMake + MSVC)
├─ android/        Cliente Android en Kotlin (Gradle + Compose)
└─ tools/          Utilidades de desarrollo y pruebas
```

## Estado del proyecto

**Fase 0 — definición.** El repositorio contiene la arquitectura, el protocolo
y la estructura de carpetas. Aún no hay binarios funcionales.

## Compilar

### Windows (host)

Requisitos: Visual Studio 2022 Build Tools (workload *Desarrollo para el escritorio con C++*),
CMake ≥ 3.25, Ninja.

```powershell
cmake -S windows -B build/windows -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/windows
```

### Android (cliente)

Requisitos: JDK 17+, Android SDK (API 35), Gradle 8.x+.

```powershell
cd android
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Seguridad y uso responsable

- El anfitrión **siempre** muestra un aviso visible cuando hay una conexión activa,
  y puede cortarla con un clic. Sin conexiones silenciosas.
- El acceso requiere **PIN de 6 dígitos** que rota en cada sesión.
- Este software es para **tus equipos o equipos con permiso explícito**. Usarlo
  para acceder a un dispositivo ajeno es delito en la mayoría de países.

## Licencia

GNU General Public License v3.0 — ver [`LICENSE`](LICENSE).

Copyright (C) 2026 Guillermo (Fluxionics)
