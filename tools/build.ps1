param([string[]]$Tasks = @(':core:test', ':app:assembleDebug', ':app:assembleDebugAndroidTest'), [string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) {
    $studioJbr = (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr')
    if (Test-Path -LiteralPath (Join-Path $studioJbr 'bin\java.exe')) { $JavaHome = $studioJbr }
}
if (-not $JavaHome) { throw '请设置 JAVA_HOME 为 JDK 17 或更新版本，或传入 -JavaHome。' }
$env:JAVA_HOME = $JavaHome
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @Tasks --console=plain --no-configuration-cache
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location }
