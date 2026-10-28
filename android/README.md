# Cliente Android

Kotlin + Jetpack Compose. Mínimo soportado: Android 8.0 (API 26).

## Compilar

```powershell
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requisitos: JDK 17+, Android SDK API 35.

## Permisos previstos

| Permiso | Motivo |
|---------|--------|
| `INTERNET` | Obligatorio para abrir sockets, incluso en LAN sin salida a internet |
| `ACCESS_NETWORK_STATE` | Avisar si se pierde el WiFi |
| `FOREGROUND_SERVICE` | Mantener la sesión al cambiar de app (fase 2) |

## Estado

Fase 0: pendiente de implementación. Ver [`../docs/ROADMAP.md`](../docs/ROADMAP.md).
