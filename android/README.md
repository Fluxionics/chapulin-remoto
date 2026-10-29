# Cliente Android

Kotlin + Jetpack Compose. Mínimo soportado: Android 8.0 (API 26).

## Compilar

```powershell
# Una vez: apuntar el SDK (local.properties no se sube al repo)
"sdk.dir=$env:LOCALAPPDATA\Android\Sdk" | Out-File -Encoding ascii local.properties

.\gradlew.bat assembleDebug
```

Requisitos: JDK 17+, Android SDK API 35.

## Instalar en el móvil

Con depuración USB activada y el móvil conectado:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

También puedes copiar el APK al teléfono e instalarlo manualmente
(permitiendo "instalar apps de origen desconocido").

## Uso

1. En el PC, ejecuta `chapulin-host.exe` y apunta la IP y el PIN.
2. En el móvil, abre Chapulín Remoto, escribe IP, puerto (47474) y PIN.
3. Pulsa **Conectar**.

Gestos:

| Gesto | Acción |
|-------|--------|
| Tocar | Clic izquierdo en ese punto |
| Arrastrar | Mover el cursor |
| Mantener pulsado | Clic derecho |
| Tocar con dos dedos | Clic derecho |

## Permisos

| Permiso | Motivo |
|---------|--------|
| `INTERNET` | Abrir sockets TCP, incluso en LAN sin salida a internet |
| `ACCESS_NETWORK_STATE` | (reservado) avisar si se pierde el WiFi |

## Estructura

```
app/src/main/java/com/chapulin/remoto/
├─ MainActivity.kt        Navegación entre conexión y visor
├─ net/Chp.kt             Serialización del protocolo CHP/1
├─ net/RemoteClient.kt    Socket, handshake, recepción de frames, input
└─ ui/
   ├─ ConnectScreen.kt    IP, puerto y PIN
   └─ RemoteScreen.kt     Visor + gestos
```
