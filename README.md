# 多用户个人知识库与 AI 助手后端

依据根目录《AI模型应用开发学习验证项目架构.md》实现；按用户最新要求保留MySQL 8.4、调整ES至8.15基线，使用 Java 17、Spring Boot 3.5.16 和七个 Maven 模块。方法及关键权限、SQL、状态迁移、预算和索引代码带中文注释。

**当前不是 P0～P4 全部完成的交付。** 2026-10-03 已修复审查报告其余 8 项缺陷：来源版本恢复、正常暂停卡队列、模型截断及超时、SSE 异常 EOF、ES 删除／旧代次清理、列表元素校验和 owner 删除规则。最终 48 项自动测试通过、1 项 Docker 测试跳过；43 项主验收及 6 项研究报告／SSE 专项验收全部通过，详见 [缺陷修复记录](docs/backend-bugfix-2026-10-03.md)。MySQL 8.4.11 的 V1～V3、真实 2048 维 Embedding、ES 8.15、DeepSeek RAG、FAQ／研究报告已验证；测试数据与临时索引已清理，配置库保留表结构且无用户。`.env` 和自动 Worker 开关未修改；视频、多轮会话、完整运行图和模型工具循环等核心待办见 [实施状态](docs/implementation-status.md)。

## 七模块与边界

| 模块 | 职责 |
|---|---|
| lab-contract | 框架无关 record、身份、范围、窄端口 |
| lab-business | 账户、统一权限、知识 CRUD、任务及私人资源用例 |
| lab-data | PO／Mapper、MySQL 短事务、Flyway、ES 8.15 RestClient、来源复核 |
| lab-ai | Gateway、编排、模型注册路由／主备、工具、偏好、汇聚 |
| lab-web | Bearer 认证、HTTP DTO、Controller、异常与安全 SSE |
| lab-app | 唯一正式 API／Worker 装配与环境配置 |
| lab-demo | 固定输入的无数据库 Mock CLI |

普通库产普通 JAR；仅 app/demo 重新打包可执行 JAR。ArchUnit 守住依赖方向。后端请求不得提供 userId／owner／role 来覆盖身份。

## 构建和离线验证

需要 Java 17。Maven Wrapper 固定 3.9.11；本机 Wrapper 下载时遇到系统 TLS 问题，已用 Python 下载官方发行 ZIP 并核对官方 SHA-512 后预热本地缓存。普通 Maven 3.9.x 也可用于构建。

```powershell
# 本机可选：让 Wrapper 缓存在项目 var 下，避免改动共享 Maven 配置
$env:MAVEN_USER_HOME = Join-Path (Get-Location) 'var/maven-home'
.\mvnw.cmd verify
# 如当前机器未预热缓存且 Wrapper 下载受 TLS 环境影响：
mvn verify
java -jar lab-demo/target/lab-demo-0.1.0-SNAPSHOT.jar
```

CLI 精确断言 Markdown 代码内标题不建章节，以及 Mock 主超时切备用两次尝试；输出明确带 Mock。不会调用付费模型／TTS。

真实存储集成仅在显式 profile 中启用：

```powershell
.\mvnw.cmd -Pintegration clean verify
```

MySQL Testcontainers 固定 `mysql:8.4.12`。Docker 不可用则 Failsafe 记录 SKIPPED。没有 H2 替代验证。当前执行范围和结果见 [评测报告](docs/evaluation-report.md)。

## 专用环境与启动

Compose 仅启动 MySQL 8.4.12 和 ES 8.15.0。端口只绑定本机；关闭 ES 安全仅用于这份本地学习配置。外部 ES 支持 ES_USERNAME／ES_PASSWORD 成对配置，使用 Basic 认证；按用户要求，application.yml 的 trust-all 默认值为 `${ES_TRUST_ALL:true}`；当前测试 .env 显式设置 true，仅 ES 客户端跳过证书链和主机名校验。设置 ES_TRUST_ALL=false 后恢复验证，可通过 ES_CA_CERTIFICATE 指定可信 CA。凭证不要放入 ES_URL。

