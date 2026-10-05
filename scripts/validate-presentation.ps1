param([ValidateSet('all','restart-write','restart-read')][string]$Phase='all')
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $workspace
# 同一专项运行互斥，防止Windows解包覆盖运行中JAR或同时修改恢复夹具。
New-Item -ItemType Directory -Path (Join-Path $workspace 'var/stage-S10') -Force | Out-Null
try { $validationLock = [IO.File]::Open((Join-Path $workspace 'var/stage-S10/validation.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) } catch { throw 'S10专项已在运行；请待当前专项结束后串行执行' }
try {
# 使用最终包隔离运行时，环境凭证只由现有加载器读入，不复制进证据目录。
$applicationJar = Join-Path $workspace 'lab-app/target/lab-app-0.1.0-SNAPSHOT.jar'
$applicationHash = (Get-FileHash -LiteralPath $applicationJar -Algorithm SHA256).Hash
# 不同专项使用不同目录，避免Windows运行中的JAR被另一专项解压覆盖；同专项应串行运行。
$runtimeKind = 'presentation'
$runtimeRoot = [IO.Path]::GetFullPath((Join-Path $workspace ('var/stage-S10/runtime-' + $applicationHash.Substring(0,12).ToLowerInvariant() + '-' + $runtimeKind)))
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
$entryClass = 'S10NativeValidation'
& javac -encoding UTF-8 -cp $classpath -d $runtimeRoot (Join-Path $PSScriptRoot ('validation/' + $entryClass + '.java')) (Join-Path $PSScriptRoot 'validation/ValidationSql.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
# 入口没有购买参数；媒体／报告／入库扫描全部关闭。
if($Phase -eq 'all'){
    & java '-Dfile.encoding=UTF-8' "-Dvalidation.application-jar-sha256=$applicationHash" -cp "$classpath;$runtimeRoot" "com.example.ailab.app.$entryClass"
}else{
    & java '-Dfile.encoding=UTF-8' "-Dvalidation.application-jar-sha256=$applicationHash" -cp "$classpath;$runtimeRoot" "com.example.ailab.app.$entryClass" $Phase
}
exit $LASTEXITCODE

} finally { $validationLock.Dispose() }
