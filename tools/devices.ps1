. "$PSScriptRoot/environment.ps1"
$python = Join-Path $env:USERPROFILE 'AppData/Local/Programs/Python/Python313/python.exe'
& $python "$PSScriptRoot/device_lab.py" @args
if ($LASTEXITCODE -ne 0) { throw 'Device operation failed' }
