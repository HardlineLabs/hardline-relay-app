[CmdletBinding()]
param()
. "$PSScriptRoot/environment.ps1"
& "$env:ANDROID_HOME/emulator/emulator.exe" -accel-check
if ($LASTEXITCODE -ne 0) { throw 'Enable Windows Hypervisor Platform and reboot before starting the emulator.' }
Start-Process -FilePath "$env:ANDROID_HOME/emulator/emulator.exe" -ArgumentList '-avd Hardline_Relay_API34 -no-snapshot -gpu swiftshader -memory 2048 -cores 2' -WindowStyle Hidden
