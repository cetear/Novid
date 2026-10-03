# 测试服务连接检查

2026-10-03 最终修复验收通过，当前配置库保留 22 张项目表（含迁移历史）、V1～V3 成功、用户数 0；测试资料与临时 ES 索引已清理。`.env` 和自动 Worker 开关未修改。请以[缺陷修复与验收记录](backend-bugfix-2026-10-03.md)为最新状态。以下保留功能验收前的只读连接检查历史证据。

最新数据库检查日期：2026-10-03（Asia/Shanghai）；ES与模型检查为2026-10-02。用户最新确定MySQL8.4 + ES8.15。连接参数集中在根目录.env，该文件被Git忽略；报告不保存地址、账号、密码、密钥或供应商正文。

## 功能验收前的历史连接结果

| 服务 | 结果 | 已证明／待处理 |
|---|---|---|
| 数据库 | PASS，实际8.4.11 | JDBC连接和项目8.4版本检查通过；Flyway info正常，V1／V2／V3为PENDING，当前库0张表，尚未执行迁移 |
| ES连接／API | PASS，服务8.15.0 | Java与REST客户端改为8.15.5，Basic认证、trust-all及正式info调用通过；旧9.5.4客户端的协议错误已消除 |
| ES生产索引实现 | PASS | ElasticsearchRepository创建本次随机临时索引、写入2条合成文档、refresh并核对全集ID／hash／模型／维度 |
| ES双路查询 | PASS | 官方API执行与生产同形的BM25／kNN过滤DSL，两路均只返回owner17的1条合成文档，排除owner18；本次临时索引已删除 |
| Embedding正式网关 | PASS | 基础URL去除末尾/embeddings，GLM-Embedding-3修正为embedding-3，32维修正为2048维；实际ModelGateway／LangChain4j单次返回1个2048维向量 |
| DeepSeek正式网关 | PASS | 使用模型deepseek-flash，合成对话单次尝试返回文本；已验证供应商usage为43输入／19输出Token |

ES验证使用独立随机命名的临时索引及合成两维向量；只删除本次创建的索引。该验证证明正式索引实现和ES预过滤，不证明MySQL身份／资料版本／来源的最终复核，也不代表完整授权RAG或重启恢复验收。

模型网关探针使用合成输入与有限单目标测试注册配置，证明SDK及真实服务连接，不证明质量、主备或完整业务链路。Embedding排查顺序为401认证错误、重复接口路径404、模型代码错误400，最后修正模型与维度后通过。

## 当前版本与配置

- MySQL目标仍是8.4 LTS（Compose与容器测试固定8.4.12），迁移前检查继续拒绝5.7。
- ES服务基线8.15.0，Java／RestClient均8.15.5；实际app JAR核对两依赖版本一致。Compose同步至8.15.0，镜像本身未拉取验证。
- 向量服务模型代码embedding-3，实际2048维；ES_INDEX已改用该模型／维度对应的新索引名，尚未初始化正式知识索引。
- 按用户指定，application.yml为 `trust-all: ${ES_TRUST_ALL:true}`，本机.env显式true，仅ES客户端跳过证书链及主机名验证。false恢复验证，此时可指定ES_CA_CERTIFICATE，不影响JVM全局或模型客户端。

## 后续真实链路验证

1. MySQL8.4连接已更新并验证通过；后续在当前专用库执行V1～V3迁移。
2. 迁移后验证认证、幂等、确认事务、租约与权限撤销。
3. 再初始化新正式知识索引，验证文档解析／真实向量入库／SQL激活／授权RAG。

INGESTION_WORKER_ENABLED和TASK_WORKER_ENABLED仍暂为false，未自动消费文档或运行后台任务。启动脚本以文本方式加载.env，Java本身不自动读取该文件。

本机诊断探针与脱敏结果位于Git忽略的var目录，不作为正式模块或CI集成测试；离线测试与真实探测结果分开报告。

## 2026-10-03 数据库复检边界

本次仅JDBC只读查询、版本元数据、SHOW GRANTS与Flyway info检查。授权信息包含CREATE／ALTER／SELECT条目，但未执行DDL或写入验证。不创建表、账号、业务资料，不启动后台Worker，也未重复调用模型。原数据库5.7连接问题已解决；完整迁移与授权RAG仍未验收。
