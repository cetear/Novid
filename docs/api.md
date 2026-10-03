# 后端 API 与配置

前端开发请参阅 [前端开发与联调文档](frontend-development-guide.md)：按当前源码提供完整接口、返回类型、权限／状态处理、SSE、认证下载与页面联调清单。本文保留后端 API 和运行配置概览。

统一 /api/v1、JSON camelCase、Authorization: Bearer。除登录和不含详情的存活 health 外均要求认证。未知 JSON 字段拒绝，包括伪造的 userId/role/ownerUserId。POST /admin/users 不接受角色。

## 当前实现的接口

| 方法／地址 | 请求要点 |
|---|---|
| POST /auth/login | username/password；五分钟同账号最多五次 |
| POST /auth/logout | 当前 header token；撤销 |
| GET /auth/me | 当前公开账户 |
| POST /auth/password | oldPassword/newPassword；撤销全部登录 |
| POST/GET /admin/users | username／page,size；创建返回一次临时密码 |
| PATCH /admin/users/{id} | enabled/role；最后 ADMIN 保护 |
| POST /knowledge-bases | name/description；owner 固定请求者 |
| GET /knowledge-bases | scopeMode，knowledgeBaseIds，ownerUserId，page,size |
| GET/PATCH/DELETE /knowledge-bases/{id} | PATCH version/name/description/enabled；DELETE version |
| POST /documents | multipart file/knowledgeBaseId；Idempotency-Key；202 |
| GET /documents | 授权 scopeMode/ids/owner 与分页 |
| GET /documents/{id} | 当前原文＋服务器来源 |
| GET /documents/{id}/source | 认证文本下载 |
| GET /documents/{id}/sections | 当前 active revision，分页 |
| GET /documents/{id}/chunks | 当前 active revision，分页 |
| POST /documents/{id}/index-actions | query action=retry/reprocess；自有目标 |
| PATCH /documents/{id} | documentVersion/title/text；保留来源 |
| DELETE /documents/{id} | query documentVersion |
| GET /knowledge/statistics | scopeMode/knowledgeBaseIds；SQL 数值 |
| POST /chat | question/scope；单轮固定 RAG |
| POST /chat/stream | 同 chat；已校验全文的 SSE |
| POST /notes/prepare | knowledgeBaseId/title/content/sourceDependencies |
| GET /approvals/{id} | 本人完整预览＋当前来源复核 |
| POST /approvals/{id}/decision | 只接受 approved |
| GET/POST /memories | 本人列表／content |
| PATCH/DELETE /memories/{id} | version/content／version |
| POST /tasks | taskType/topic/scope/documentIds；header Idempotency-Key |
| GET /tasks/{id} | 本人状态、持久 attempts、完成步数 |
| POST /tasks/{id}/actions | action=pause/resume/cancel |
| GET /artifacts/{id} | 已发布且本人、来源可读 |
| GET /runs、GET /runs/{id} | 本人脱敏运行摘要，尚无 span 图 |
| GET /actuator/health | 不显示详细依赖或密钥 |

## 请求样例

```json
{"question":"RRF 有什么作用？","scope":{"mode":"SELECTED","knowledgeBaseIds":[1],"ownerUserId":null}}
```

普通用户请求 ALL 直接拒绝。SELF 是默认；空 SELECTED 为零范围。page 从 0 开始，最大 10000；size 1～100。混合合法／越权库整体拒绝，不截掉非法项。

```json
{"taskType":"FAQ","topic":"RAG 的常见问题","scope":{"mode":"SELF","knowledgeBaseIds":[],"ownerUserId":null},"documentIds":[1,2]}
```

任务最多六份资料，topic 最多 1000 字符。当前 FAQ/研究报告仅覆盖有限模型输入，超额明确 PARTIAL。NOTES_VIDEO 返回 MEDIA_CAPABILITY_UNAVAILABLE，不能用它请求公开视频。

## 错误与去重

响应 code/message/retryable；不含异常栈、SQL、密钥。认证 401、权限 403、并发/版本冲突 409、确认过期 410、限流 429、模型超时 504、不可用 503。资源无权或不存在统一按拒绝处理，不泄露他人 ID 是否存在。

去重键 1～128 个 A-Za-z0-9_.:- 字符；作用域为请求者＋API 命名空间。相同键同参数返回既有资源，不同参数 OPERATION_CONFLICT。记录至少保留七天（当前尚未自动清理）；不得承诺无限期 exactly-once。AI 笔记另有稳定 operationId 唯一约束，确认消费和保存同事务。

## 唯一 RAG 参数表

实现位置：lab-ai 的 RagProperties 与 app application.yml。500/50/100/64/2000/4000、candidate/final=6、neighborWindow=1 对应架构第 8.3 节。计数为 UTF-8 字节保守估计，供应商 tokenizer 精确校准尚待真实联调。处理 configHash 保存实际参数快照 hash。原文 offset 是 Java UTF-16 索引，切断处保证 Unicode 码点完整；行号／blockType/sourceMap 扩展仍 TODO。

ES 索引名、维度、embedding 模型版本为 server config；向量模型变化必须新索引，不允许混向量空间。运行参数可减少上限，不开放任意增大或模型生成 SQL。

## 配置与实际默认

在线 60 秒、单次模型至多 30 秒、六轮／十次模型尝试／八工具调用；逻辑 chat 最多三次尝试、最多两个候选目标。SDK 重试为 0。上下文不足安全失败，不把超长提示送较小备用；自动按备用重装包尚 TODO。

后台 report-v1：二十分钟累计执行、三次租约恢复、十次尝试／六轮持久预算、两个角色工作线程；角色独立上下文。持久计数在提交前消费，进程崩溃窗口也不重置。

入库：32 项／16000 保守 Token 单批，5000 小片，有限 160 次尝试与十分钟单次执行，最多三次领取；额度尚未逐批持久结算，不宣称预算恢复完整。失败重处理生成新 revision，不改原文版本。

密码 BCrypt cost=12，12～64 字符且不超过 72 UTF-8 字节。随机 token 256 位、八小时、哈希持久化。临时密码须首次修改。数据库 UTC。

索引初始化 `--lab.command=init-index` 只受控创建，已有索引保留；应禁用 Worker 与 bootstrap 并以非 Web 方式运行，scripts/init-index.ps1 已传这些参数。

外部 ES 认证：ES_USERNAME／ES_PASSWORD 必须成对配置，ES_URL 只写服务地址，不嵌入凭证。客户端使用 Basic 认证，ES_TRUST_ALL=true 时仅 ES 客户端跳过证书链及主机名校验；设为 false 恢复验证，此时自签 CA 可通过 ES_CA_CERTIFICATE 指定证书文件。按用户要求 YAML 默认 true，.env.example 显式 false。

单一对话测试模型可设置 MODEL_BACKUP_ENABLED=false；不计作主备验收。向量模型可使用独立的 EMBEDDING_BASE_URL、EMBEDDING_CREDENTIAL_REF=EMBEDDING_API_KEY 和 EMBEDDING_API_KEY。若暂缺向量模型，设置 EMBEDDING_ENABLED=false、INGESTION_WORKER_ENABLED=false、TASK_WORKER_ENABLED=false；不能把聊天模型冒充 embedding。

当前服务版本基线按用户要求为MySQL8.4 + ES8.15，Java／REST客户端固定8.15.5。真实Embedding当前为embedding-3、2048维；EMBEDDING_BASE_URL只配置基础URL，不能包含末尾/embeddings。模型／维度更换使用新ES_INDEX。
