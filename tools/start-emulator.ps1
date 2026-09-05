[CmdletBinding()]
param([ValidateSet('host','swiftshader')][string]$Graphics = 'host')
. "$PSScriptRoot/environment.ps1"
& "$env:ANDROID_HOME/emulator/emulator.exe" -accel-check
if ($LASTEXITCODE -ne 0) { throw 'Enable Windows Hypervisor Platform and reboot before starting the emulator.' }
$adb = "$env:ANDROID_HOME/platform-tools/adb.exe"
$name = @(& $adb -s emulator-5554 emu avd name 2>$null)
if ($LASTEXITCODE -eq 0) {
    if ($name[0] -ne 'Hardline_Relay_API34') { throw 'Port 5554 belongs to another emulator; leave it untouched.' }
    Write-Host 'Reusing Hardline_Relay_API34 on emulator-5554.'
} else {
    Start-Process -FilePath "$env:ANDROID_HOME/emulator/emulator.exe" -ArgumentList "-avd Hardline_Relay_API34 -port 5554 -no-snapshot -gpu $Graphics -memory 3072 -cores 4" -WindowStyle Hidden
}
Write-Host 'Waiting for Android boot and package service (up to 10 minutes on first boot)...'
$deadline = (Get-Date).AddMinutes(10)
do {
    $boot = & $adb -s emulator-5554 shell getprop sys.boot_completed 2>$null
    if ($LASTEXITCODE -eq 0 -and $boot -eq '1') {
        $packageService = & $adb -s emulator-5554 shell service check package 2>$null
        if ($LASTEXITCODE -eq 0 -and $packageService -match 'Service package: found') {
            Write-Host 'emulator-5554 is ready for APK installs and tests.'
            return
        }
    }
    Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)
throw 'Emulator did not finish booting. Inspect its window; no device installs were attempted.'
