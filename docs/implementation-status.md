# 实施状态

2026-10-03 修复更新：[缺陷修复与最终验收](backend-bugfix-2026-10-03.md)为最新证据。原审查其余 8 项缺陷已修复；48 项自动测试、43 项主验收和 6 项专项验收通过，1 项 Docker 集成跳过。SSE 正常传输、报告历史来源／覆盖、正常暂停与异常恢复、版本化索引清理已验证；阶段缺项仍未整体完成。

依据：根目录架构文档（2026-10-02），学习指南已阅读。用户本次要求后端，未生成轻量前端；其余核心缺项不会借“后端范围”省略报告。

状态含义：IMPLEMENTED 仅代表列出的具体代码行为；VERIFIED 必须有明确测试层；SKIPPED 是未接入的扩展；TODO 是未完成的核心内容。**没有任何阶段被声明全部完成。**

## 阶段状态

| 阶段 | 状态 | 事实／退出条件差距 |
|---|---|---|
| P0 | IMPLEMENTED | 七模块/Wrapper/依赖解析/装配；真实MySQL8.4.11迁移和ES8.15基本兼容性已验证，镜像digest未验证 |
| P1 | IMPLEMENTED | 正式认证/用户/知识CRUD/策略/Mock模型；未完成全部工具循环与完整三身份真实矩阵 |
| P2 | IMPLEMENTED | 原文/结构/批次/ES双路/父段扩展/主备适配；真实入库与引用RAG正常路径通过；删除／旧代次清理通过；完整批次故障、会话、真实双目标主备未完成 |
| P3 | TODO | SSE/本人运行摘要已有；完整span图/费用/缓存/审计查询未完成 |
| P4 | IMPLEMENTED | 笔记确认/私人记忆/FAQ与报告状态机及两个角色已有；会话/动态计划/完整覆盖/核心视频TODO |
| P5/P6 | SKIPPED | 明确扩展，未接服务或额外协议，不能计核心通过 |

## 已验证的具体范围

- VERIFIED：Java 17 编译、七模块 app/demo 可执行打包、正式装配（Mock存储）。
- VERIFIED：34 项离线测试：统一权限 6、模型/结构 7、DAG 2、HTTP身份拦截 4、模块边界 3、装配与路由 2、ES/Jackson本地序列化 2、ES认证传输与脱敏 4、数据库迁移前版本保护 2、实际HTTPS校验开关 2。
- VERIFIED：固定 CLI Mock 结构与故障切换。
- SKIPPED：1 项真实 MySQL Failsafe 用例，Docker 不可用。
- VERIFIED（具体正常路径）：用户配置集中在.env；实际MySQL8.4.11的V1～V3、ES8.15临时索引、2048维Embedding入库与DeepSeek引用RAG、并发确认和来源撤销、个人偏好、FAQ与研究报告均已运行。测试数据已清理，保留表结构且无测试账户。已修复的恢复／SSE故障及尚未覆盖的矩阵见backend-bugfix-2026-10-03.md；不代表这些能力整组完成。

## 37 组能力保留清单

