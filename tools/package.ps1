param([string]$Version='1.0.1')
$ErrorActionPreference='Stop'
if($Version -notmatch '^\d+\.\d+\.\d+$') {throw '版本号应为数字形式，例如 1.0.0。'}
$projectRoot=[IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$outputDir=Join-Path $projectRoot 'artifacts'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$apk=Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
if(-not (Test-Path -LiteralPath $apk)) {throw '请先构建 :app:assembleDebug。'}
$apkOutput=Join-Path $outputDir "QuietReader-$Version-debug.apk"
Copy-Item -LiteralPath $apk -Destination $apkOutput -Force

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Write-Archive([string]$Destination,[IO.FileInfo[]]$Files,[string]$BaseDirectory) {
    $base=[IO.Path]::GetFullPath($BaseDirectory).TrimEnd('\')+'\'
    $stream=[IO.File]::Open($Destination,[IO.FileMode]::Create,[IO.FileAccess]::Write)
    try {
        $zip=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create,$true)
        try {
            foreach($file in ($Files | Sort-Object FullName -Unique)) {
                if(-not $file.FullName.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)) {throw "文件不在打包目录中：$($file.FullName)"}
                $relative=$file.FullName.Substring($base.Length).Replace('\','/')
                [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$relative,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
            }
        } finally {$zip.Dispose()}
    } finally {$stream.Dispose()}
}

$sourceFiles=[Collections.Generic.List[IO.FileInfo]]::new()
$rootFiles=@('.gitignore','.gitattributes','README.md','REQUIREMENTS.md','IMPLEMENTATION.md','ACCEPTANCE.md','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat','app/build.gradle.kts','core/build.gradle.kts','core/VERIFICATION.md')
foreach($name in $rootFiles) {$sourceFiles.Add((Get-Item -LiteralPath (Join-Path $projectRoot $name)))}
foreach($directory in @('app/src','core/src','gradle','docs','tools','test-data')) {
    Get-ChildItem -LiteralPath (Join-Path $projectRoot $directory) -Recurse -File | ForEach-Object {$sourceFiles.Add($_)}
}
foreach($name in @('artifacts/instrumentation-final.txt','artifacts/performance-result.json','artifacts/device-log.txt','artifacts/first-launch.png','artifacts/shelf-1-0-1.png')) {
    $sourceFiles.Add((Get-Item -LiteralPath (Join-Path $projectRoot $name)))
}
foreach($directory in @('artifacts/verification','artifacts/visual')) {
    Get-ChildItem -LiteralPath (Join-Path $projectRoot $directory) -Recurse -File | ForEach-Object {$sourceFiles.Add($_)}
}
$sourceOutput=Join-Path $outputDir "QuietReader-$Version-source.zip"
Write-Archive $sourceOutput $sourceFiles.ToArray() $projectRoot

$templateRoot=Join-Path $projectRoot 'core/src/main/resources/protocol'
$templateOutput=Join-Path $outputDir "QuietReader-$Version-templates.zip"
Write-Archive $templateOutput @(Get-ChildItem -LiteralPath $templateRoot -File) $templateRoot
$packages=@($apkOutput,$sourceOutput,$templateOutput)
$checksums=foreach($file in $packages) {
    $hash=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $([IO.Path]::GetFileName($file))"
}
[IO.File]::WriteAllLines((Join-Path $outputDir 'SHA256SUMS.txt'),$checksums,[Text.UTF8Encoding]::new($false))
$packages | ForEach-Object {Get-Item -LiteralPath $_} | Select-Object Name,Length
$checksums | Write-Output
