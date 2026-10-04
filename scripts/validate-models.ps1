param([string]$EnvFile = '.env')
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $workspace
# 依赖按最终包摘要隔离；专项不装配数据库，也不执行任何自动扫描或迁移。
$applicationJar = Join-Path $workspace 'lab-app/target/lab-app-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $applicationJar)) { throw '请先完成S04构建' }
$applicationHash = (Get-FileHash -LiteralPath $applicationJar -Algorithm SHA256).Hash
$runtimeRoot = [IO.Path]::GetFullPath((Join-Path $workspace ('var/stage-S04/runtime-' + $applicationHash.Substring(0,12).ToLowerInvariant())))
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($applicationJar)
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName.StartsWith('BOOT-INF/lib/') -and $entry.Name.EndsWith('.jar')) {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $runtimeRoot $entry.Name),$true)
        } elseif ($entry.FullName.StartsWith('BOOT-INF/classes/') -and $entry.Name) {
            $destination = [IO.Path]::GetFullPath((Join-Path (Join-Path $runtimeRoot 'classes') $entry.FullName.Substring('BOOT-INF/classes/'.Length)))
            if (-not $destination.StartsWith($runtimeRoot + [IO.Path]::DirectorySeparatorChar)) { throw '非法归档路径' }
            New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$destination,$true)
        }
    }
} finally { $archive.Dispose() }
$classpath = "$runtimeRoot/classes;$runtimeRoot/*"
& javac -encoding UTF-8 -cp $classpath -d $runtimeRoot (Join-Path $PSScriptRoot 'validation/ModelNativeValidation.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
# 只有三条固定合成样本／启用目标，小预算真实聊天；不修改.env，不读用户正文。
& java '-Dfile.encoding=UTF-8' "-Dvalidation.application-jar-sha256=$applicationHash" -cp "$classpath;$runtimeRoot" com.example.ailab.app.ModelNativeValidation $EnvFile
exit $LASTEXITCODE
