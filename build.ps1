# Build of the Proxy app without Gradle: aapt2 + kotlinc + d8 + zipalign + apksigner (Android SDK 34).
#
# Tool locations (first one found wins):
#   1. parameters -Sdk -Jdk -Kotlinc
#   2. local.properties next to this script (sdk.dir, jdk.dir, kotlinc.dir) - machine specific, not committed
#   3. environment variables ANDROID_HOME / ANDROID_SDK_ROOT, JAVA_HOME, KOTLIN_HOME
#
# Signing (no password is ever written in this script):
#   1. environment variables PROXY_KEYSTORE, PROXY_KS_PASS, PROXY_KEY_ALIAS, PROXY_KEY_PASS
#   2. keystore.properties next to this script (storeFile, storePassword, keyAlias, keyPassword) - not committed
param([string]$Sdk = '', [string]$Jdk = '', [string]$Kotlinc = '')
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$versionCode = '10'
$versionName = '1.0'

function Read-Props($path) {
    $h = @{}
    if (Test-Path $path) {
        foreach ($line in Get-Content $path) {
            $t = $line.Trim()
            if ($t -eq '' -or $t.StartsWith('#') -or $t.StartsWith('!')) { continue }
            $i = $t.IndexOf('=')
            if ($i -lt 1) { continue }
            $h[$t.Substring(0, $i).Trim()] = ($t.Substring($i + 1).Trim() -replace '\\(.)', '$1')
        }
    }
    return $h
}

function Pick([string[]]$list) {
    foreach ($x in $list) { if ($x) { return $x } }
    return ''
}

function Need($label, $path, $hint) {
    if (-not $path -or -not (Test-Path $path)) { throw "Not found: $label ($path). $hint" }
}

# ---------------------------------------------------------------- tools
$lp = Read-Props (Join-Path $root 'local.properties')
$Sdk = Pick @($Sdk, $lp['sdk.dir'], $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
$Jdk = Pick @($Jdk, $lp['jdk.dir'], $env:JAVA_HOME)
$Kotlinc = Pick @($Kotlinc, $lp['kotlinc.dir'], $env:KOTLIN_HOME)
$bt = Join-Path $Sdk 'build-tools\34.0.0'
$jar = Join-Path $Sdk 'platforms\android-34\android.jar'
Need 'Android SDK' $Sdk 'Set sdk.dir in local.properties or ANDROID_HOME.'
Need 'build-tools 34.0.0' $bt 'Install it with sdkmanager "build-tools;34.0.0".'
Need 'android-34 platform' $jar 'Install it with sdkmanager "platforms;android-34".'
Need 'JDK 17 or newer' (Join-Path $Jdk 'bin\jar.exe') 'Set jdk.dir in local.properties or JAVA_HOME.'
Need 'kotlinc' (Join-Path $Kotlinc 'bin\kotlinc.bat') 'Set kotlinc.dir in local.properties or KOTLIN_HOME.'
$env:JAVA_HOME = $Jdk
$env:JAVA_TOOL_OPTIONS = ''

# ---------------------------------------------------------------- signing key (checked before building)
$kp = Read-Props (Join-Path $root 'keystore.properties')
$ks = Pick @($env:PROXY_KEYSTORE, $kp['storeFile'])
if ($ks -and -not [System.IO.Path]::IsPathRooted($ks)) { $ks = Join-Path $root $ks }
$alias = Pick @($env:PROXY_KEY_ALIAS, $kp['keyAlias'])
$ksPass = Pick @($env:PROXY_KS_PASS, $kp['storePassword'])
$keyPass = Pick @($env:PROXY_KEY_PASS, $kp['keyPassword'], $ksPass)
Need 'signing keystore' $ks 'Set PROXY_KEYSTORE and PROXY_KS_PASS, or create keystore.properties (storeFile, storePassword, keyAlias, keyPassword).'
if (-not $ksPass) { throw 'Keystore password missing: set PROXY_KS_PASS or storePassword in keystore.properties.' }
# handed to apksigner through the environment, never on the command line
$env:PROXY_KS_PASS = $ksPass
$env:PROXY_KEY_PASS = $keyPass

$b = Join-Path $root 'build'
if (Test-Path $b) { Remove-Item $b -Recurse -Force }
New-Item -ItemType Directory -Force "$b\classes", "$b\dex" | Out-Null

function Run($label, $exe, $argList) {
    $ErrorActionPreference = 'Continue'; $out = & $exe @argList 2>&1 | Out-String; $ErrorActionPreference = 'Stop'; $rc = $LASTEXITCODE
    if ($rc -ne 0) { throw "FAILED $label ($rc):`n$out" }
    if ($out.Trim()) { Write-Output "[$label] $($out.Trim())" }
}

# ---------------------------------------------------------------- build
# manifest with package name
$mf = Get-Content "$root\app\src\main\AndroidManifest.xml" -Raw
$mf = $mf -replace '<manifest xmlns:android="http://schemas.android.com/apk/res/android"', '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.furycom.proxy"'
Set-Content "$b\AndroidManifest.xml" $mf -Encoding UTF8

Run 'aapt2 compile' "$bt\aapt2.exe" @('compile', '--dir', "$root\app\src\main\res", '-o', "$b\res.zip")
Run 'aapt2 link' "$bt\aapt2.exe" @('link', '-o', "$b\base.apk", '-I', $jar, '--manifest', "$b\AndroidManifest.xml",
    '--min-sdk-version', '29', '--target-sdk-version', '34', '--version-code', $versionCode, '--version-name', $versionName, '-R', "$b\res.zip")

$src = Get-ChildItem "$root\app\src\main\java" -Recurse -Filter *.kt | % { $_.FullName }
Run 'kotlinc' "$Kotlinc\bin\kotlinc.bat" (@('-jvm-target', '17', '-no-stdlib', '-cp', "`"$jar;$Kotlinc\lib\kotlin-stdlib.jar`"", '-d', "$b\classes") + $src)

Run 'jar' "$env:JAVA_HOME\bin\jar.exe" @('cf', "$b\classes.jar", '-C', "$b\classes", '.')
Run 'd8' "$bt\d8.bat" @('--lib', $jar, '--min-api', '29', '--output', "$b\dex", "$b\classes.jar", "$Kotlinc\lib\kotlin-stdlib.jar")

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$b\base.apk", 'Update')
[void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$b\dex\classes.dex", 'classes.dex')
$zip.Dispose()

Run 'zipalign' "$bt\zipalign.exe" @('-f', '4', "$b\base.apk", "$b\aligned.apk")
$signArgs = @('sign', '--ks', $ks, '--ks-pass', 'env:PROXY_KS_PASS', '--key-pass', 'env:PROXY_KEY_PASS')
if ($alias) { $signArgs += @('--ks-key-alias', $alias) }
$apk = Join-Path $b "Proxy-v$versionName.apk"
$signArgs += @('--out', $apk, "$b\aligned.apk")
Run 'apksigner' "$bt\apksigner.bat" $signArgs
Get-Item $apk | Select-Object FullName, Length
