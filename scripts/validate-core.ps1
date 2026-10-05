param([switch]$RecoverOnly)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $workspace
$root = Join-Path $workspace 'var/stage-S11'
New-Item -ItemType Directory -Path $root -Force | Out-Null
# 单专项互斥；不使用全局进程名查杀，也不覆盖另一轮尚未清理的恢复夹具。
$lock = [IO.File]::Open((Join-Path $root 'validation.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$cases = [Collections.Generic.List[object]]::new()
$child = $null
try {
    $jar = Join-Path $workspace 'lab-app/target/lab-app-0.1.0-SNAPSHOT.jar'
    $jarHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
    $runtime = Join-Path $root ('runtime-' + $jarHash.Substring(0,12).ToLowerInvariant())
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName.StartsWith('BOOT-INF/lib/') -and $entry.Name.EndsWith('.jar')) {
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $runtime $entry.Name), $true)
            } elseif ($entry.FullName.StartsWith('BOOT-INF/classes/') -and $entry.Name) {
                $target = [IO.Path]::GetFullPath((Join-Path (Join-Path $runtime 'classes') $entry.FullName.Substring(17)))
                if (-not $target.StartsWith($runtime + [IO.Path]::DirectorySeparatorChar)) { throw '非法归档路径' }
                New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
            }
        }
    } finally { $archive.Dispose() }
    $classpath = "$runtime/classes;$runtime/*;$runtime"
    $sources = @('S11Inventory','S07NativeValidation','TaskProgressRollbackValidation','S08NativeValidation','S09NativeValidation','S10NativeValidation','BackendValidation') | ForEach-Object { Join-Path $PSScriptRoot "validation/$_.java" }
    & javac -encoding UTF-8 -cp $classpath -d $runtime @sources
    if ($LASTEXITCODE -ne 0) { throw '专项编译失败' }
    & (Join-Path $PSScriptRoot 'start-api.ps1') -LoadEnvironmentOnly
    $presentationRoot = Join-Path $root 'presentation'
    New-Item -ItemType Directory -Path $presentationRoot -Force | Out-Null
    $javaOptions = @('-Dfile.encoding=UTF-8', '-Dlab.media.worker-enabled=false', '-Dlab.governance.cleanup-enabled=false', '-Dlab.observability.export-enabled=false', "-Dvalidation.application-jar-sha256=$jarHash", "-Dvalidation.evidence-root=$presentationRoot", '-cp', $classpath)

    # 每层独立记录：退出2是缺核心证据，绝不当作成功；失败继续收集不相关层的结果。
    function Invoke-CoreProbe([string]$Name, [string]$Class, [string[]]$ProbeArgs, [string]$ResultFile) {
        $started = [DateTime]::UtcNow
        & java @javaOptions $Class @ProbeArgs *> (Join-Path $root "$Name.log")
        $code = $LASTEXITCODE
        $status = if ($code -eq 0) { 'PASS' } elseif ($code -eq 2) { 'NOT_RUN' } else { 'FAIL' }
        $checks = $null
        if ($ResultFile -and (Test-Path -LiteralPath $ResultFile)) {
            # 拒绝沿用旧轮的JSON；每轮结果复制到当前阶段，原历史记录不作为新增分母。
            if ((Get-Item -LiteralPath $ResultFile).LastWriteTimeUtc -ge $started.AddSeconds(-2)) {
                $result = Get-Content -Raw -Encoding UTF8 -LiteralPath $ResultFile | ConvertFrom-Json
                $checks = if ($result.passed) { $result.passed } else { $result.metadata.passed }
                Copy-Item -LiteralPath $ResultFile -Destination (Join-Path $root "$Name-results.json") -Force
            } elseif ($code -eq 0) { $status = 'FAIL' }
        } elseif ($ResultFile -and $code -eq 0) { $status = 'FAIL' }
        $cases.Add([pscustomobject]@{name=$Name; status=$status; exit_code=$code; checks=$checks; started_at=$started.ToString('o')})
        Write-Host "$Name $status exit=$code checks=$checks"
        return $code
    }

    $state = Join-Path $presentationRoot 'restart-state.json'
    if ($RecoverOnly) {
        if (-not (Test-Path -LiteralPath $state)) { throw '没有待清理的本阶段恢复夹具' }
    } else {
        if (Test-Path -LiteralPath $state) { throw '存在恢复夹具；请用同包 -RecoverOnly，不能覆盖后重跑' }
        $inventoryCode = Invoke-CoreProbe 'inventory-before' 'com.example.ailab.app.S11Inventory' @('before') ''
        if ($inventoryCode -ne 0) { throw '未建立保护快照，停止数据库验收' }
        $null = Invoke-CoreProbe 'isolated-backend' 'BackendValidation' @('--offline') (Join-Path $workspace 'var/backend-review/results.json')
        $null = Invoke-CoreProbe 'fees' 'com.example.ailab.app.S07NativeValidation' @('--database-only') (Join-Path $workspace 'var/stage-S07/database-native-results.json')
        $null = Invoke-CoreProbe 'task-progress' 'TaskProgressRollbackValidation' @() ''
        $null = Invoke-CoreProbe 'governance' 'com.example.ailab.app.S08NativeValidation' @() (Join-Path $workspace 'var/stage-S08/native-results.json')
        $null = Invoke-CoreProbe 'media' 'com.example.ailab.app.S09NativeValidation' @() (Join-Path $workspace 'var/stage-S09/native-results.json')
        $null = Invoke-CoreProbe 'presentation' 'com.example.ailab.app.S10NativeValidation' @() (Join-Path $presentationRoot 'native-results.json')
        $ready = Join-Path $presentationRoot 'crash-ready.json'
        if (Test-Path -LiteralPath $ready) { Remove-Item -LiteralPath $ready }
        # 已知classpath均在本工作区。引用每个参数，后台窗口隐藏，仅保留此次启动句柄。
        $arguments = $javaOptions + @('com.example.ailab.app.S10NativeValidation', 'restart-crash-wait')
        $argumentLine = ($arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
        $child = Start-Process -FilePath (Get-Command java).Source -ArgumentList $argumentLine -PassThru -WindowStyle Hidden -WorkingDirectory $workspace -RedirectStandardOutput (Join-Path $root 'crash-write.log') -RedirectStandardError (Join-Path $root 'crash-write-error.log')
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        while (-not (Test-Path -LiteralPath $ready) -and -not $child.HasExited -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 250 }
        if (-not (Test-Path -LiteralPath $ready)) { throw '合成进程未到达持久检查点；保留日志与可能夹具' }
        $marker = Get-Content -Raw -Encoding UTF8 -LiteralPath $ready | ConvertFrom-Json
        if ($marker.pid -ne $child.Id -or $marker.jarHash -ne $jarHash -or $child.HasExited) { throw '进程身份或构建包不匹配，禁止查杀' }
        Stop-Process -Id $child.Id -Force
        $child.WaitForExit()
        [pscustomobject]@{pid=$child.Id; jar_hash=$jarHash; forced_termination=$true; boundary='持久检查点后，合成SQL租约与素材，未执行正式claim或提供方请求'} | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $presentationRoot 'crash-killed.json')
        $cases.Add([pscustomobject]@{name='os-force-stop'; status='PASS'; exit_code=$child.ExitCode; checks=1})
    }
    # 先只读比较恢复包摘要；不匹配不启动SQL上下文、不删除旧夹具。
    $recoveryState = Get-Content -Raw -Encoding UTF8 -LiteralPath $state | ConvertFrom-Json
    if ($recoveryState.jarHash -ne $jarHash) { throw '恢复必须使用原构建包；旧状态保留' }
    $null = Invoke-CoreProbe 'crash-recovery' 'com.example.ailab.app.S10NativeValidation' @('restart-read') (Join-Path $presentationRoot 'restart-results.json')
    $null = Invoke-CoreProbe 'inventory-after' 'com.example.ailab.app.S11Inventory' @('after') ''
    if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $jarHash) { throw '验收期间构建包变化，所有结果须在稳定同包重验' }
    # 用户明确零新增购买；必需的真实模型、素材和人工质量保留为未运行／待本人验收。
    foreach ($missing in @('real-model-quality-fees','real-generated-and-factual-images','real-complete-video-agent-quality','mysql-backup-restore-es-rebuild')) {
        $cases.Add([pscustomobject]@{name=$missing; status='NOT_RUN'; reason='零新增购买或缺独立恢复资源；须在交接指定补验'})
    }
    foreach ($pending in @('native-powerpoint-human-review','video-teaching-human-review','full-product-ui-review')) {
        $cases.Add([pscustomobject]@{name=$pending; status='PENDING_HUMAN'; reason='未由本人确认，不补造通过'})
    }
} catch {
    # 不输出底层异常正文，避免连接字符串／SQL细节中的敏感数据进入聊天。
    $cases.Add([pscustomobject]@{name='core-runner'; status='FAIL'; reason=$_.Exception.GetType().Name})
    Write-Output 'core-runner FAIL；请核对本阶段日志，未删除恢复状态'
} finally {
    if ($child -and -not $child.HasExited) { Stop-Process -Id $child.Id -Force; $child.WaitForExit() }
    $summary = [pscustomobject]@{stage='S11'; core_status='NOT_ACCEPTED'; scope='ZERO_NEW_PURCHASE'; application_jar_sha256=$jarHash; cases=$cases.ToArray(); generated_at=[DateTimeOffset]::Now.ToString('o')}
    $summary | ConvertTo-Json -Depth 15 | Set-Content -Encoding UTF8 (Join-Path $root 'core-results.json')
    $lock.Dispose()
}
# 全部核心证据不足时返回2；程序失败返回1，只有全部必需项通过才可能返回0。
if ($cases.Where({$_.status -eq 'FAIL'}).Count) { exit 1 }
if ($cases.Where({$_.status -in @('NOT_RUN','PENDING_HUMAN')}).Count) { exit 2 }
exit 0