| 能力 | 状态 | 实现／缺项（不是整组 PASS） |
|---|---|---|
| basics | IMPLEMENTED | 七模块、record、配置、Validation、异常；固定 CLI 已验证 |
| knowledge_access | IMPLEMENTED | 统一策略/身份/owner/来源；6 项策略与 4 项 HTTP 离线断言，真实权限矩阵待验证 |
| ai_gateway | IMPLEMENTED | 授权、固定单轮 RAG、有限执行；会话路由与按人 AI 限流 TODO |
| model_api | IMPLEMENTED | LC4j chat/embedding 适配；真实调用/用量/拒绝/截断评测未运行 |
| selection | TODO | configured-baseline 不是质量评测，经济/分析差异待固定样本验证 |
| model_routing | IMPLEMENTED | 服务端任务→profile，有能力/质量/PRIVATE筛选；EXACT/路由原因持久化 TODO |
| model_failover | IMPLEMENTED | Mock 超时/不兼容备用/重复别名/小窗口测试；半开单探针/真实429和费用 TODO |
| hybrid_orchestration | TODO | 已实现简单固定流程与报告固定 DAG；模型 Planner/ReAct 未实现 |
| prompt_context | TODO | 有有限证据与偏好；提示词版本对比/历史窗口/摘要 TODO |
| structured | TODO | DTO/参数校验存在；真实模型 Schema 提取/修复循环未实现 |
| ingest | IMPLEMENTED | AST/章节/父段/Unicode小片/真实参数hash；表格重复表头/block元数据/完整sourceMap TODO |
| index_sync | IMPLEMENTED | MySQL原文/入库意图/Outbox，批次ID/hash/可见性后CAS；有界版本化删除／旧代次清理已验证；入库逐批恢复计费 TODO |
| search | IMPLEMENTED | 真实ES双路预过滤/RRF/SQL复核/父段与邻片；Basic认证与脱敏本地验证，实际8.15索引／全集验证／双路owner过滤已通过；故障/去重覆盖评测未运行 |
| rag_answer | IMPLEMENTED | 有限证据/实际引用范围/生成前后复核；整章分页覆盖/备用重装包/效果评测 TODO |
| aggregation | IMPLEMENTED | 拒绝非法引用/假保存/SSE先全文校验；敏感输出规则/支持性固定评测/有限修复 TODO |
| tool_registry | IMPLEMENTED | 固定白名单/开关/预算；完整Schema注册验证/动态暴露/GET tools TODO |
| tools | TODO | 程序调用受控搜索/统计；SDK toolCallId续轮与ReAct循环未实现 |
| write_approval | IMPLEMENTED | 本人具体预览/期限/目标版本/来源/原子operation与Outbox；真实并发事务待验证 |
| routing_skill | TODO | 统计/知识问答规则已有；多意图/OOD/版本化FAQ Skill未完成 |
| orchestration | TODO | 固定 DAG 与白名单拓扑验证；模型生成/有限修复/策略对照未完成 |
| memory | IMPLEMENTED | 本人显式保存/更正/删除偏好并进入有限上下文；完整会话/摘要未实现 |
| memory_store | TODO | 仅MySQL偏好；会话事件/关系边/记忆向量未实现 |
| durable_task | IMPLEMENTED | SQL状态/租约/fencing/检查点/尝试与轮数/累计期限/暂停取消；真实重启实验待验证 |
| multi_agent | IMPLEMENTED | ReportTaskWorker独立研究/分析两个角色池和汇合；经济强模型评测/完整派发时间线 TODO |
| notes_video | TODO | TTS/画面/字幕/FFmpeg/媒体操作/费用确认/UNKNOWN/真实产物未实现 |
| eval | IMPLEMENTED | 精确程序单测与本地报告；真实Recall/MRR/引用支持/裁判校准 TODO |
| security | IMPLEMENTED | 正式认证/来源/隔离/输入限额/假身份拦截；全部攻击矩阵/外部数据策略评测待验证 |
| trace_visualization | TODO | 仅ai_runs本人脱敏摘要；ai_spans/树/时间线/流程图/OTLP未实现 |
| ops | TODO | 有界执行/失败/任务持久预算已有；完整费用/缓存键与失效/故障观测未完成 |
| frameworks | SKIPPED | P5/P6扩展，未实现AI Services/Agentic/LangGraph4j对比 |
| mcp | SKIPPED | P6真实协议未接入，未用Java函数冒充 |
| documents | SKIPPED | P6 PDF/Office/OCR未启用，TXT/MD以外拒绝上传 |
| multimodal | SKIPPED | P6 vision/ASR扩展未启用；核心TTS视频仍为TODO |
| finetune | SKIPPED | P6未配置训练服务/数据/硬件 |
| local_inference | SKIPPED | P6未配置本地推理能力 |
| model_theory | SKIPPED | P6 tokenizer/注意力原理实验未实现 |
| delivery | IMPLEMENTED | README/API/版本/评测/恢复/状态/Wrapper/Compose；完整核心交付未达到 |

## 必須补齐后才可称完整核心后端

1. 真实数据库事务、并发幂等、权限撤销、租约迟到和完整ES故障矩阵，真实模型两个实际目标与固定质量数据。
2. 多轮会话 owner/CAS/来源摘要，角色任务上下文独立恢复。
3. JSON Schema 工具注册、SDK续轮保留 toolCallId、受限Planner/Execute/ReAct与有限修复。
4. 完整章节分页覆盖和 sourceMap/block元数据，实际 tokenizer 计数，入库逐批持久预算与失败项恢复、历史结构保留策略及迟到索引写入竞态。
5. 可重建的实际 ai_spans/时间线/运行图，可靠Token/费用预留结算、UNKNOWN费用、管理审计查询。
6. 核心 NOTES_VIDEO 八节点、讲解/分镜并行、真实TTS/模板/字幕/FFmpeg、预览确认/媒体子操作UNKNOWN与对账、私人下载及人工抽查。
7. 系统级恢复演习、版本化配置备份、完整安装复现和核心验收包。

## 设计实现取舍

- TXT/Markdown有限原文直接落MySQL，是文档明确允许的首版路径；不存在文件系统与DB伪事务。
- data 使用MyBatis-Plus读取账户PO；并发／去重／确认／任务以显式JdbcTemplate SQL落实。SQL和Mapper均不出data，未引入第二普通MyBatis Starter。
- 单应用写事务先锁 system_control，再用户/目标；教学规模以串行短写事务确保权限撤销与提交一致。远程调用永不持锁；后续优化需统一多资源锁顺序和并发验收。
- 程序固定Supervisor是实际实现；不把固定拓扑声称为模型生成计划。
- 未实现功能未用空类、假音频、假产物或假成功 API 替代。
