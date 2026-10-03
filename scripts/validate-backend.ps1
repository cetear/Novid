param([string]$EnvFile = '.env', [switch]$InspectDatabase, [switch]$Focused)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $workspace
# 复用正式入口的环境字段白名单，避免新增一份容易漂移的配置解析。
& (Join-Path $PSScriptRoot 'start-api.ps1') -EnvFile $EnvFile -LoadEnvironmentOnly
$applicationJar = Join-Path $workspace 'lab-app/target/lab-app-0.1.0-SNAPSHOT.jar'
$runtimeId = (Get-FileHash -LiteralPath $applicationJar -Algorithm SHA256).Hash.Substring(0,12).ToLowerInvariant()
$runtimeRoot = Join-Path $workspace ('var/backend-review/runtime-' + $runtimeId)
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($applicationJar)
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName.StartsWith('BOOT-INF/lib/') -and $entry.Name.EndsWith('.jar')) {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $runtimeRoot $entry.Name),$true)
        } elseif ($entry.FullName.StartsWith('BOOT-INF/classes/') -and $entry.Name) {
            $relative = $entry.FullName.Substring('BOOT-INF/classes/'.Length)
            $destination = [IO.Path]::GetFullPath((Join-Path (Join-Path $runtimeRoot 'classes') $relative))
            if (-not $destination.StartsWith($runtimeRoot + [IO.Path]::DirectorySeparatorChar)) { throw '非法归档路径' }
            New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$destination,$true)
        }
    }
} finally { $archive.Dispose() }
$classes = Join-Path $workspace 'var/backend-review/probe-classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$classpath = "$runtimeRoot/*;$runtimeRoot/classes;$classes"
& javac -encoding UTF-8 -cp $classpath -d $classes (Join-Path $PSScriptRoot 'validation/BackendValidation.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
# 验证会调用已配置真实模型，使用合成资料、有限输出；创建并清理独立临时库与索引。
[Environment]::SetEnvironmentVariable('REVIEW_LOCAL_MODEL_KEY', 'synthetic-local-only-key', 'Process')
if ($InspectDatabase) { & java '-Dfile.encoding=UTF-8' -cp $classpath BackendValidation --inspect-database }
elseif ($Focused) { & java '-Dfile.encoding=UTF-8' -cp $classpath BackendValidation --focused }
else { & java '-Dfile.encoding=UTF-8' -cp $classpath BackendValidation }
exit $LASTEXITCODE
