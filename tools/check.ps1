[CmdletBinding()]
param([switch]$Connected)
. "$PSScriptRoot/environment.ps1"
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $tasks = @(':core:test', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', ':app:assembleDebugAndroidTest')
    if ($Connected) { $tasks += ':app:connectedDebugAndroidTest' }
    & ./gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed' }
} finally { Pop-Location }
