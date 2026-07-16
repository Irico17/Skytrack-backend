param(
  # Forzar recompilación aunque ya existan jar y dist (por defecto se REUTILIZAN si existen,
  # para poder redesplegar desde una PC sin Java/Node — p.ej. extraída de los zips de release).
  [switch]$ForceBuild
)

$ErrorActionPreference = 'Stop'

$BackendRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$FrontendRoot = (Resolve-Path (Join-Path $BackendRoot '..\Skytrack-Frontend')).Path
$DeployRoot = Join-Path $BackendRoot 'deploy'
$BundleRoot = Join-Path $DeployRoot 'bundle'
$TarPath = Join-Path $DeployRoot 'skytrack-vm-deploy.tar.gz'

function Find-Jar {
  Get-ChildItem (Join-Path $BackendRoot 'build\libs\*.jar') -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*-plain.jar' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
}

$Jar = Find-Jar
$DistIndex = Join-Path $FrontendRoot 'dist\index.html'
$HasDist = Test-Path $DistIndex

# ── Backend: compilar SOLO si falta el jar (o -ForceBuild) ──────────────────
if ($ForceBuild -or -not $Jar) {
  if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'No hay jar compilado y Java no está instalado en esta PC. Usa los zips de release (traen el jar) o instala JDK 17.'
  }
  Write-Host 'Building backend jar...'
  Push-Location $BackendRoot
  try {
    .\gradlew.bat bootJar -x test
    if ($LASTEXITCODE -ne 0) { throw "gradlew bootJar failed ($LASTEXITCODE)" }
  } finally {
    Pop-Location
  }
  $Jar = Find-Jar
} else {
  Write-Host "Reutilizando jar compilado: $($Jar.Name) ($($Jar.LastWriteTime))"
}

# ── Frontend: compilar SOLO si falta dist/ (o -ForceBuild) ──────────────────
if ($ForceBuild -or -not $HasDist) {
  if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw 'No hay dist/ compilado y npm no está instalado en esta PC. Usa los zips de release (traen dist/) o instala Node.'
  }
  Write-Host 'Building frontend dist...'
  Push-Location $FrontendRoot
  try {
    if (-not (Test-Path (Join-Path $FrontendRoot 'node_modules'))) {
      # cmd /c evita que PowerShell 5.1 convierta el stderr informativo de npm en error fatal.
      cmd /c "npm ci 2>&1"
      if ($LASTEXITCODE -ne 0) { throw "npm ci failed ($LASTEXITCODE)" }
    }
    cmd /c "npm run build 2>&1"
    if ($LASTEXITCODE -ne 0) { throw "npm run build failed ($LASTEXITCODE)" }
  } finally {
    Pop-Location
  }
} else {
  Write-Host "Reutilizando frontend compilado: dist/ ($((Get-Item $DistIndex).LastWriteTime))"
}

if (-not $Jar) { throw 'Could not find Spring Boot jar in build\libs' }
if (-not (Test-Path $DistIndex)) { throw 'Could not find Skytrack-Frontend\dist\index.html' }

# ── Armar el bundle ──────────────────────────────────────────────────────────
if (Test-Path $BundleRoot) {
  Remove-Item $BundleRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $BundleRoot | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'backend') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'backend\data') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'frontend') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'deploy') | Out-Null

Copy-Item $Jar.FullName (Join-Path $BundleRoot 'backend\scheduling-core.jar') -Force

$DataRoot = Join-Path $BackendRoot 'data'
Copy-Item (Join-Path $DataRoot 'c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt') (Join-Path $BundleRoot 'backend\data') -Force
Copy-Item (Join-Path $DataRoot 'planes_vuelo.txt') (Join-Path $BundleRoot 'backend\data') -Force
Copy-Item (Join-Path $DataRoot '_envios_preliminar_') (Join-Path $BundleRoot 'backend\data\_envios_preliminar_') -Recurse -Force
New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'backend\data\results') | Out-Null

Copy-Item (Join-Path $FrontendRoot 'dist') (Join-Path $BundleRoot 'frontend\dist') -Recurse -Force
Copy-Item (Join-Path $BackendRoot 'deploy\vm') (Join-Path $BundleRoot 'deploy\vm') -Recurse -Force
$BundleDeploySsh = Join-Path $BundleRoot 'deploy\vm\.ssh'
if (Test-Path $BundleDeploySsh) {
  Remove-Item $BundleDeploySsh -Recurse -Force
}

if (Test-Path $TarPath) {
  Remove-Item $TarPath -Force
}
if (-not (Get-Command tar -ErrorAction SilentlyContinue)) {
  throw 'tar no está disponible (viene con Windows 10+). Actualiza Windows o instala bsdtar.'
}
tar -czf $TarPath -C $BundleRoot .
if ($LASTEXITCODE -ne 0) {
  throw 'tar failed while creating deploy archive'
}
Remove-Item $BundleRoot -Recurse -Force

Write-Host "Created package: $TarPath"
Write-Host 'Upload it with:'
Write-Host 'scp deploy\skytrack-vm-deploy.tar.gz 1inf54.981.2b@200.16.7.142:~/skytrack-vm-deploy.tar.gz'
