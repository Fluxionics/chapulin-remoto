# Cliente de prueba CHP/1
#
# Sirve para comprobar el host de Windows SIN el movil: hace el handshake,
# se autentica y guarda el primer frame JPEG en disco.
#
# Uso:
#   .\test-client.ps1 -Pin 123456
#   .\test-client.ps1 -Server 192.168.1.50 -Port 47474 -Pin 123456 -Out C:\temp\frame.jpg

param(
    [string]$Server = "127.0.0.1",
    [int]$Port = 47474,
    [Parameter(Mandatory = $true)][string]$Pin,
    [string]$Out = "$PSScriptRoot\frame.jpg",
    [int]$Frames = 1
)

$ErrorActionPreference = "Stop"

function New-Packet {
    param([byte]$Type, [uint32]$Seq, [byte[]]$Payload)

    $stream = New-Object System.IO.MemoryStream
    $writer = New-Object System.IO.BinaryWriter($stream)
    $writer.Write([uint16]0x4843)   # "CH"
    $writer.Write([byte]1)          # version
    $writer.Write([byte]$Type)
    $writer.Write([uint32]$Payload.Length)
    $writer.Write([uint32]$Seq)
    $writer.Write($Payload)
    $writer.Flush()
    return $stream.ToArray()
}

function Read-Exact {
    param([System.IO.Stream]$Stream, [int]$Count)

    $buffer = New-Object byte[] $Count
    $offset = 0
    while ($offset -lt $Count) {
        $read = $Stream.Read($buffer, $offset, $Count - $offset)
        if ($read -le 0) { throw "La conexion se cerro inesperadamente" }
        $offset += $read
    }
    return $buffer
}

function Read-Packet {
    param([System.IO.Stream]$Stream)

    $header = Read-Exact -Stream $Stream -Count 12
    $magic = [BitConverter]::ToUInt16($header, 0)
    if ($magic -ne 0x4843) { throw ("Cabecera invalida: 0x{0:X4}" -f $magic) }

    $version = $header[2]
    if ($version -ne 1) { throw "Version de protocolo no soportada: $version" }

    $length = [BitConverter]::ToUInt32($header, 4)
    if ($length -gt 8388608) { throw "Frame demasiado grande: $length" }

    [pscustomobject]@{
        Type    = $header[3]
        Seq     = [BitConverter]::ToUInt32($header, 8)
        Payload = if ($length -gt 0) { Read-Exact -Stream $Stream -Count ([int]$length) } else { @() }
    }
}

$client = New-Object System.Net.Sockets.TcpClient
Write-Host "Conectando a $Server`:$Port ..."
$client.Connect($Server, $Port)
$client.NoDelay = $true
$stream = $client.GetStream()
$stream.ReadTimeout = 15000
$stream.WriteTimeout = 5000

try {
    # HELLO: proto 1..1, cliente Windows, sin capacidades declaradas
    $hello = New-Object System.IO.MemoryStream
    $hw = New-Object System.IO.BinaryWriter($hello)
    $hw.Write([uint16]1); $hw.Write([uint16]1); $hw.Write([byte]1)
    $hw.Write([byte]0); $hw.Write([byte]0); $hw.Write([byte]0)
    $hw.Write([uint32]0); $hw.Write([uint32]0)
    $hw.Flush()
    $helloPacket = New-Packet -Type 1 -Seq 0 -Payload $hello.ToArray()
    $stream.Write($helloPacket, 0, $helloPacket.Length)

    $ack = Read-Packet -Stream $stream
    if ($ack.Type -ne 2) { throw "Se esperaba HELLO_ACK, llego el tipo $($ack.Type)" }

    $hostPort = [BitConverter]::ToUInt16($ack.Payload, 2)
    $screenW = [BitConverter]::ToUInt32($ack.Payload, 4)
    $screenH = [BitConverter]::ToUInt32($ack.Payload, 8)
    $nameLen = $ack.Payload[14]
    $hostName = [System.Text.Encoding]::UTF8.GetString($ack.Payload, 24, $nameLen)

    Write-Host "Host: $hostName (${screenW}x${screenH}) puerto $hostPort"

    # AUTH
    $pinBytes = [System.Text.Encoding]::ASCII.GetBytes($Pin)
    $auth = New-Packet -Type 3 -Seq 0 -Payload $pinBytes
    $stream.Write($auth, 0, $auth.Length)

    $result = Read-Packet -Stream $stream
    if ($result.Type -ne 4) { throw "Se esperaba AUTH_RESULT, llego el tipo $($result.Type)" }
    if ($result.Payload[0] -ne 0) { throw "PIN rechazado (codigo $($result.Payload[0]))" }
    Write-Host "Autenticado correctamente." -ForegroundColor Green

    # Frames
    $saved = 0
    $start = Get-Date
    while ($saved -lt $Frames) {
        $packet = Read-Packet -Stream $stream
        if ($packet.Type -eq 9) { throw "El host cerro la sesion (motivo $($packet.Payload[0]))" }
        if ($packet.Type -ne 5) { continue }

        $width = [BitConverter]::ToUInt16($packet.Payload, 0)
        $height = [BitConverter]::ToUInt16($packet.Payload, 2)
        $format = $packet.Payload[4]
        $ts = [BitConverter]::ToUInt32($packet.Payload, 8)

        $imageBytes = New-Object byte[] ($packet.Payload.Length - 12)
        [Array]::Copy($packet.Payload, 12, $imageBytes, 0, $imageBytes.Length)

        $target = if ($Frames -gt 1) {
            [System.IO.Path]::Combine(
                [System.IO.Path]::GetDirectoryName($Out),
                [System.IO.Path]::GetFileNameWithoutExtension($Out) + "_$saved.jpg")
        } else { $Out }

        [System.IO.File]::WriteAllBytes($target, $imageBytes)

        $elapsed = ((Get-Date) - $start).TotalSeconds
        Write-Host ("Frame {0}: {1}x{2} fmt={3} {4} KB ts={5} ({6:N1}s)" -f `
            $saved, $width, $height, $format, [math]::Round($imageBytes.Length / 1KB), $ts, $elapsed)
        Write-Host "Guardado en $target" -ForegroundColor Green
        $saved++
    }

    # Desconexion limpia
    $bye = New-Packet -Type 9 -Seq 0 -Payload ([byte[]]@(0))
    $stream.Write($bye, 0, $bye.Length)
}
finally {
    $client.Close()
}
