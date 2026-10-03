param([string]$EnvFile = '.env')
$ErrorActionPreference = 'Stop'
# 初始化调用与 API 启动共用受控环境加载。
& (Join-Path $PSScriptRoot 'start-api.ps1') -EnvFile $EnvFile -InitializeIndex
exit $LASTEXITCODE
