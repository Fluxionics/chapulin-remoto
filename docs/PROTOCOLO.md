# Protocolo Chapulín (CHP/1)

Protocolo binario propio, TCP, puerto por defecto **47474**.

## 1. Cabecera común (12 bytes)

Todos los paquetes empiezan igual. Enteros en **little-endian**.

| Offset | Tamaño | Campo | Descripción |
|-------:|-------:|-------|-------------|
| 0 | 2 | `magic` | `0x43 0x48` = `"CH"` |
| 2 | 1 | `version` | `0x01` |
| 3 | 1 | `type` | Tipo de paquete (ver tabla) |
| 4 | 4 | `payload_len` | Longitud del payload en bytes (máx. 8 MiB) |
| 8 | 4 | `seq` | Secuencia creciente por tipo; sirve para descartar obsoletos |

```
 0        2   3   4                8               12
 +--------+---+---+----------------+----------------+
 |  "CH"  | v | t |  payload_len   |      seq       |
 +--------+---+---+----------------+----------------+
```

## 2. Tipos de paquete

| Valor | Nombre | Dirección | Payload |
|------:|--------|-----------|---------|
| 0x01 | `HELLO` | cliente → host | `Handshake` |
| 0x02 | `HELLO_ACK` | host → cliente | `HandshakeAck` |
| 0x03 | `AUTH` | cliente → host | PIN (UTF-8, 6 dígitos) |
| 0x04 | `AUTH_RESULT` | host → cliente | `u8` 0=ok, 1=PIN mal, 2=bloqueado |
| 0x05 | `VIDEO_FRAME` | host → cliente | `FrameInfo` + imagen |
| 0x06 | `INPUT` | cliente → host | `InputEvent` |
| 0x07 | `PING` | ambos | `u32` timestamp ms |
| 0x08 | `PONG` | ambos | eco del timestamp |
| 0x09 | `DISCONNECT` | ambos | `u8` motivo |
| 0x0A | `SET_QUALITY` | cliente → host | `Quality` |

> Regla de compatibilidad: un receptor debe **ignorar** tipos desconocidos
> (leer y descartar el payload) en lugar de cerrar la conexión.

## 3. Handshake

```c
// HELLO payload (16 bytes)
struct Handshake {
    uint16_t proto_min;    // 1
    uint16_t proto_max;    // 1
    uint8_t  client_os;    // 1=Windows, 2=Android, 3=Linux
    uint8_t  caps[3];      // bits: jpeg, h264, clipboard, audio
    uint32_t screen_w;
    uint32_t screen_h;
};

// HELLO_ACK payload (24 bytes)
struct HandshakeAck {
    uint16_t proto;        // versión elegida
    uint16_t host_port;
    uint32_t screen_w;     // resolución actual del monitor elegido
    uint32_t screen_h;
    uint8_t  monitor_count;
    uint8_t  auth_required; // 1 = PIN obligatorio
    uint8_t  reserved[6];
    uint8_t  host_name_len; // seguido de UTF-8 con el nombre del PC
};
```

Secuencia:

```
cliente                          host
   |-- HELLO -------------------->|
   |<---------------- HELLO_ACK ---|
   |-- AUTH (PIN) --------------->|
   |<--------------- AUTH_RESULT --|
   |<================ VIDEO_FRAME ==|
   |-- INPUT -------------------->|
```

## 4. Vídeo

```c
// VIDEO_FRAME payload
struct FrameInfo {
    uint16_t width;
    uint16_t height;
    uint8_t  format;      // 1=JPEG, 2=H.264 IDR, 3=H.264 P
    uint8_t  monitor;
    uint16_t scale_pct;   // 100 = nativo
    uint32_t ts_ms;       // reloj monotónico del host
};                        // 12 bytes, luego los bytes de imagen
```

- El cliente puede descartar frames si va retrasado: compara `ts_ms` con el
  último recibido y, si el retraso supera 250 ms, pide `SET_QUALITY` más bajo.
- `seq` se incrementa por frame. Un `seq` menor que el último procesado se ignora.

## 5. Input

Coordenadas **normalizadas** a `0..65535` para ser independientes de resolución.

```c
enum InputKind : uint8_t {
    MOVE       = 0,
    BUTTON_DOWN= 1,
    BUTTON_UP  = 2,
    WHEEL      = 3,
    KEY_DOWN   = 4,
    KEY_UP     = 5,
    CHAR       = 6,   // texto Unicode para IME
};

// INPUT payload (12 bytes)
struct InputEvent {
    uint8_t  kind;
    uint8_t  button;    // 0=izq, 1=der, 2=medio
    uint16_t x;         // 0..65535
    uint16_t y;         // 0..65535
    int16_t  delta;     // rueda: ±120 por muesca
    uint16_t key;       // scancode o código
    uint32_t flags;     // modificadores: 1=Shift 2=Ctrl 4=Alt 8=Meta
};
```

Mapeo de gestos Android (MVP):

| Gesto | Acción |
|-------|--------|
| 1 dedo, arrastrar | mover cursor |
| 1 dedo, toque | clic izquierdo |
| 2 dedos, toque | clic derecho |
| 2 dedos, arrastrar | scroll vertical |
| Pellizcar | zoom local (no se envía al PC) |
| Teclado en pantalla | `KEY_DOWN` / `KEY_UP` |

## 6. Límites y validación

| Límite | Valor |
|--------|-------|
| `payload_len` máximo | 8 388 608 (8 MiB) |
| Tamaño máximo de PIN | 16 bytes |
| Timeout de handshake | 5 s |
| Timeout de inactividad | 30 s (PING cada 5 s) |
| Intentos de PIN | 3 por IP, luego bloqueo 60 s |
| Clientes simultáneos | 1 (MVP) |

Todo campo leído del socket se valida **antes** de reservar memoria o indexar
buffers. Un paquete malformado cierra la conexión con `DISCONNECT(1)`.

## 7. Pendiente (fase 4)

El MVP viaja en claro. El diseño previsto:

- Intercambio de claves **X25519** + autenticación del host con huella del PIN.
- Cifrado **AES-256-GCM** por paquete, con `seq` como nonce parcial.
- Upgrade del handshake a `CHP/2` sin romper clientes v1 (negociación por versión).
