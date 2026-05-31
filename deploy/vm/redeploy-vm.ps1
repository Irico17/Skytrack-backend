param(
  [string]$VmUser = '1inf54.981.2b',
  [string]$VmHost = '200.16.7.142',
  [switch]$OverwriteData,
  [string]$SudoPassword = ''
)

$ErrorActionPreference = 'Stop'

# Si no se pasó -SudoPassword, leer de variable de entorno SKYTRACK_SUDO_PASS
if (-not $SudoPassword) {
  $SudoPassword = $env:SKYTRACK_SUDO_PASS
}
# Último recurso: pedir interactivamente (no echo en pantalla)
if (-not $SudoPassword) {
  $securePass = Read-Host 'Sudo password for VM' -AsSecureString
  $SudoPassword = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePass))
}

$BackendRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$PackageScript = Join-Path $PSScriptRoot 'package-local.ps1'
$TarPath = Join-Path $BackendRoot 'deploy\skytrack-vm-deploy.tar.gz'
$Remote = "$VmUser@$VmHost"

Write-Host '==> Building local package'
& $PackageScript

if (-not (Test-Path $TarPath)) {
  throw "Package was not created: $TarPath"
}

Write-Host "==> Uploading package to $Remote"
scp $TarPath "${Remote}:~/skytrack-vm-deploy.tar.gz"
if ($LASTEXITCODE -ne 0) {
  throw 'scp failed'
}

$DeployCommand = if ($OverwriteData) {
  "echo '$SudoPassword' | sudo -S -E env SKYTRACK_OVERWRITE_DATA=true ./deploy-artifacts.sh"
} else {
  "echo '$SudoPassword' | sudo -S ./deploy-artifacts.sh"
}

$RemoteCommands = @(
  'set -e',
  'rm -rf ~/skytrack-deploy/current',
  'mkdir -p ~/skytrack-deploy/current',
  'tar -xzf ~/skytrack-vm-deploy.tar.gz -C ~/skytrack-deploy/current',
  'cd ~/skytrack-deploy/current/deploy/vm',
  'chmod +x *.sh',
  $DeployCommand
) -join '; '

Write-Host "==> Deploying on $Remote"
ssh -t $Remote $RemoteCommands
if ($LASTEXITCODE -ne 0) {
  throw 'remote deploy failed'
}

Write-Host '==> Verifying public frontend'
try {
  $response = Invoke-WebRequest -UseBasicParsing "http://$VmHost/" -TimeoutSec 20
  Write-Host "HTTP $($response.StatusCode) from http://$VmHost/"
} catch {
  Write-Warning "Public verification failed: $($_.Exception.Message)"
}

Write-Host 'Redeploy finished.'
