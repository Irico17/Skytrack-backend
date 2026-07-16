$ErrorActionPreference = 'Stop'

$BackendRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$FrontendRoot = (Resolve-Path (Join-Path $BackendRoot '..\Skytrack-Frontend')).Path
$RepoRoot = (Resolve-Path (Join-Path $BackendRoot '..')).Path
$ScriptsRoot = Join-Path $RepoRoot 'scripts'
$DocsRoot = Join-Path $RepoRoot 'docs'
$ReleaseRoot = (Resolve-Path (Join-Path $BackendRoot '..\release')).Path
$StagingRoot = Join-Path $ReleaseRoot '_staging'
$BackendZip = Join-Path $ReleaseRoot 'skytrack-backend.zip'
$FrontendZip = Join-Path $ReleaseRoot 'skytrack-frontend.zip'
$ExtrasZip = Join-Path $ReleaseRoot 'skytrack-extras.zip'

function Copy-TreeFiltered {
  param(
    [string]$Source,
    [string]$Destination,
    [string[]]$ExcludeDirs = @(),
    [string[]]$ExcludeFiles = @(),
    [string[]]$ExcludeExtensions = @()
  )

  if (-not (Test-Path $Source)) {
    throw "Source path not found: $Source"
  }

  New-Item -ItemType Directory -Force -Path $Destination | Out-Null

  Get-ChildItem -Path $Source -Force | ForEach-Object {
    if ($ExcludeDirs -contains $_.Name) { return }
    if ($ExcludeFiles -contains $_.Name) { return }
    if ($ExcludeExtensions -contains $_.Extension) { return }
    if ($_.Extension -eq '.md') { return }

    $target = Join-Path $Destination $_.Name
    if ($_.PSIsContainer) {
      Copy-TreeFiltered -Source $_.FullName -Destination $target `
        -ExcludeDirs $ExcludeDirs -ExcludeFiles $ExcludeFiles -ExcludeExtensions $ExcludeExtensions
    } else {
      Copy-Item $_.FullName $target -Force
    }
  }
}

function Clean-BackendDataResults {
  param([string]$ResultsDir)
  New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
  Get-ChildItem $ResultsDir -Filter '*.json' -File -ErrorAction SilentlyContinue | Remove-Item -Force
}

function Clean-DeployVm {
  param([string]$VmDir)
  if (-not (Test-Path $VmDir)) { return }
  Get-ChildItem $VmDir -File | ForEach-Object {
    if ($_.Extension -in @('.ps1', '.md')) {
      Remove-Item $_.FullName -Force
    }
  }
}

if (Test-Path $StagingRoot) {
  Remove-Item $StagingRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $StagingRoot | Out-Null

# Limpieza de zips de entregas pasadas / residuos en release/ que no son el paquete actual
# (p.ej. "0981.Eq2B.Sol.final.*.zip" de entregas anteriores) — evita acumular GBs de zips
# viejos cada vez que se repackagea.
Get-ChildItem $ReleaseRoot -Filter '*.zip' -File | Where-Object {
  $_.Name -ne 'skytrack-backend.zip' -and $_.Name -ne 'skytrack-frontend.zip' -and $_.Name -ne 'skytrack-extras.zip'
} | ForEach-Object {
  Write-Host "Eliminando zip de entrega anterior: $($_.Name)"
  Remove-Item $_.FullName -Force
}

$BackendExcludeDirs = @(
  '.git', 'build', '.gradle', 'bin', '.idea', '.vscode', '.kiro',
  'documentos', 'InformacionCaso', 'experimentos-historicos'
)
$BackendExcludeFiles = @(
  'Dockerfile', 'Containerfile.runtime', 'compose.podman.yml', '.dockerignore',
  'TestEarlyFilter.class', 'diagrama_clases_sistema.puml'
)
$BackendExcludeExtensions = @('.class', '.ps1')

$FrontendExcludeDirs = @('.git', 'node_modules', 'dist', 'guidelines')
$FrontendExcludeFiles = @('Dockerfile', '.dockerignore', 'nginx.conf.template')
$FrontendExcludeExtensions = @('.ps1')

$BackendStaging = Join-Path $StagingRoot 'scheduling-core'
$FrontendStaging = Join-Path $StagingRoot 'Skytrack-Frontend'

Write-Host 'Building backend jar...'
Push-Location $BackendRoot
try {
  .\gradlew.bat bootJar -x test
} finally {
  Pop-Location
}

Write-Host 'Installing frontend dependencies (npm ci)...'
Push-Location $FrontendRoot
try {
  # cmd /c evita que PowerShell 5.1 convierta el stderr informativo de npm en
  # NativeCommandError bajo ErrorActionPreference=Stop (abortaba el empaquetado).
  cmd /c "npm ci 2>&1"
  if ($LASTEXITCODE -ne 0) { throw "npm ci failed ($LASTEXITCODE)" }
  cmd /c "npm run build 2>&1"
  if ($LASTEXITCODE -ne 0) { throw "npm run build failed ($LASTEXITCODE)" }
} finally {
  Pop-Location
}

Write-Host 'Packaging scheduling-core...'
Copy-TreeFiltered `
  -Source $BackendRoot `
  -Destination $BackendStaging `
  -ExcludeDirs $BackendExcludeDirs `
  -ExcludeFiles $BackendExcludeFiles `
  -ExcludeExtensions $BackendExcludeExtensions
Clean-BackendDataResults (Join-Path $BackendStaging 'data\results')
Clean-DeployVm (Join-Path $BackendStaging 'deploy\vm')

# Excepción a la exclusión de .ps1: estos dos scripts permiten REDESPLEGAR desde otra PC
# Windows usando solo el contenido del zip (reutilizan el jar/dist incluidos, sin Java/Node).
foreach ($keep in @('redeploy-vm.ps1', 'package-local.ps1')) {
  $src = Join-Path $PSScriptRoot $keep
  if (Test-Path $src) {
    Copy-Item $src (Join-Path $BackendStaging 'deploy\vm') -Force
  }
}

$Jar = Get-ChildItem (Join-Path $BackendRoot 'build\libs\*.jar') |
  Where-Object { $_.Name -notlike '*-plain.jar' } |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1
if (-not $Jar) {
  throw 'Could not find Spring Boot jar in build\libs'
}
$JarDestDir = Join-Path $BackendStaging 'build\libs'
New-Item -ItemType Directory -Force -Path $JarDestDir | Out-Null
Copy-Item $Jar.FullName (Join-Path $JarDestDir $Jar.Name) -Force
Write-Host "Included compiled jar: build/libs/$($Jar.Name)"

Write-Host 'Packaging Skytrack-Frontend...'
Copy-TreeFiltered `
  -Source $FrontendRoot `
  -Destination $FrontendStaging `
  -ExcludeDirs $FrontendExcludeDirs `
  -ExcludeFiles $FrontendExcludeFiles `
  -ExcludeExtensions $FrontendExcludeExtensions

$FrontendDistDest = Join-Path $FrontendStaging 'dist'
New-Item -ItemType Directory -Force -Path $FrontendDistDest | Out-Null
Copy-Item (Join-Path $FrontendRoot 'dist\*') $FrontendDistDest -Recurse -Force
Write-Host 'Included compiled frontend: dist/'

# node_modules NO se incluye: pesa ~170 MB sin comprimir y no hace falta para desplegar
# (el deploy solo sirve dist/ ya compilado via Nginx). Si algún día se necesita recompilar
# el frontend en la máquina de destino, ahi si corre `npm ci` (requiere Node instalado).

# scripts/ y docs/ viven en la raíz del repo (hermanos de scheduling-core/ y
# Skytrack-Frontend/, NO dentro de ninguno) — sin este paso quedaban fuera de todo ZIP.
# Van en un tercer paquete liviano: scripts de preparación/cierre de la prueba día a día
# (prueba-d2d.mjs, gen-d2d-flightplan.mjs) y la guía de exposición.
# Copy-Item directo (no Copy-TreeFiltered): esa función excluye TODOS los .md, y aquí
# el .md (la guía) es justamente lo que hay que incluir.
Write-Host 'Packaging scripts/ y docs/ (extras)...'
$ExtrasStaging = Join-Path $StagingRoot 'extras'
New-Item -ItemType Directory -Force -Path $ExtrasStaging | Out-Null
if (Test-Path $ScriptsRoot) {
  Copy-Item $ScriptsRoot (Join-Path $ExtrasStaging 'scripts') -Recurse -Force
}
if (Test-Path $DocsRoot) {
  Copy-Item $DocsRoot (Join-Path $ExtrasStaging 'docs') -Recurse -Force
}

foreach ($zip in @($BackendZip, $FrontendZip, $ExtrasZip)) {
  if (Test-Path $zip) {
    Remove-Item $zip -Force
  }
}

Write-Host 'Creating skytrack-backend.zip...'
tar -a -cf $BackendZip -C $StagingRoot scheduling-core
if ($LASTEXITCODE -ne 0) { throw 'tar failed while creating backend zip' }

Write-Host 'Creating skytrack-frontend.zip...'
tar -a -cf $FrontendZip -C $StagingRoot Skytrack-Frontend
if ($LASTEXITCODE -ne 0) { throw 'tar failed while creating frontend zip' }

Write-Host 'Creating skytrack-extras.zip...'
# Nombrar las carpetas explicitamente (no "." como origen): "tar -C dir ." genera entradas
# con prefijo "./" (./, ./docs/, ...) que el explorador de Windows a veces no renderiza bien,
# mostrando el zip como vacio aunque el contenido este ahi (confirmado con unzip -l).
tar -a -cf $ExtrasZip -C $ExtrasStaging scripts docs
if ($LASTEXITCODE -ne 0) { throw 'tar failed while creating extras zip' }

Remove-Item $StagingRoot -Recurse -Force

Write-Host ''
Write-Host 'Release packages created (one project folder per zip):'
Write-Host "  $BackendZip  -> scheduling-core/"
Write-Host "  $FrontendZip  -> Skytrack-Frontend/"
Write-Host "  $ExtrasZip  -> scripts/ + docs/ (prep D2D + guia de expo)"
Write-Host "  $(Join-Path $ReleaseRoot 'DEPLOY_MANUAL.md')"
Write-Host ''
Write-Host 'Upload with:'
Write-Host 'scp release\skytrack-backend.zip release\skytrack-frontend.zip 1inf54.981.2b@200.16.7.142:~/'
