# Host y cliente de Windows

C++20, sin frameworks de UI externos.

| Carpeta | Contenido |
|---------|-----------|
| `src/capture/` | Captura de pantalla (GDI en el MVP, DXGI en fase 3) |
| `src/codec/`   | Compresión de imagen (WIC JPEG, luego Media Foundation H.264) |
| `src/net/`     | Servidor TCP, framing del protocolo CHP/1 |
| `src/input/`   | Inyección de eventos con `SendInput` |
| `src/ui/`      | Icono de bandeja y ventana de estado |

## Compilar

```powershell
cmake -S . -B ../build/windows -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build ../build/windows
```

Requisitos: Visual Studio 2022 Build Tools (workload C++), CMake ≥ 3.25, Ninja.

## Estado

Fase 0: pendiente de implementación. Ver [`../docs/ROADMAP.md`](../docs/ROADMAP.md).
