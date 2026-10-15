param([switch]$InstallAndroidSdk, [switch]$AcceptAndroidSdkLicenses)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskTools = Join-Path $taskRoot '.tools'
New-Item -ItemType Directory -Force $taskTools | Out-Null
$jdkDir = Get-ChildItem $taskTools -Directory -Filter 'jdk-17*' | Select-Object -First 1
if (-not $jdkDir) {
    $jdkZip = Join-Path $taskTools 'jdk.zip'
    Invoke-WebRequest -Uri 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile $jdkZip
    Expand-Archive -LiteralPath $jdkZip -DestinationPath $taskTools -Force
    $jdkDir = Get-ChildItem $taskTools -Directory -Filter 'jdk-17*' | Select-Object -First 1
}
$env:JAVA_HOME = $jdkDir.FullName
# A short, unsynchronized cache avoids Windows path limits and OneDrive file locks.
$env:GRADLE_USER_HOME = Join-Path ([System.IO.Path]::GetTempPath()) 'expoworld-gradle'
$env:ANDROID_USER_HOME = Join-Path ([System.IO.Path]::GetTempPath()) 'expoworld-android'
New-Item -ItemType Directory -Force $env:ANDROID_USER_HOME | Out-Null
$sdkDir = Join-Path $taskTools 'android-sdk'
if (Test-Path (Join-Path $sdkDir 'platforms/android-36/android.jar')) { $env:ANDROID_HOME = $sdkDir }
if ($InstallAndroidSdk) {
    $cmdDir = Join-Path $sdkDir 'cmdline-tools'
    $latestDir = Join-Path $cmdDir 'latest'
    if (-not (Test-Path (Join-Path $latestDir 'bin/sdkmanager.bat'))) {
        $sdkZip = Join-Path $taskTools 'android-tools.zip'
        # Pinned official command-line tools compatible with JDK 17.
        Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' -OutFile $sdkZip
        $unpackDir = Join-Path $taskTools 'sdk-unpack'
        Expand-Archive -LiteralPath $sdkZip -DestinationPath $unpackDir -Force
        New-Item -ItemType Directory -Force $cmdDir | Out-Null
        Copy-Item -LiteralPath (Join-Path $unpackDir 'cmdline-tools') -Destination $latestDir -Recurse -Force
    }
    $env:ANDROID_HOME = $sdkDir
    $sdkManager = Join-Path $latestDir 'bin/sdkmanager.bat'
    # Review/accept only the licenses of the requested packages.
    if ($AcceptAndroidSdkLicenses) {
        @('y','y','y','y') | & $sdkManager "--sdk_root=$sdkDir" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
    } else { & $sdkManager "--sdk_root=$sdkDir" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools' }
    if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed' }
}
Set-Location -LiteralPath $taskRoot
& .\gradlew.bat :core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --console=plain
exit $LASTEXITCODE
