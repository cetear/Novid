param([string]$EnvFile = '.env')
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $workspace
# 复用白名单环境读取；合成验证数据事务回滚，不清空配置库或领取用户队列。
& (Join-Path $PSScriptRoot 'start-api.ps1') -EnvFile $EnvFile -LoadEnvironmentOnly
$applicationJar = Join-Path $workspace 'lab-app/target/lab-app-0.1.0-SNAPSHOT.jar'
$runtimeId = (Get-FileHash -LiteralPath $applicationJar -Algorithm SHA256).Hash.Substring(0,12).ToLowerInvariant()
$runtimeRoot = [IO.Path]::GetFullPath((Join-Path $workspace ('var/task-progress/runtime-' + $runtimeId)))
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
& javac -encoding UTF-8 -cp $classpath -d $runtimeRoot (Join-Path $PSScriptRoot 'validation/TaskProgressRollbackValidation.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
# 正式 V4 迁移正常执行；合成数据全部回滚，租约只针对合成任务模拟。
& java '-Dfile.encoding=UTF-8' -cp "$classpath;$runtimeRoot" TaskProgressRollbackValidation
exit $LASTEXITCODE
