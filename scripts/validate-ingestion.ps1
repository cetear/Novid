param([string]$EnvFile = '.env')
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $workspace
# 按最终应用包摘要隔离依赖，防止专项验证误用旧编译结果；凭证由正式 UTF-8 加载器读取。
$applicationJar = Join-Path $workspace 'lab-app/target/lab-app-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $applicationJar)) { throw '请先完成本阶段构建' }
$applicationHash = (Get-FileHash -LiteralPath $applicationJar -Algorithm SHA256).Hash
$runtimeId = $applicationHash.Substring(0,12).ToLowerInvariant()
$runtimeRoot = [IO.Path]::GetFullPath((Join-Path $workspace ('var/stage-S03/runtime-' + $runtimeId)))
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
& javac -encoding UTF-8 -cp $classpath -d $runtimeRoot (Join-Path $PSScriptRoot 'validation/IngestionNativeValidation.java') (Join-Path $PSScriptRoot 'validation/ValidationSql.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
# 新随机库优先；没有建库权限时只精确清理自身夹具，所有自动扫描关闭。
& java '-Dfile.encoding=UTF-8' "-Dvalidation.application-jar-sha256=$applicationHash" -cp "$classpath;$runtimeRoot" com.example.ailab.app.IngestionNativeValidation $EnvFile
exit $LASTEXITCODE
