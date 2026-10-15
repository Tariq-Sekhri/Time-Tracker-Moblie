$ErrorActionPreference = 'Stop'
$mobileRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $mobileRoot
try {
    & node (Join-Path $PSScriptRoot 'android.mjs') build --target aarch64 --aab
    if ($LASTEXITCODE -ne 0) { throw "Tauri Android build failed ($LASTEXITCODE)" }
} finally { Pop-Location }
$aabPath = Join-Path $mobileRoot 'src-tauri\gen\android\app\build\outputs\bundle\universalRelease\app-universal-release.aab'
if (!(Test-Path -LiteralPath $aabPath)) { throw "AAB was not produced: $aabPath" }
$destination = Join-Path (Split-Path -Parent $mobileRoot) 'app-release.aab'
Copy-Item -LiteralPath $aabPath -Destination $destination -Force
Write-Host "Signed Android bundle ready: $destination"
