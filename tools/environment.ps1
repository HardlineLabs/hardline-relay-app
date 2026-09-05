$ErrorActionPreference = 'Stop'
$devRoot = if ($env:HARDLINE_RELAY_DEV) { $env:HARDLINE_RELAY_DEV } else { Join-Path $env:USERPROFILE 'HardlineRelayDev' }
$env:JAVA_HOME = Join-Path $devRoot 'tools/jdk-17.0.20.1+1'
$env:ANDROID_HOME = Join-Path $devRoot 'AndroidSdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $devRoot 'gradle-cache'
$env:ANDROID_AVD_HOME = Join-Path $devRoot 'avd'
$env:PATH = "$env:JAVA_HOME/bin;$env:ANDROID_HOME/platform-tools;$env:ANDROID_HOME/emulator;" + [Environment]::GetEnvironmentVariable('Path','User') + ';' + [Environment]::GetEnvironmentVariable('Path','Machine')
$env:PYTHONDONTWRITEBYTECODE = '1'
if (-not (Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe")) { throw "Run workstation bootstrap first: missing $env:JAVA_HOME" }
