# 验证报告

2026-10-03 最终缺陷修复验收：48 项自动测试通过，1 项 Docker 集成测试跳过；主验收 43/43、研究报告与 SSE 专项 6/6 通过。来源恢复、任务领取、截断／超时、SSE、索引清理、元素校验及 owner 删除已修复，见[修复记录](backend-bugfix-2026-10-03.md)。原 29/37 的失败证据保留在[原审查报告](backend-review-2026-10-03.md)。下文为修复前的历史记录，不代表最新完成状态。

执行日期：2026-10-02（Asia/Shanghai）。

## 实际执行

- Maven Wrapper 3.9.11、Java 17.0.4.1：`-Pintegration verify`（最终源码变更后再执行，以下证据按最终测试更新）。
- 34 个离线测试通过，0 failures，0 errors。
- 1 个 MySQL 8.4.12 Failsafe 集成用例 SKIPPED：无可用 Docker。
- CLI：`java -jar lab-demo/target/lab-demo-0.1.0-SNAPSHOT.jar`，输出 `PASS [Mock] AST sections=3, chunks=2, fallback=backup, attempts=2`。
- 依赖树：app实际Elasticsearch Java/RestClient均8.15.5，Jackson3与重复commons-logging排除。

| 测试类 | 数量 | 结果 | 可证明 |
|---|---:|---|---|
| KnowledgeAccessPolicyTest | 6 | PASS | SELF/SELECTED/ALL、owner、降级、禁用策略 |
| ModelAndStructureTest | 7 | PASS（Mock/本地AST） | 有限故障切换、能力拒绝、重复别名、上下文、Unicode覆盖、关闭备用单目标 |
| PlanValidatorTest | 2 | PASS | 拓扑排序、依赖环/角色白名单 |
| HttpAuthorizationTest | 4 | PASS（Mock账户/业务） | 无认证、URL token、伪owner/ADMIN、临时密码 |
| ModuleBoundaryTest | 3 | PASS | SDK/PO/Mapper不越界、模块方向 |
| AssemblyTest | 2 | PASS（Mock存储/模型） | 实际Spring装配无循环、YAML路由实际到达已注册chat/embedding目标 |
| TransportSerializationTest | 2 | PASS（本地SDK） | ES8.15.5/Jackson2 mapper和kNN请求结构 |
| ElasticsearchAuthenticationTest | 4 | PASS（本机HTTP端点） | 8.15 RestClient真实Basic头/无认证、配置拒绝、诊断脱敏；不证明真实ES可用 |
| DatabaseVersionGuardTest | 2 | PASS（Mock元数据） | 5.7不迁移、8.4可进入Flyway；不证明真实迁移 |
| ElasticsearchTlsTest | 2 | PASS（本机HTTPS） | 自签且主机名不匹配：trust-all=true连接成功、false拒绝 |
| MySqlIntegrationIT | 1 | SKIPPED | 没有证明任何真实MySQL事务 |

Mock数据库／Mock账户测试不证明迁移、SQL、事务或用户真实存储；本地SDK序列化和本机HTTP认证测试不证明ES可用性；Mock回答不证明真实模型效果。跳过没有计入通过。

## 真实测试服务探测

用户明确采用MySQL8.4 + ES8.15。ES8.15.5客户端对实际8.15.0服务的info调用通过；生产ElasticsearchRepository创建临时索引、写入2条合成文档并完成全集验证，直接官方API的BM25和kNN分别只返回当前owner的1条文档，临时索引已清理。此验证证明ES预过滤，不证明MySQL最终授权、来源撤销或完整RAG。

DeepSeek正式网关单次调用通过，usage为43输入／19输出Token；修正Embedding基础地址、模型代码embedding-3及2048维后，正式网关单次返回1个2048维向量。MySQL8.4迁移／正式API／Worker完整链路没有通过，详情见[连接检查](connection-validation.md)。

## 未运行

- MySQL真实迁移、权限矩阵、并发确认仅写一次、Worker重启/fencing、请求去重的真实唯一键断言。
- ES真实双路召回、部分bulk、版本/hash全集完整性、refresh可见性、旧代次与撤销来源。
- 真实多模型路由/主备质量、工具协议、供应商Token/失败费用、Recall@k/MRR/引用支持。
- 核心TTS/FFmpeg/ffprobe/中文字体、实际MP4与人工抽查、多角色派发时间线。
- 外部OTLP/Langfuse、MCP/训练/本地推理等扩展协议。

## 测试证据路径

每模块 target/surefire-reports，app target/failsafe-reports；目标目录不提交。本文保存执行结论，复现后应记录实际服务版本和运行结果。程序精确断言与模型效果评测分开报告，不用模型裁判证明数据库原子性。

## 2026-10-03数据库连接复检

实际MySQL8.4.11，JDBC连接、版本元数据和Flyway info通过；当前库0张表，V1～V3均PENDING。检查未执行迁移、账号初始化、业务写入或Worker，不作为真实事务／完整授权RAG通过证据。此连接检查不计入既有34项离线测试数量。
