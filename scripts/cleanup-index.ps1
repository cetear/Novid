param([string]$EnvFile='.env',[ValidateRange(1,100)][int]$MaxBatches=20)
$ErrorActionPreference='Stop'
# 复用正式环境白名单，关闭账号初始化和自动 Worker，仅执行有限 Outbox 清理。
& (Join-Path $PSScriptRoot 'start-api.ps1') -EnvFile $EnvFile -CleanupIndex -CleanupMaxBatches $MaxBatches
exit $LASTEXITCODE
