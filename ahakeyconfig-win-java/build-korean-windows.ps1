param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$IconPath = (Join-Path $PSScriptRoot "VibeCodeKeyboard.ico"),
    [string]$WixBin = "",
    [string]$OutputRoot = "",
    [string]$PackageVersion = ""
)

# Korean build of AhaKey Studio for this fork.
# Inputs: `mvn clean package` output (target/*.jar, target/lib) and the BLE driver
# built from ../BLE_tcp_bridge (bin/Release/BLE_tcp_driver.exe). $JdkHome must be
# a JDK 17 whose jmods include JavaFX (for example Liberica "jdk+fx").
# Outputs: a portable app folder + zip, and with -WixBin a Korean setup EXE.
# Not included: local voice models (downloaded inside the app), firmware HEX and
# the vendor WCHISP flasher.
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$AppName = "AhaKeyStudioKR"
$UpgradeUuid = "5b0f3c1e-8d44-4c2b-9f7a-2f6c1d9e4a71"
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $PSScriptRoot "target\windows-ko"
}
[xml]$pom = Get-Content (Join-Path $PSScriptRoot "pom.xml") -Raw
$sourceVersion = [string]$pom.project.version
$version = if ([string]::IsNullOrWhiteSpace($PackageVersion)) { $sourceVersion } else { $PackageVersion }
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw "PackageVersion must have exactly three numeric components." }
$parsedVersion = [version]$version
if ($parsedVersion.Major -gt 255 -or $parsedVersion.Minor -gt 255 -or $parsedVersion.Build -gt 65535) {
    throw "PackageVersion exceeds Windows Installer limits (255.255.65535)."
}

$jarName = "ahakey-studio-$sourceVersion.jar"
$jar = Join-Path $PSScriptRoot "target\$jarName"
$lib = Join-Path $PSScriptRoot "target\lib"
$bridge = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\BLE_tcp_bridge\bin\Release\BLE_tcp_driver.exe"))
$jpackage = Join-Path $JdkHome "bin\jpackage.exe"
$jlink = Join-Path $JdkHome "bin\jlink.exe"
foreach ($required in @($jar, $lib, $bridge, $jpackage, $jlink, $IconPath)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing build input: $required" }
}
& (Join-Path $PSScriptRoot "Test-ReleaseArtifactContents.ps1") -JarPath $jar

$build = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) (Get-Date -Format "yyyyMMdd-HHmmss")
$runtime = Join-Path $build "runtime"
$inputDir = Join-Path $build "input"
$images = Join-Path $build "portable"
$installers = Join-Path $build "installer"
New-Item -ItemType Directory -Path $inputDir, $images, $installers -Force | Out-Null