1. 复制 `.env.example` 为 `.env` 并填写**本项目专用**连接与凭证。
2. 如使用容器，设置 MYSQL_ROOT_PASSWORD／MYSQL_PASSWORD 后运行 `docker compose up -d`。
3. Java 不会自动读取 Compose 的 .env。可以用下述启动脚本读取允许的环境字段，或者直接设置进程环境变量。
4. 首次空库初始化设置 BOOTSTRAP_ENABLED=true、BOOTSTRAP_USERNAME 和强 BOOTSTRAP_PASSWORD。已有用户时拒绝 bootstrap。首次成功后将 BOOTSTRAP_ENABLED 改为 false。
5. 构建后运行 `powershell -File scripts/start-api.ps1` 或 `java -jar lab-app/target/lab-app-0.1.0-SNAPSHOT.jar`。

DB_URL 例如 `jdbc:mysql://localhost:3306/ailab?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`；DB_USERNAME／DB_PASSWORD 对应该专用库。Flyway 自动创建结构，不删除已有表、不自动导入教学用户。**不要连接未经明确指定的现有业务库**。

SEARCH_ENABLED=false 时资料 CRUD 可独立运行，问答会明确返回 SEARCH_UNAVAILABLE；原文上传保持 RECEIVED，不冒称索引成功。启用搜索后，先完成受控初始化：

```powershell
powershell -File scripts/init-index.ps1
```

上面的初始化脚本只调用显式 app 参数，已存在索引不销毁。索引初始化不会自动调用付费模型。随后 SEARCH_ENABLED=true 启用两个有界入库 Worker。入库模型预算有上限；实际费用还需补价格／金额可靠结算。

## 正式账户与权限流程

- POST /api/v1/auth/login 返回原 token 一次；后续只用 Authorization: Bearer。服务端保存哈希，不接受 URL token。
- 管理员 POST /admin/users 创建 USER，临时密码仅返回一次。用户首次登录只能 me、password、logout；改密撤销所有旧 token。
- ADMIN 默认 SELF。ALL／SELECTED 是显式只读知识范围；修改资料仍仅 owner。
- USER 的混合合法／越权 SELECTED 整体拒绝；空 SELECTED 为零范围。
- 会话尚未实现；偏好、确认、任务、产物及运行摘要均本人隔离，管理员没有私人资源旁路。
- 账户禁用、角色变更或密码变更使登录撤销，permissionVersion 更新。最后有效管理员不能禁用或降级。

## 资料、问答与 AI 笔记

POST /documents 为 multipart，一个 `file` 和 `knowledgeBaseId`，必须 Idempotency-Key。仅 UTF-8 TXT/MD/Markdown，10 MB／100 万码点／5000 小片上限，有限原文保存在 MySQL。文档／版本／入库意图／Outbox／请求去重同事务提交。新内容版本和技术处理 revision 分开。

Markdown 用 CommonMark AST 建章节；父段和小片保留原文 offset。当前计数为 UTF-8 字节的保守估计，上限含标题和重叠，不冒称供应商精确 Token。代码／表格块的完整结构元数据及表头重复等仍有待办。

ES 只索引小片：BM25＋kNN 各 20 候选，Java RRF；两路 scope 预过滤，MySQL 再复核当前用户／内容版本／active revision／所有来源。扩展真实父段，过大时用同章节前后一个邻片，最终最多六包、保守 4000 Token。Citation 返回 matched/included、来源版本和原文 offset。引用支持质量还须真实固定样本评测。

普通问答是单轮。以“统计”开头走真实程序统计；其他问题走授权 RAG。SSE 先缓存全文／汇聚／重新核验，再发送 progress/delta/citation/done；错误不发送草稿。

