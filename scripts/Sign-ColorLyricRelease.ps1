[CmdletBinding(DefaultParameterSetName = 'Sign')]
param(
    [Parameter(Mandatory)] [string] $BuildTools,
    [Parameter(Mandatory, ParameterSetName = 'Sign')] [string] $OutputApk,
    [Parameter(Mandatory, ParameterSetName = 'Verify')] [string] $VerifyApk
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$keyDir = 'D:\Onedrive\文档\androidkey'
$store = Join-Path $keyDir 'DebPlus-release.jks'
$credentials = Join-Path $keyDir 'DebPlus-release-credentials.txt'
$reference = Join-Path $keyDir 'Color-lyric-DebPlus-release-signer.pem'
$signer = Join-Path $BuildTools 'apksigner.bat'
$aapt = Join-Path $BuildTools 'aapt.exe'
$zipalign = Join-Path $BuildTools 'zipalign.exe'

function Read-Certificate([string] $text) {
    $matches = [regex]::Matches($text, '-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\s]+?)\s*-----END CERTIFICATE-----')
    if ($matches.Count -ne 1) { throw 'Expected exactly one signing certificate.' }
    $matches[0].Groups[1].Value -replace '\s', ''
}

if (-not (Test-Path -LiteralPath $reference -PathType Leaf)) { throw "Missing release signer certificate: $reference" }
$expected = Read-Certificate (Get-Content -LiteralPath $reference -Raw)

function Assert-ColorLyricApk([string] $path) {
    $badging = & $aapt dump badging $path
    if ($LASTEXITCODE -ne 0 -or -not ($badging -match "^package: name='io\.github\.luckyumimi\.terramoor' ")) {
        throw 'The APK does not use the Color lyric release package name.'
    }
}

function Assert-ReleaseSigner([string] $path) {
    $output = & $signer verify --print-certs-pem $path
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    if ((Read-Certificate ($output -join "`n")) -cne $expected) {
        throw 'Signer certificate does not match the designated DebPlus release key.'
    }
}

if ($PSCmdlet.ParameterSetName -eq 'Verify') {
    Assert-ColorLyricApk $VerifyApk
    Assert-ReleaseSigner $VerifyApk
    Write-Output 'Color lyric release signer matches.'
    return
}

if (-not (Test-Path -LiteralPath $store -PathType Leaf)) { throw "Missing Color lyric keystore: $store" }
if (-not (Test-Path -LiteralPath $credentials -PathType Leaf)) { throw "Missing signing credentials: $credentials" }
$unsigned = Join-Path $repo 'app/build/outputs/apk/public/release/app-public-release-unsigned.apk'
Assert-ColorLyricApk $unsigned
$outputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputApk)
if (Test-Path -LiteralPath $outputPath) { throw "Output already exists: $outputPath" }

$temporary = Join-Path ([IO.Path]::GetTempPath()) ('color-lyric-sign-' + [guid]::NewGuid() + '.apk')
$credentialText = Get-Content -LiteralPath $credentials -Raw
function Read-Credential([string] $name) {
    $field = [regex]::Match($credentialText, '(?im)^[ \t]*' + [regex]::Escape($name) + '[ \t]*[:=][ \t]*([^\r\n]+)')
    if (-not $field.Success -or -not $field.Groups[1].Value.Trim()) { throw "Missing signing credential: $name" }
    $field.Groups[1].Value.Trim()
}

$previousStorePass = $env:COLOR_LYRIC_STORE_PASS
$previousKeyPass = $env:COLOR_LYRIC_KEY_PASS
try {
    $env:COLOR_LYRIC_STORE_PASS = Read-Credential 'Store password'
    $env:COLOR_LYRIC_KEY_PASS = Read-Credential 'Key password'
    $signArgs = @('sign', '--ks', $store, '--ks-key-alias', 'debplus', '--ks-pass',
        'env:COLOR_LYRIC_STORE_PASS', '--key-pass', 'env:COLOR_LYRIC_KEY_PASS', '--v4-signing-enabled', 'false',
        '--out', $temporary, $unsigned)
    & $signer @signArgs
    if ($LASTEXITCODE -ne 0) { throw 'Could not sign Color lyric APK.' }
    Assert-ReleaseSigner $temporary
    & $zipalign -c -P 16 4 $temporary
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment check failed.' }
    [IO.File]::Copy($temporary, $outputPath, $false)
    Write-Output $outputPath
} finally {
    $env:COLOR_LYRIC_STORE_PASS = $previousStorePass
    $env:COLOR_LYRIC_KEY_PASS = $previousKeyPass
    if (Test-Path -LiteralPath $temporary -PathType Leaf) { Remove-Item -LiteralPath $temporary }
}
