param([switch]$DatabaseOnly)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $workspace
# 使用最终包隔离运行时，环境凭证只由现有加载器读入，不复制进证据目录。
$applicationJar = Join-Path $workspace 'lab-app/target/lab-app-0.1.0-SNAPSHOT.jar'
$applicationHash = (Get-FileHash -LiteralPath $applicationJar -Algorithm SHA256).Hash
# 不同专项使用不同目录，避免Windows运行中的JAR被另一专项解压覆盖；同专项应串行运行。
$runtimeKind = if ($DatabaseOnly) { 'database' } else { 'all' }
$runtimeRoot = [IO.Path]::GetFullPath((Join-Path $workspace ('var/stage-S08/runtime-' + $applicationHash.Substring(0,12).ToLowerInvariant() + '-' + $runtimeKind)))
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
# 正式包之外仅编译本专项入口；不会扫描或领取已有业务队列。
& javac -encoding UTF-8 -cp $classpath -d $runtimeRoot (Join-Path $PSScriptRoot 'validation/S08NativeValidation.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($DatabaseOnly) {
    # 显式参数防止PowerShell字符串展开导致专项开关丢失，数据库专项不得额外调用模型。
    & java '-Dfile.encoding=UTF-8' "-Dvalidation.application-jar-sha256=$applicationHash" -cp "$classpath;$runtimeRoot" com.example.ailab.app.S08NativeValidation '--database-only'
} else {
    & java '-Dfile.encoding=UTF-8' "-Dvalidation.application-jar-sha256=$applicationHash" -cp "$classpath;$runtimeRoot" com.example.ailab.app.S08NativeValidation
}
exit $LASTEXITCODE

