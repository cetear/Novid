param([string]$EnvFile = '.env', [switch]$InitializeIndex, [switch]$LoadEnvironmentOnly, [switch]$CleanupIndex, [ValidateRange(1,100)][int]$CleanupMaxBatches=20)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$envPath = if ([IO.Path]::IsPathRooted($EnvFile)) { [IO.Path]::GetFullPath($EnvFile) } else { [IO.Path]::GetFullPath((Join-Path $workspace $EnvFile)) }
if ($LoadEnvironmentOnly) {
    # 仅验收脚本需要进程环境；正式应用由 Java 加载所选文件。
    $allowed = Get-Content -LiteralPath (Join-Path $workspace 'lab-app/src/main/resources/local-environment-keys.txt') -Encoding utf8
    $values = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    if (Test-Path -LiteralPath $envPath) {
        $lineNumber = 0
        foreach ($line in Get-Content -LiteralPath $envPath -Encoding utf8) {
            $lineNumber++
            if ($line.Trim() -eq '' -or $line.TrimStart().StartsWith('#')) { continue }
            $separator = $line.IndexOf('=')
            if ($separator -lt 1) { throw ".env 第 $lineNumber 行格式错误" }
            $key = $line.Substring(0, $separator)
            if ($key -cnotin $allowed -or $values.ContainsKey($key)) { throw ".env 第 $lineNumber 行字段不受支持或重复" }
            $values.Add($key, $line.Substring($separator + 1))
        }
    }
    foreach ($key in $values.Keys) {
        if ($null -eq [Environment]::GetEnvironmentVariable($key, 'Process')) {
            [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
        }
    }
    return
}
if ($InitializeIndex -and $CleanupIndex) { throw '初始化与清理必须分别执行' }
$jar = Join-Path $workspace 'lab-app/target/lab-app-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw '请先在项目目录完成 Maven 构建' }
$arguments = @('-Dfile.encoding=UTF-8', '-jar', $jar, "--lab.env-file=$envPath")
if ($InitializeIndex -or $CleanupIndex) {
    $arguments += @('--lab.bootstrap.enabled=false', '--lab.search.enabled=true', '--lab.ingestion.worker-enabled=false', '--lab.task.worker-enabled=false', '--lab.media.worker-enabled=false', '--spring.main.web-application-type=none')
    if ($InitializeIndex) { $arguments += '--lab.command=init-index' }
    else { $arguments += @('--lab.command=cleanup-index', "--lab.cleanup.max-batches=$CleanupMaxBatches") }
}
Push-Location -LiteralPath $workspace
try {
    & java @arguments
    $applicationExitCode = $LASTEXITCODE
} finally { Pop-Location }
exit $applicationExitCode