POST /notes/prepare 只是准备：绑定自有目标、目标库版本、完整文本与最多 32 个扁平来源。GET /approvals/{id} 展示预览。POST /approvals/{id}/decision 只接收 approved。有效批准在**一个 MySQL 事务**中消费确认、保存文档／来源／Outbox／operation；重复决定返回既有事实。修改目标、期限、权限或来源后不可沿用。客户端编辑笔记不能清除来源。

## FAQ 与研究报告

POST /tasks 指定 FAQ 或 RESEARCH_REPORT、topic、scope、1～6 个 documentIds 和 header Idempotency-Key。返回 202/taskId。使用持久 MySQL 队列、180 秒租约／20 秒续租、fencing、成功检查点、持久十次尝试／六轮预算和累计二十分钟期限。

程序 Supervisor 以独立上下文派发 ResearchWorker（economy）和 AnalysisWorker（analysis），最多两个角色并行，再由 ReportWriter（report）汇总。角色池与协调池分离。**目前执行固定受限 DAG；不是已实现模型生成计划或 ReAct 循环。**各 profile 当前可共用目标，强/经济质量差异尚未评测。

输入超出有限证据预算时标为 PARTIAL 并说明未读前缀以外的内容，不能说整篇总结成功。暂停立即撤销本地执行权；正在远程生成的请求可能继续计费，但不能提交新结果。resume 不重置预算、不复用已成功步骤生成。终态不普通 resume。产物下载仅本人＋发布态＋当前全部来源可读。

NOTES_VIDEO 明确返回 MEDIA_CAPABILITY_UNAVAILABLE；真实 TTS、模板合成、预览费用确认、媒体 UNKNOWN 对账和完整视频工作流**尚未实现**。不会返回假 MP4 或假 providerJobId。

## 模型配置

MODEL_MODE=mock 或 real，真实模式不自动回退 Mock。主备验证需要 primary/backup 两个不同实际目标；当前只有一个 DeepSeek 对话测试目标时，设置 MODEL_BACKUP_ENABLED=false，可先验证单模型连接，不把该验证计作主备验收。

real 必填 MODEL_EXTERNAL_DATA_ALLOWED=true、MODEL_BASE_URL、MODEL_PRIMARY_NAME、MODEL_API_KEY。启用备用还需 MODEL_BACKUP_BASE_URL、MODEL_BACKUP_NAME、MODEL_BACKUP_API_KEY。

向量模型需要 EMBEDDING_MODEL_NAME／EMBEDDING_DIMENSIONS。独立供应商可设置 EMBEDDING_BASE_URL、EMBEDDING_CREDENTIAL_REF=EMBEDDING_API_KEY 和 EMBEDDING_API_KEY；不设置时沿用对话服务地址和 MODEL_API_KEY。不要把只支持对话的模型注册为向量模型。尚无向量服务时设置 EMBEDDING_ENABLED=false、INGESTION_WORKER_ENABLED=false、TASK_WORKER_ENABLED=false，先验证数据库／ES／对话；RAG 和文档索引不能据此标为可用。客户端只能请求业务范围，不能指定真实模型、endpoint、密钥或增大预算。

Embedding 模型和维度变化必须使用新 ES_INDEX。相同维度并不代表相同向量空间。注册配置中的 configured-baseline 只代表人工配置门槛，**并不代表质量评测通过**。EXACT 模式、半开单探针、真实价格／可靠费用、SDK tool-call 续轮及更多真实错误分类仍待完成。

## 交付记录

- [实施状态与 37 组能力](docs/implementation-status.md)
- [版本与兼容性](docs/version-validation.md)
- [API 与配置](docs/api.md)
- [前端开发与联调文档（接口、字段、流程与客户端示例）](docs/frontend-development-guide.md)
- [评测、失败与跳过](docs/evaluation-report.md)
- [恢复与故障演示](docs/recovery-and-failures.md)
- [真实测试服务连接检查](docs/connection-validation.md)
- [2026-10-03 后端代码审查与真实功能验收](docs/backend-review-2026-10-03.md)

var、密钥、日志、.env、target 和本地 Maven 仓库均不提交。
