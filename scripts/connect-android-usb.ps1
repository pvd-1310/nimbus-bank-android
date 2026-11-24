param(
    [string]$AdbPath,
    [string]$Serial,
    [ValidateRange(1, 65535)][int]$BackendPort = 3001
)

$ErrorActionPreference = 'Stop'

if (-not $AdbPath) {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { $AdbPath = $command.Source }
}
if (-not $AdbPath) {
    foreach ($sdkRoot in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if (-not $sdkRoot) { continue }
        $candidate = Join-Path $sdkRoot 'platform-tools/adb.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $AdbPath = $candidate
            break
        }
    }
}
if (-not $AdbPath) {
    $properties = Join-Path (Split-Path -Parent $PSScriptRoot) 'local.properties'
    if (Test-Path -LiteralPath $properties -PathType Leaf) {
        $sdkLine = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) {
            $sdkRoot = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
            $candidate = Join-Path $sdkRoot 'platform-tools/adb.exe'
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { $AdbPath = $candidate }
        }
    }
}
if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw 'adb.exe was not found. Install Android SDK Platform-Tools or pass -AdbPath "C:\...\adb.exe".'
}

$devicesOutput = & $AdbPath devices
if ($LASTEXITCODE -ne 0) { throw 'Could not list ADB devices.' }
$devices = @($devicesOutput | ForEach-Object {
    if ($_ -match '^([^\s]+)\s+device\s*$') { $Matches[1] }
})
$physicalDevices = @($devices | Where-Object { $_ -notmatch '^emulator-' })
if ($Serial) {
    if ($Serial -notin $devices) { throw "Device $Serial is disconnected or has not accepted USB debugging." }
} elseif ($physicalDevices.Count -eq 1) {
    $Serial = $physicalDevices[0]
} else {
    throw 'Exactly one authorized physical device is required. If several are connected, pass -Serial.'
}

& $AdbPath -s $Serial reverse "tcp:$BackendPort" "tcp:$BackendPort"
if ($LASTEXITCODE -ne 0) { throw 'ADB reverse failed.' }
$mapping = & $AdbPath -s $Serial reverse --list
if ($LASTEXITCODE -ne 0 -or -not ($mapping -match "tcp:$BackendPort\s+tcp:$BackendPort")) {
    throw 'ADB reverse mapping was not listed after setup.'
}

Write-Host "Device $Serial uses http://127.0.0.1:$BackendPort/ over USB."
Write-Host 'Run this script again after disconnecting USB or rebooting the phone.'
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$BackendPort/health" -TimeoutSec 3
    if ($health.status -eq 'ok') { Write-Host 'Host backend: OK.' }
    else { Write-Warning 'The backend responded but /health was not ok.' }
} catch {
    Write-Warning "Host backend is not responding on port $BackendPort. ADB reverse is already set."
}
