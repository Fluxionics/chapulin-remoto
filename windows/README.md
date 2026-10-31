# Host y cliente de Windows

C++20, sin frameworks externos: Win32 + GDI + WIC + Winsock.

| Carpeta | Contenido |
|---------|-----------|
| `src/capture/` | Captura de pantalla (GDI en el MVP, DXGI en fase 3) |
| `src/codec/`   | Compresión de imagen (WIC JPEG, luego Media Foundation H.264) |
| `src/net/`     | Servidor TCP, protocolo CHP/1 |
| `src/input/`   | Inyección de eventos con `SendInput` |
| `src/app/`     | Configuración y estado de sesión |
| `src/util/`    | Log a consola |

## Compilar

```powershell
cmake -S windows -B build/windows -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/windows
```

Requisitos: Visual Studio 2022 Build Tools (workload C++), CMake ≥ 3.25, Ninja.

## Ejecutar

```powershell
.\build\windows\chapulin-host.exe
```

Salida típica:

```
Puerto:   47474
PIN:      482913
Direcciones para conectar desde el movil:
  -> 192.168.1.50:47474
```

Opciones: `--port`, `--fps`, `--width`, `--quality`, `--pin`, `--help`.

## Probar sin el móvil

```powershell
.\tools\test-client.ps1 -Pin 482913
```

Hace el handshake, se autentica y guarda el primer frame en `frame.jpg`.
Si quieres probar el control, el cliente Android envía los eventos de input.

## Notas

- Ejecuta la consola **como administrador** si necesitas controlar ventanas
  elevadas (UAC): Windows bloquea `SendInput` hacia procesos de mayor integridad.
- El primer arranque puede disparar un aviso del SmartScreen/Defender porque el
  binario no está firmado. Es esperado en esta fase. Para eliminarlo en local:
  `.\tools\build-release.ps1 -TrustCert` (crea la clave, publica autocontenido
  en `dist\` y firma todos los `.exe`; `-TrustCert` una vez como admin para
  que Windows confíe en el cert).
