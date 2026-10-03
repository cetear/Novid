# 恢复边界与故障复现

2026-10-03 修复更新：[缺陷修复记录](backend-bugfix-2026-10-03.md)验证正常暂停不会耗尽异常恢复次数、异常恢复有界并 fencing、超限队列有终态、报告保留历史版本及 partial、索引清理只删除固定旧边界且迟到执行者不能提交 DONE。原故障证据保留于审查报告。跨进程 kill、真实 ES 故障和迟到写入、备份恢复仍未完成，不能把上述断言称为完整可靠恢复。

## 权威数据与备份

MySQL 保存账号、登录哈希、所有权、文档历史原文、结构批次、来源、确认、operation、任务检查点与私人文本产物。首版有限 TXT/Markdown 原文和报告保存在 MySQL；未使用无约束磁盘上传地址。备份至少覆盖本项目 MySQL 和版本化配置。后续媒体文件须与 MySQL 引用及 checksum 同步备份，目前未交付。

ES 是可重建的小片索引，不能替代 MySQL 备份。恢复后先复核用户／owner／来源及版本，再初始化新索引，逐文档由 owner 触发 reprocess。只有索引全部 ID/hash/维度/modelVersion 与搜索可见性核验完成才 CAS 激活。**没有实测恢复时间、RPO/RTO 或镜像 digest，不能称备份恢复已经可靠验收。**

## 可执行离线故障

| 故障 | 入口与精确断言 |
|---|---|
| USER ALL／混合 SELECTED 越权 | KnowledgeAccessPolicyTest，整体拒绝 |
| ADMIN 写他人资料 | 同测试，读允许而 owner 写拒绝 |
| 旧权限版本／库禁用 | 同测试，历史范围不保留授权 |
| 请求体伪造 ADMIN/owner | HttpAuthorizationTest，未知字段 400 且业务未调用 |
| URL token／未登录 | 同测试，401 |
| 临时密码进行知识操作 | 同测试，403 |
| Mock 主模型超时 | ModelAndStructureTest/CLI，备用接管，两次尝试 |
| 备用能力不足／重复真实别名 | 同测试，备用不调用／启动拒绝 |
| Unicode 长文／短尾／重复标题／代码井号 | 同测试，精确原文范围覆盖与上限 |
| 假保存／假引用 | 汇聚测试，拒绝交付 |
| DAG 环／未知角色 | PlanValidatorTest，拒绝执行 |

## 必须真实环境完成的故障

重复／并发笔记确认、真实 MySQL 事务和权限撤销竞态、Worker kill 后 fencing 和检查点恢复、ES 停机／部分 bulk／延迟可见／旧索引候选、真实主备超时与提供方 429、历史来源撤销与报告下载等仍未验证。

- 按 README 创建专用 MySQL 8.4.12/ES 环境，启用 integration 后先运行存储闭环。
- 同一 Idempotency-Key 同参只一个资源；改参冲突。确认后查 documents/operations/outbox/approvals 的真实事实，不用模型裁判。
- 任务 pause/cancel 立即更新 fencing；迟到 Worker 不能 checkpoint 或 publish。已远程提交的调用仍可能继续计费，本地取消不表示退款。
- 任务恢复跳过 task_steps 已成功结果，持久 modelAttempts/modelTurns 不清零。失效租约至多三次领取，累计二十分钟超限明确 FAILED。
- 入库只激活最新当前内容处理意图；MySQL 先删除/禁用即阻止知识读取，即使 ES 清理事件尚未消费。

当前删除 Outbox 的主动 ES 清理消费者、过期登录/去重/历史批次清理、访问审计查询 API、来源 RESTRICTED 状态标注、完整覆盖分页／媒体恢复仍 TODO。权限检查能够拒绝失效来源，但不能把这一点称作所有清理和状态管理已完成。

## 视频故障

配音后重启、响应丢失进入 UNKNOWN、分镜／脚本冲突、取消／对账四组是核心视频验收要求，目前全部 TODO。没有 TTS／媒体子操作代码、MP4 或人工抽查报告，不能运行假音频来填通过。
