param([Parameter(Mandatory)][string]$OtherRepository)
$ErrorActionPreference = 'Stop'
$own = Join-Path (Split-Path -Parent $PSScriptRoot) 'protocol'
foreach ($name in @('README.md','status-v1.tsv','pli-v1.md','point-v1.md','channel-profile-v1.md')) {
    $other = Join-Path $OtherRepository "protocol/$name"
    if ((Get-FileHash -LiteralPath (Join-Path $own $name)).Hash -ne (Get-FileHash -LiteralPath $other).Hash) {
        throw "Protocol differs: $name"
    }
}
'Protocol snapshots match.'