# Java runtime with JavaFX. The module list covers every java.* / jdk.* / javafx.*
# API the client and its libraries (Jackson, JNA, Logback, opencc4j, Sherpa) use.
$modules = @(
    "java.base", "java.desktop", "java.logging", "java.management", "java.naming",
    "java.net.http", "java.prefs", "java.scripting", "java.sql", "java.xml",
    "java.instrument", "jdk.crypto.ec", "jdk.crypto.cryptoki", "jdk.localedata",
    "jdk.charsets", "jdk.unsupported", "jdk.zipfs", "jdk.accessibility",
    "jdk.management", "javafx.base", "javafx.graphics", "javafx.controls", "javafx.fxml"
) -join ","
& $jlink --module-path (Join-Path $JdkHome "jmods") --add-modules $modules `
    --output $runtime --strip-debug --no-header-files --no-man-pages --compress=2
if ($LASTEXITCODE -ne 0) { throw "jlink failed: $LASTEXITCODE" }
$listed = & (Join-Path $runtime "bin\java.exe") --list-modules
if (-not ($listed -match '^javafx.controls')) { throw "Runtime image is missing JavaFX." }

Copy-Item -LiteralPath $jar -Destination $inputDir
Copy-Item -LiteralPath $lib -Destination (Join-Path $inputDir "lib") -Recurse
New-Item -ItemType Directory -Path (Join-Path $inputDir "ble-driver") | Out-Null
Copy-Item -LiteralPath $bridge -Destination (Join-Path $inputDir "ble-driver\BLE_tcp_driver.exe")
if (Test-Path -LiteralPath "$bridge.config") {
    Copy-Item -LiteralPath "$bridge.config" -Destination (Join-Path $inputDir "ble-driver\BLE_tcp_driver.exe.config")
}
& (Join-Path $PSScriptRoot "Test-BleDriverPackaging.ps1") -ReleaseInputDir $inputDir

$arguments = @(
    "--type", "app-image", "--name", $AppName,
    "--app-version", $version, "--vendor", "AhaKey",
    "--description", "AhaKey Studio 한국어판",
    "--input", $inputDir, "--main-jar", $jarName,
    "--main-class", "com.example.ahakey.App",
    "--runtime-image", $runtime,
    "--icon", ([IO.Path]::GetFullPath($IconPath)), "--dest", $images,
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Dahakey.defaultLanguage=ko",
    "--java-options", "-Dahakey.koreanBuild=true",
    "--java-options", "-Dapp.version=$version",
    "--java-options", "-Dprism.allowhidpi=true",
    "--java-options", "--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED",
    "--java-options", "--add-opens=javafx.controls/com.sun.javafx.scene.control=ALL-UNNAMED",
    "--java-options", "--add-opens=javafx.fxml/com.sun.javafx.fxml=ALL-UNNAMED"
)
& $jpackage @arguments
if ($LASTEXITCODE -ne 0) { throw "App-image build failed: $LASTEXITCODE" }
$image = Join-Path $images $AppName
if (-not (Test-Path (Join-Path $image "$AppName.exe"))) { throw "EXE missing" }
@"
AhaKey Studio $version 한국어판

실행: $AppName.exe를 두 번 클릭해요. app, runtime 폴더를 EXE 옆에 그대로 두세요.
Java와 BLE 드라이버가 들어 있어 따로 설치할 것이 없어요.
로컬 음성 인식 모델은 앱 안에서 켤 때 내려받아요. 펌웨어 파일과 WCHISP 도구는 들어 있지 않아요.
게시자 서명이 없는 빌드라 Windows가 경고하면 '추가 정보'를 누른 뒤 '실행'을 눌러요.
"@ | Set-Content -LiteralPath (Join-Path $image "읽어 주세요.txt") -Encoding UTF8
$zip = Join-Path $build "AhaKey-Studio-Korean-Windows-Portable.zip"
Compress-Archive -LiteralPath $image -DestinationPath $zip
Write-Output "PORTABLE_ZIP=$zip"
Write-Output "PORTABLE_EXE=$image\$AppName.exe"

if (-not [string]::IsNullOrWhiteSpace($WixBin)) {
    foreach ($exe in @("candle.exe", "light.exe")) {
        if (-not (Test-Path (Join-Path $WixBin $exe))) { throw "WiX missing: $exe" }
    }
    $env:PATH = "$WixBin;$env:PATH"
    $resources = Join-Path $build "installer-resources"
    New-Item -ItemType Directory -Path $resources | Out-Null
    Copy-Item (Join-Path $PSScriptRoot "packaging\windows\*") -Destination $resources
    # Korean labels for the custom folder chooser; a separate folder and registry
    # key so this build never replaces an official AhaKeyStudio installation.
    $uiPath = Join-Path $resources "ui.wxf"
    $ui = [IO.File]::ReadAllText($uiPath)
    $labels = [ordered]@{
        '选择父目录，安装程序会自动创建 AhaKeyStudio 文件夹。' = '선택한 폴더 안에 AhaKeyStudioKR 폴더를 만들어 설치해요.'
        '请选择安装位置的父文件夹。实际安装目录将显示在下方。' = '설치할 위치의 상위 폴더를 선택하세요. 실제 설치 폴더는 아래에 보여요.'
        '程序文件夹：[ProgramFiles64Folder]AhaKeyStudio\' = '프로그램 폴더: [ProgramFiles64Folder]AhaKeyStudioKR\'
        '[ProductName] 安装程序' = '[ProductName] 설치'
        '下一步(&amp;N)' = '다음(&amp;N)'
        '上一步(&amp;B)' = '이전(&amp;B)'
        '浏览父文件夹...' = '찾아보기...'
        '选择安装位置' = '설치 위치'
        '取消' = '취소'
    }
    foreach ($label in $labels.Keys) { $ui = $ui.Replace($label, $labels[$label]) }
    [IO.File]::WriteAllText($uiPath, $ui, [Text.UTF8Encoding]::new($false))
    $mainPath = Join-Path $resources "main.wxs"
    $mainXml = [IO.File]::ReadAllText($mainPath)
    $mainXml = $mainXml.Replace('<Product', '<Product Codepage="949"').Replace('JpProductLanguage=1033', 'JpProductLanguage=1042')
    $mainXml = $mainXml.Replace('Software\AhaKey\AhaKeyStudio"', 'Software\AhaKey\AhaKeyStudioKR"')
    [IO.File]::WriteAllText($mainPath, $mainXml, [Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "packaging\windows-ko\strings_ko.wxl") -Destination $resources
    $installerArgs = @(
        "--type", "exe", "--name", $AppName, "--app-version", $version,
        "--app-image", $image, "--dest", $installers,
        "--vendor", "AhaKey", "--install-dir", $AppName,
        "--resource-dir", $resources, "--win-dir-chooser", "--win-shortcut",
        "--win-menu", "--win-menu-group", "AhaKey",
        "--win-upgrade-uuid", $UpgradeUuid,
        "--temp", (Join-Path $build "jpackage-temp")
    )
    & $jpackage @installerArgs
    if ($LASTEXITCODE -ne 0) { throw "Installer build failed: $LASTEXITCODE" }
    $installer = Join-Path $build "AhaKey-Studio-Korean-Setup.exe"
    Move-Item -LiteralPath (Join-Path $installers "$AppName-$version.exe") -Destination $installer
    Write-Output "INSTALLER=$installer"
}
Write-Output "BUILD_DIRECTORY=$build"
