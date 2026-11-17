# 编译语音球 APK（不依赖 Gradle，javac + d8 + aapt2 + apksigner）
#
# 只需要三样东西：
#   JDK 8+        —— 改下面 $jdk
#   Android SDK   —— 改下面 $sdk，要求 build-tools/34.0.0 和 platforms/android-34
#   jsch-0.1.55.jar —— 放在 lib/ 下（README 里有链接）
#
$ErrorActionPreference = 'Stop'
$根   = $PSScriptRoot
$jdk  = 'C:\android\build\jdk'                     # ← 改这里
$sdk  = 'C:\android\build\sdk'                     # ← 改这里
$bt   = Join-Path $sdk 'build-tools\34.0.0'
$plat = Join-Path $sdk 'platforms\android-34\android.jar'
$src  = Join-Path $根 'src'
$res  = Join-Path $根 'res'
$lib  = Join-Path $根 'lib\jsch-0.1.55.jar'
$out  = Join-Path $根 'out'
$pkg  = 'com.yuhua.voicebox'

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$bt;$env:PATH"

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path "$out\classes","$out\dex" -Force | Out-Null

Write-Host '1) 编译 java …'
$files = Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$旧 = $ErrorActionPreference
$ErrorActionPreference = 'Continue'   # javac 的提示走 stderr
& "$jdk\bin\javac.exe" -source 8 -target 8 -nowarn -encoding UTF-8 `
    -classpath "$plat;$lib" -d "$out\classes" $files 2>&1 | ForEach-Object { Write-Host "   $_" }
$码 = $LASTEXITCODE
$ErrorActionPreference = $旧
if ($码 -ne 0) { throw "javac 失败（$码）" }

Write-Host '2) 转 dex …'
& "$bt\d8.bat" --min-api 26 --output "$out\dex" `
    (Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }) $lib 2>&1 |
    ForEach-Object { Write-Host "   $_" }

Write-Host '3) 打包资源 …'
$资源参数 = @()
if (Test-Path $res) { $资源参数 += @('-S', $res) }
$assets = Join-Path $根 'assets'
if (Test-Path $assets) { $资源参数 += @('-A', $assets) }
& "$bt\aapt2.exe" link -o "$out\base.apk" --manifest (Join-Path $根 'AndroidManifest.xml') `
    -I $plat --min-sdk-version 26 --target-sdk-version 34 @资源参数 2>&1 |
    ForEach-Object { Write-Host "   $_" }

Write-Host '4) 塞 dex …'
Copy-Item "$out\dex\classes.dex" "$out\classes.dex"
& "$jdk\bin\jar.exe" uf "$out\base.apk" -C "$out" classes.dex 2>&1 | ForEach-Object { Write-Host "   $_" }

Write-Host '5) 对齐 + 签名 …'
& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
$ks = Join-Path $根 'debug.keystore'
if (-not (Test-Path $ks)) {
    & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" 2>&1 | Select-Object -Last 1
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out (Join-Path $根 '语音球.apk') "$out\aligned.apk" 2>&1 | ForEach-Object { Write-Host "   $_" }

$apk = Join-Path $根 '语音球.apk'
Write-Host ("6) 好了：{0}（{1:N0} KB）" -f $apk, ((Get-Item $apk).Length / 1KB))
