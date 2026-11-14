# Builds MyBookSpace.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#   .\build.ps1                 build only  (output: build\MyBookSpace.apk)
#   .\build.ps1 -Install        build and install on the connected device
#   .\build.ps1 -Install -Serial 10.0.0.5:5555
param([switch]$Install, [string]$Serial)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$tc = if ($env:FOLIO_TOOLCHAIN) { $env:FOLIO_TOOLCHAIN } else { "$env:USERPROFILE\android-toolchain" }
$jdk = (Get-ChildItem $tc -Directory -Filter "jdk-17*" | Select-Object -First 1).FullName
$bt = "$tc\sdk\build-tools\34.0.0"
$androidJar = "$tc\sdk\platforms\android-34\android.jar"
if (-not $jdk -or -not (Test-Path "$bt\aapt2.exe") -or -not (Test-Path $androidJar)) {
    throw "Toolchain not found in $tc (need jdk-17*, sdk\build-tools\34.0.0, sdk\platforms\android-34)."
}
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"

function Step($name, [scriptblock]$cmd) {
    & $cmd
    if ($LASTEXITCODE -ne 0) { throw "$name failed (exit $LASTEXITCODE)" }
}

$out = "$root\build"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\classes" | Out-Null

Step "aapt2 compile" { & "$bt\aapt2.exe" compile --dir "$root\res" -o "$out\res.zip" }
Step "aapt2 link" {
    & "$bt\aapt2.exe" link -o "$out\unaligned.apk" -I $androidJar --manifest "$root\AndroidManifest.xml" `
        --min-sdk-version 26 --target-sdk-version 34 --version-code 2 --version-name 1.1 "$out\res.zip"
}

$sources = Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Step "javac" { & "$jdk\bin\javac.exe" -source 8 -target 8 -Xlint:-options -encoding UTF-8 -cp $androidJar -d "$out\classes" $sources }
Step "jar" { & "$jdk\bin\jar.exe" cf "$out\classes.jar" -C "$out\classes" . }
Step "d8" { & "$bt\d8.bat" --release --lib $androidJar --min-api 26 --output $out "$out\classes.jar" }

Push-Location $out
try { Step "aapt add" { & "$bt\aapt.exe" add unaligned.apk classes.dex | Out-Null } } finally { Pop-Location }
Step "zipalign" { & "$bt\zipalign.exe" -f -p 4 "$out\unaligned.apk" "$out\aligned.apk" }

# The signing key identifies the app to Android: keep it, or updates will need an uninstall first
# (which deletes the library). It lives beside the toolchain, outside the repo, so every checkout
# and worktree signs with the same key.
$ks = if ($env:FOLIO_KEYSTORE) { $env:FOLIO_KEYSTORE } else { "$tc\folio-debug.keystore" }
if (-not (Test-Path $ks)) {
    Step "keytool" {
        & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android -alias folio `
            -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Folio Debug" | Out-Null
    }
}
Step "apksigner" { & "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$out\MyBookSpace.apk" "$out\aligned.apk" }

$size = [math]::Round((Get-Item "$out\MyBookSpace.apk").Length / 1KB)
"Built $out\MyBookSpace.apk ($size KB)"

if ($Install) {
    $target = if ($Serial) { @("-s", $Serial) } else { @() }
    Step "adb install" { & adb @target install -r "$out\MyBookSpace.apk" }
}
