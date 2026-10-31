# Prueba del modo inverso: conecta como el .exe al PhoneServer del celular
# y verifica que lleguen frames de video (CHP/1).
#
#   .\tools\test-phone.ps1 -Phone 192.168.1.113 -Pin 478461
#
param(
    [string]$Phone = "192.168.1.113",
    [int]$Port = 47474,
    [string]$Pin = "478461",
    [string]$Out = "$env:TEMP\phone-frame.jpg",
    [int]$Seconds = 6
)

$ErrorActionPreference = "Stop"

function Read-Exact($stream, [int]$count) {
    $buf = New-Object byte[] $count
    $offset = 0
    while ($offset -lt $count) {
        $n = $stream.Read($buf, $offset, $count - $offset)
        if ($n -le 0) { throw "conexion cerrada al leer $offset/$count bytes" }
        $offset += $n
    }
    return $buf
}

function New-Packet([int]$type, [int]$seq, [byte[]]$payload) {
    $len = if ($null -eq $payload) { 0 } else { $payload.Length }
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([uint16]0x4843)   # magic 'CH'
    $bw.Write([byte]1)          # version
    $bw.Write([byte]$type)
    $bw.Write([uint32]$len)
    $bw.Write([uint32]$seq)
    if ($len -gt 0) { $bw.Write($payload) }
    $bw.Flush()
    return $ms.ToArray()
}

function Read-Packet($stream) {
    $hdr = Read-Exact $stream 12
    $magic = [BitConverter]::ToUInt16($hdr, 0)
    if ($magic -ne 0x4843) { throw "magic invalido: 0x$($magic.ToString('X4'))" }
    $type = $hdr[3]
    $len = [BitConverter]::ToUInt32($hdr, 4)
    if ($len -gt 8MB) { throw "payload enorme: $len" }
    $payload = if ($len -gt 0) { Read-Exact $stream ([int]$len) } else { ,@() }
    return ,@($type, $payload)
}

"== Conectando a ${Phone}:${Port} =="
$client = New-Object System.Net.Sockets.TcpClient
$client.NoDelay = $true
$client.Connect($Phone, $Port)
$stream = $client.GetStream()
$stream.ReadTimeout = 5000
$stream.WriteTimeout = 5000

# 1) HELLO
$hello = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter($hello)
$bw.Write([uint16]1)       # proto_min
$bw.Write([uint16]1)       # proto_max
$bw.Write([byte]1)         # client_os = Windows
$bw.Write([byte[]](1, 0, 0))  # caps
$bw.Write([uint32]1920)    # screen_w
$bw.Write([uint32]1080)    # screen_h
$bw.Flush()
$stream.Write((New-Packet 1 0 $hello.ToArray()), 0, (New-Packet 1 0 $hello.ToArray()).Length)

$t = Read-Packet $stream
if ($t[0] -ne 2) { throw "esperaba HELLO_ACK, llego tipo $($t[0])" }
$ackLen = $t[1].Length
"OK HELLO_ACK (payload $ackLen bytes)"

# 2) AUTH
$pinBytes = [Text.Encoding]::UTF8.GetBytes($Pin)
$stream.Write((New-Packet 3 0 $pinBytes), 0, (New-Packet 3 0 $pinBytes).Length)

$t = Read-Packet $stream
if ($t[0] -ne 4) { throw "esperaba AUTH_RESULT, llego tipo $($t[0])" }
$code = $t[1][0]
if ($code -ne 0) { throw "auth fallo, codigo $code (PIN incorrecto?)" }
"OK AUTH (PIN $Pin aceptado)"

# 3) Escuchar frames
$sw = [Diagnostics.Stopwatch]::StartNew()
$frames = 0
$bytes = 0
$firstSaved = $false
$firstInfo = ""
try {
    while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
        $t = Read-Packet $stream
        if ($t[0] -ne 5) { "  (paquete tipo $($t[0]) ignorado, $($t[1].Length) bytes)"; continue }
        $p = $t[1]
        if ($p.Length -lt 12) { "  frame muy corto: $($p.Length)"; continue }
        $w = [BitConverter]::ToUInt16($p, 0)
        $h = [BitConverter]::ToUInt16($p, 2)
        $fmt = $p[4]
        if (-not $firstSaved) {
            $firstInfo = "first frame: ${w}x${h} format=$fmt jpeg=$($p.Length - 12) bytes"
            $jpeg = $p[12..($p.Length - 1)]
            [IO.File]::WriteAllBytes($Out, [byte[]]$jpeg)
            $firstSaved = $true
        }
        $frames++
        $bytes += $p.Length
    }
} catch {
    "  lectura interrumpida: $($_.Exception.Message)"
}

$elapsed = $sw.Elapsed.TotalSeconds
if ($frames -eq 0) {
    "RESULTADO: 0 frames en $([math]::Round($elapsed,1))s -> EL CELULAR NO MANDA IMAGEN"
} else {
    $kb = [math]::Round($bytes / 1024 / $elapsed, 1)
    "RESULTADO: $frames frames en $([math]::Round($elapsed,1))s ($([math]::Round($frames/$elapsed,1)) fps, $kb KB/s)"
    if ($firstInfo) { $firstInfo; "guardado en $Out" }
}

$client.Close()
