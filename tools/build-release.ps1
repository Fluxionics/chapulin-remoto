#Requires -Version 5.1
<#
.SYNOPSIS
    Compila, empaqueta y FIRMA el host de Windows (chapulin-host.exe).

.DESCRIPTION
    Hace tres cosas:
      1. Crea (si no existe) un certificado auto-firmado de firma de código
         y lo exporta a certs\chapulin-codesign.pfx.
      2. Publica la GUI (windows_gui) autocontenida: el .exe arranca en
         cualquier PC sin instalar .NET 8.
      3. Firma con Authenticode todos los .exe del proyecto (el publicado,
         los de build\windows y los de bin\Debug / bin\Release), así
         Defender/SmartScreen dejan de quejarse de "app desconocida".

.EXAMPLE
    .\tools\build-release.ps1                 # publica en dist\ y firma
    .\tools\build-release.ps1 -TrustCert      # además instala el cert en el
                                              # almacén local (requiere admin)
    .\tools\build-release.ps1 -SignOnly      # solo firma, no compila
#>
[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Release',

    # Instala el certificado en Raíz de confianza + Publicadores de confianza
    # (solo tiene sentido en tu PC; requiere PowerShell como administrador).
    [switch]$TrustCert,

    # Solo firma los .exe existentes, sin compilar nada.
    [switch]$SignOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$certDir  = Join-Path $repoRoot 'certs'
$certPfx  = Join-Path $certDir 'chapulin-codesign.pfx'
$certPwd  = Join-Path $certDir 'chapulin-codesign.pfx.password'
$subject  = 'CN=Chapulin Remoto (firma local)'
$distDir  = Join-Path $repoRoot 'dist'

# ---------------------------------------------------------------- 1. clave --
function New-CodeSigningCert {
    Write-Host '==> Creando certificado auto-firmado de firma de código...' -ForegroundColor Cyan

    $cert = New-SelfSignedCertificate `
        -Type CodeSigningCert `
        -Subject $subject `
        -CertStoreLocation 'Cert:\CurrentUser\My' `
        -KeyExportPolicy Exportable `
        -NotAfter (Get-Date).AddYears(5)

    # Contraseña aleatoria para el .pfx, guardada al lado (es una clave LOCAL,
    # no la subas a ningún sitio compartido).
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $plain = [Convert]::ToBase64String($bytes)

    New-Item -ItemType Directory -Force -Path $certDir | Out-Null
    Export-PfxCertificate -Cert $cert -FilePath $certPfx `
        -Password (ConvertTo-SecureString $plain -AsPlainText -Force) | Out-Null
    Set-Content -Path $certPwd -Value $plain -Encoding ASCII

    Write-Host "    PFX:  $certPfx" -ForegroundColor Green
    Write-Host "    Pass: $certPwd  (guárdala; NO la subas a git)" -ForegroundColor Green
    return $cert
}

if (-not (Test-Path $certPfx)) {
    $cert = New-CodeSigningCert
} else {
    Write-Host "==> Certificado existente: $certPfx" -ForegroundColor Cyan
    if (-not (Test-Path $certPwd)) {
        throw "Falta $certPwd (contraseña del .pfx). Borra el .pfx para regenerar la clave."
    }
}

if ($TrustCert) {
    $isAdmin = ([Security.Principal.WindowsPrincipal] `
        [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $isAdmin) {
        Write-Warning '-TrustCert necesita una consola como administrador. Omitido.'
    } else {
        Write-Host '==> Instalando el cert en Publicadores de confianza y Raíz...' -ForegroundColor Cyan
        $securePwd = ConvertTo-SecureString (Get-Content $certPwd -Raw).Trim() -AsPlainText -Force
        Import-PfxCertificate -FilePath $certPfx -CertStoreLocation 'Cert:\LocalMachine\TrustedPublisher' -Password $securePwd | Out-Null
        Import-PfxCertificate -FilePath $certPfx -CertStoreLocation 'Cert:\LocalMachine\Root' -Password $securePwd | Out-Null
    }
}

# --------------------------------------------------------------- 2. build --
if (-not $SignOnly) {
    Write-Host "==> dotnet publish (GUI, autocontenida, $Configuration)..." -ForegroundColor Cyan
    $project = Join-Path $repoRoot 'windows_gui\ChapulinRemoto.csproj'
    & dotnet publish $project `
        -c $Configuration `
        -r win-x64 `
        --self-contained true `
        -p:PublishSingleFile=true `
        -p:IncludeNativeLibrariesForSelfExtract=true `
        -o $distDir
    if ($LASTEXITCODE -ne 0) { throw "dotnet publish ha fallado (código $LASTEXITCODE)" }
    Write-Host "    Salida: $distDir\chapulin-host.exe" -ForegroundColor Green
}

# ---------------------------------------------------------------- 3. sign --
function Find-SignTool {
    $kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
    if (Test-Path $kits) {
        $tool = Get-ChildItem $kits -Recurse -Filter signtool.exe -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match '\\x64\\signtool\.exe$' } |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($tool) { return $tool.FullName }
    }
    $cmd = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$plainPwd  = (Get-Content $certPwd -Raw).Trim()
$signTool  = Find-SignTool
$targets = @(
    (Join-Path $distDir 'chapulin-host.exe'),
    (Join-Path $repoRoot 'windows\chapulin-host.exe'),
    (Join-Path $repoRoot 'build\windows\chapulin-host.exe'),
    (Join-Path $repoRoot 'windows_gui\bin\Debug\net8.0-windows\chapulin-host.exe'),
    (Join-Path $repoRoot 'windows_gui\bin\Release\net8.0-windows\chapulin-host.exe')
) | Where-Object { $_ -and (Test-Path $_) }

if (-not $targets) {
    Write-Warning 'No hay ningún .exe para firmar todavía.'
    return
}

foreach ($exe in $targets) {
    if ($signTool) {
        # Auto-firmado local: sin sello de tiempo (no hace falta para tu PC).
        & $signTool sign /fd SHA256 /f $certPfx /p $plainPwd $exe | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "No se pudo firmar: $exe"; continue }
    } else {
        $storeCert = Import-PfxCertificate -FilePath $certPfx `
            -CertStoreLocation 'Cert:\CurrentUser\My' `
            -Password (ConvertTo-SecureString $plainPwd -AsPlainText -Force)
        Set-AuthenticodeSignature -FilePath $exe -Certificate $storeCert -HashAlgorithm SHA256 | Out-Null
    }
    Write-Host "    Firmado: $exe" -ForegroundColor Green
}

Write-Host ''
Write-Host 'Listo. El .exe de dist\ abre en cualquier PC sin instalar .NET.' -ForegroundColor Cyan
Write-Host 'Si Defender aun insiste: exclusión temporal de la carpeta dist\.' -ForegroundColor DarkGray
