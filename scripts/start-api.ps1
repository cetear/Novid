param([string]$EnvFile = '.env', [switch]$InitializeIndex, [switch]$LoadEnvironmentOnly, [switch]$CleanupIndex, [ValidateRange(1,100)][int]$CleanupMaxBatches=20)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$allowed = @('DB_URL','DB_USERNAME','DB_PASSWORD','BOOTSTRAP_ENABLED','BOOTSTRAP_USERNAME','BOOTSTRAP_PASSWORD','SEARCH_ENABLED','INGESTION_WORKER_ENABLED','TASK_WORKER_ENABLED','ES_URL','ES_USERNAME','ES_PASSWORD','ES_CA_CERTIFICATE','ES_TRUST_ALL','ES_INDEX','MODEL_MODE','MODEL_EXTERNAL_DATA_ALLOWED','MODEL_PRIMARY_NAME','MODEL_BACKUP_NAME','MODEL_BACKUP_ENABLED','MODEL_BASE_URL','MODEL_BACKUP_BASE_URL','MODEL_API_KEY','MODEL_BACKUP_API_KEY','EMBEDDING_MODEL_NAME','EMBEDDING_ENABLED','EMBEDDING_BASE_URL','EMBEDDING_CREDENTIAL_REF','EMBEDDING_API_KEY','EMBEDDING_DIMENSIONS','PORT','MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD')
# .env 只作为文本读取，不执行其中任何 PowerShell 语句。
$envPath = Join-Path $workspace $EnvFile
if (Test-Path -LiteralPath $envPath) {
    foreach ($line in Get-Content -LiteralPath $envPath -Encoding utf8) {
        if ($line.Trim() -eq '' -or $line.Trim().StartsWith('#')) { continue }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw '.env 格式错误' }
        $key = $Matches[1]
        if ($key -notin $allowed) { throw '发现不允许的环境配置字段' }
        [Environment]::SetEnvironmentVariable($key, $Matches[2], 'Process')
    }
}
if ($LoadEnvironmentOnly) { return }
if ($InitializeIndex -and $CleanupIndex) { throw '初始化与清理必须分别执行' }
$jar = Join-Path $workspace 'lab-app/target/lab-app-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw '请先在项目目录完成 Maven 构建' }
# 仅明确初始化命令建索引；已有索引不会删除。
# 明确 UTF-8，避免维护命令的中文结果被本机默认编码破坏。
if ($InitializeIndex) { & java '-Dfile.encoding=UTF-8' -jar $jar --lab.command=init-index --lab.bootstrap.enabled=false --lab.search.enabled=true --lab.ingestion.worker-enabled=false --lab.task.worker-enabled=false --spring.main.web-application-type=none }
elseif ($CleanupIndex) { & java '-Dfile.encoding=UTF-8' -jar $jar --lab.command=cleanup-index "--lab.cleanup.max-batches=$CleanupMaxBatches" --lab.bootstrap.enabled=false --lab.search.enabled=true --lab.ingestion.worker-enabled=false --lab.task.worker-enabled=false --spring.main.web-application-type=none }
else { & java '-Dfile.encoding=UTF-8' -jar $jar }
exit $LASTEXITCODE
