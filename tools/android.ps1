param(
    [ValidateSet('Status','Start','Install','Test','Shot','Logs','Performance','Launch','Visuals','FreshStart')][string]$Action='Status',
    [string]$Sdk=$env:ANDROID_HOME,
    [string]$Serial='emulator-5554',
    [string]$TestClass='',
    [string]$Name='screen'
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
if(-not $Sdk) {
    $properties=Get-Content -LiteralPath (Join-Path $projectRoot 'local.properties')
    $sdkLine=$properties | Where-Object {$_ -like 'sdk.dir=*'} | Select-Object -First 1
    if($sdkLine) {$Sdk=$sdkLine.Substring(8).Replace('\\','\')}
}
if(-not $Sdk) {throw '请设置 ANDROID_HOME 或 local.properties 的 sdk.dir。'}
$adb=Join-Path $Sdk 'platform-tools\adb.exe'
$outputDir=Join-Path $projectRoot 'artifacts'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
switch($Action) {
    'Status' { & $adb devices -l; & $adb -s $Serial shell getprop sys.boot_completed }
    'Start' {
        $emulator=Join-Path $Sdk 'emulator\emulator.exe'
        Start-Process -FilePath $emulator -ArgumentList @('-avd','Medium_Phone_API_36','-port','5554','-no-window','-no-audio','-no-boot-anim','-no-snapshot-save','-gpu','swiftshader') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $outputDir 'emulator.log') -RedirectStandardError (Join-Path $outputDir 'emulator-error.log')
        Write-Output '已启动无窗口模拟器；使用 Status 检查启动状态。'
    }
    'Install' {
        & $adb -s $Serial install -r (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk')
        if($LASTEXITCODE -ne 0) {throw '应用 APK 安装失败。'}
        & $adb -s $Serial install -r (Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
        if($LASTEXITCODE -ne 0) {throw '测试 APK 安装失败。'}
    }
    'Test' {
        $testArgs=@('-s',$Serial,'shell','am','instrument','-w')
        if($TestClass) {$testArgs+=@('-e','class',$TestClass)}
        $testArgs+='org.quietreader.test/android.test.InstrumentationTestRunner'
        $stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
        $testOutput = & $adb @testArgs | Tee-Object -FilePath (Join-Path $outputDir "instrumentation-$stamp.txt")
        $testOutput | Write-Output
        if($LASTEXITCODE -ne 0 -or ($testOutput -join "`n") -notmatch 'OK \(\d+ tests?\)') {throw '设备测试未全部通过，请检查 instrumentation 日志。'}
    }
    'Shot' {
        if($Name -notmatch '^[a-zA-Z0-9_-]+$') {throw '截图名只能含字母数字下划线连字符。'}
        & $adb -s $Serial shell screencap -p /sdcard/quiet-reader-screen.png
        & $adb -s $Serial pull /sdcard/quiet-reader-screen.png (Join-Path $outputDir "$Name.png")
    }
    'Logs' { & $adb -s $Serial logcat -d -s 'QuietReaderPerf:I' 'AndroidRuntime:E' 'QuietReaderPositions:E' '*:S' | Tee-Object -FilePath (Join-Path $outputDir 'device-log.txt') }
    'Performance' {
        & $adb -s $Serial shell run-as org.quietreader cat files/performance-result.json | Set-Content -LiteralPath (Join-Path $outputDir 'performance-result.json') -Encoding utf8
    }
    'Visuals' { & $adb -s $Serial pull /sdcard/Android/data/org.quietreader/files/visual $outputDir }
    'FreshStart' {
        if($Serial -notmatch '^emulator-\d+$') {throw 'FreshStart 仅允许用于专用测试模拟器，会清空静读的测试数据。'}
        $isEmulator = & $adb -s $Serial shell getprop ro.kernel.qemu
        if(($isEmulator -join '').Trim() -ne '1') {throw '无法确认设备为模拟器，拒绝清理数据。'}
        & $adb -s $Serial shell pm clear org.quietreader
        if($LASTEXITCODE -ne 0) {throw '模拟器测试数据未能清理。'}
        & $adb -s $Serial shell am start -n org.quietreader/.MainActivity
    }
    'Launch' { & $adb -s $Serial shell am start -n org.quietreader/.MainActivity }
}
