#Requires -Version 5.1
<#
.SYNOPSIS
    Instala el certificado auto-firmado de firma (certs\chapulin-codesign.pfx)
    en los almacenes LOCAL de Publicadores de confianza y Raíz de confianza.

    Hay que ejecutarlo COMO ADMINISTRADOR (una sola vez). Después Windows
    acepta los .exe firmados por build-release.ps1 sin avisos de SmartScreen
    en este PC.
#>
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$certPfx  = Join-Path $repoRoot 'certs\chapulin-codesign.pfx'
$certPwd  = Join-Path $repoRoot 'certs\chapulin-codesign.pfx.password'

if (-not (Test-Path $certPfx)) { throw "No existe $certPfx. Ejecuta antes tools\build-release.ps1" }
if (-not (Test-Path $certPwd)) { throw "No existe $certPwd (contraseña del .pfx)" }

$securePwd = ConvertTo-SecureString (Get-Content $certPwd -Raw).Trim() -AsPlainText -Force

Import-PfxCertificate -FilePath $certPfx -CertStoreLocation 'Cert:\LocalMachine\TrustedPublisher' -Password $securePwd | Out-Null
Import-PfxCertificate -FilePath $certPfx -CertStoreLocation 'Cert:\LocalMachine\Root' -Password $securePwd | Out-Null

Write-Host 'Certificado instalado en Publicadores de confianza y Raiz. Los .exe firmados ya no avisan.' -ForegroundColor Green
