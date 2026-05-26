$ErrorActionPreference = 'Stop'

$BackendRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$FrontendRoot = (Resolve-Path (Join-Path $BackendRoot '..\Skytrack-Frontend')).Path
$DeployRoot = Join-Path $BackendRoot 'deploy'
$BundleRoot = Join-Path $DeployRoot 'bundle'
$TarPath = Join-Path $DeployRoot 'skytrack-vm-deploy.tar.gz'

Write-Host 'Building backend jar...'
Push-Location $BackendRoot
try {
  .\gradlew.bat bootJar -x test
} finally {
  Pop-Location
}

Write-Host 'Building frontend dist...'
Push-Location $FrontendRoot
try {
  npm run build
} finally {
  Pop-Location
}

if (Test-Path $BundleRoot) {
  Remove-Item $BundleRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $BundleRoot | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'backend') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'backend\data') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'frontend') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'deploy') | Out-Null

$Jar = Get-ChildItem (Join-Path $BackendRoot 'build\libs\*.jar') |
  Where-Object { $_.Name -notlike '*-plain.jar' } |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1
if (-not $Jar) {
  throw 'Could not find Spring Boot jar in build\libs'
}
Copy-Item $Jar.FullName (Join-Path $BundleRoot 'backend\scheduling-core.jar') -Force

$DataRoot = Join-Path $BackendRoot 'data'
Copy-Item (Join-Path $DataRoot 'c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt') (Join-Path $BundleRoot 'backend\data') -Force
Copy-Item (Join-Path $DataRoot 'planes_vuelo.txt') (Join-Path $BundleRoot 'backend\data') -Force
Copy-Item (Join-Path $DataRoot '_envios_preliminar_') (Join-Path $BundleRoot 'backend\data\_envios_preliminar_') -Recurse -Force
New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'backend\data\results') | Out-Null

Copy-Item (Join-Path $FrontendRoot 'dist') (Join-Path $BundleRoot 'frontend\dist') -Recurse -Force
Copy-Item (Join-Path $BackendRoot 'deploy\vm') (Join-Path $BundleRoot 'deploy\vm') -Recurse -Force

if (Test-Path $TarPath) {
  Remove-Item $TarPath -Force
}
tar -czf $TarPath -C $BundleRoot .
if ($LASTEXITCODE -ne 0) {
  throw 'tar failed while creating deploy archive'
}

Write-Host "Created package: $TarPath"
Write-Host 'Upload it with:'
Write-Host 'scp deploy\skytrack-vm-deploy.tar.gz 1inf54.981.2b@200.16.7.142:~/skytrack-vm-deploy.tar.gz'
