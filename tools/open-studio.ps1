[CmdletBinding()]
param()
. "$PSScriptRoot/environment.ps1"
$root = Split-Path -Parent $PSScriptRoot
$sdk = $env:ANDROID_HOME.Replace('\','/')
$properties = Join-Path $root 'local.properties'
if (-not (Test-Path -LiteralPath $properties)) {
    [IO.File]::WriteAllText($properties, "sdk.dir=$sdk" + [Environment]::NewLine)
}
$idea = Join-Path $root '.idea'
New-Item -ItemType Directory -Path $idea -Force | Out-Null
$gradleConfig = Join-Path $idea 'gradle.xml'
if (-not (Test-Path -LiteralPath $gradleConfig)) {
    $xml = '<project version="4"><component name="GradleSettings"><option name="linkedExternalProjectsSettings"><GradleProjectSettings><option name="externalProjectPath" value="$PROJECT_DIR$" /><option name="gradleJvm" value="#JAVA_HOME" /></GradleProjectSettings></option></component></project>'
    [IO.File]::WriteAllText($gradleConfig, $xml)
}
$studio = 'C:/Program Files/Android/Android Studio/bin/studio64.exe'
if (-not (Test-Path -LiteralPath $studio)) { throw 'Android Studio is not installed at its default location.' }
Start-Process -FilePath $studio -ArgumentList ('"' + $root + '"')
