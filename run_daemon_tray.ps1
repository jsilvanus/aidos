param(
    [switch]$Build,
    [int]$Port = 0
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Exe = Join-Path $Root "engine\desktopapp\build\compose\binaries\main\app\AidosEngineDebug\AidosEngineDebug.exe"

if ($Build -or -not (Test-Path $Exe)) {
    $jdk17 = Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-17*" -Directory -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($jdk17) { $env:JAVA_HOME = $jdk17 }
    if (-not $env:ANDROID_HOME) {
        $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
        if (Test-Path $sdk) { $env:ANDROID_HOME = $sdk }
    }

    Write-Host "Building AidosEngineDebug distributable..."
    Push-Location (Join-Path $Root "engine")
    try {
        & ..\gradlew.bat :desktopapp:createDistributable
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally {
        Pop-Location
    }
}

$daemonArgs = @("--daemon")
if ($Port -gt 0) {
    $daemonArgs += @("--port", "$Port")
}

Write-Host "Starting Aidos Engine daemon..."
$daemonProc = Start-Process -FilePath $Exe -ArgumentList $daemonArgs -WindowStyle Hidden -PassThru

Write-Host "Starting Aidos tray icon..."
$trayProc = Start-Process -FilePath $Exe -ArgumentList "--tray" -PassThru

Write-Host "Started daemon (PID $($daemonProc.Id)) and tray (PID $($trayProc.Id))."
