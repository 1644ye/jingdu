param([string[]]$Tasks = @(':core:test', ':app:assembleDebug', ':app:assembleDebugAndroidTest'), [string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) {
    $javaCommand=Get-Command java -ErrorAction SilentlyContinue
    if($javaCommand){$JavaHome=Split-Path -Parent (Split-Path -Parent $javaCommand.Source)}
}
if (-not $JavaHome) { throw '请设置 JAVA_HOME 为 JDK 17 或更新版本，或传入 -JavaHome。' }
$env:JAVA_HOME = $JavaHome
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @Tasks --console=plain --no-configuration-cache
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location }
