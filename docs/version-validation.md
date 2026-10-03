# 版本与兼容性验证

2026-10-03 后续更新：修复generated字段引用后，实际MySQL8.4.11的V1～V3迁移、正式SQL事务与HTTP、ES8.15.0／2048维Embedding入库、DeepSeek RAG及报告任务正常路径通过。详细范围与失败见[后端审查与验收](backend-review-2026-10-03.md)。下文未验证项为此前版本检查的历史记录；容器镜像和8.4.12补丁仍未实测。

日期：2026-10-02（Asia/Shanghai）。按用户最新要求采用MySQL8.4 + ES8.15；Java／Boot大版本、七模块和权威数据库职责保持原架构。

| 项目 | 已解析／使用 | 当前证据 |
|---|---|---|
| Java | Oracle JDK 17.0.4.1 | 编译 release=17，CLI 与测试执行 |
| Maven | 系统 3.9.4；Wrapper 3.9.11 | Wrapper 版本输出；官方发行 ZIP SHA-512 核对 |
| Spring Boot | 3.5.16 | 实际启动完整装配测试和 HTTP slice |
| LangChain4j BOM／OpenAI/Core | 1.20.2 | Maven 解析、编译、正式路由组件 Mock CLI |
| LC4j reactive-streaming | 1.20.2-beta30 | BOM 解析；未把所有模块强锁稳定版本 |
| MyBatis-Plus Boot3 | 3.5.17 | 编译、显式 Mapper 扫描；真实 SQL 尚未运行 |
| Connector/J | 9.7.0（Boot 管理） | 依赖解析；这不是服务器版本，目标仍 MySQL 8.4.12 |
| Flyway Core/MySQL | 11.7.2 | 成套依赖解析；迁移尚待真实 MySQL 验证 |
| Elasticsearch Java／RestClient | 8.15.5／8.15.5 | 实际app JAR依赖与8.15.0服务连接、索引、双路过滤验证 |
| Jackson 2 | 2.21.4（annotations 2.21） | Boot 管理；JacksonJsonpMapper 明确选用；排除 Jackson 3 |
| springdoc | 2.9.1 | Web/OpenAPI 装配 |
| CommonMark | 0.24.0 | AST、重复标题、围栏代码、中文/emoji覆盖测试 |
| ArchUnit | 1.4.1 | 三组模块边界测试 |
| Testcontainers | 1.21.4 | integration/Failsafe；Docker 缺失为 SKIPPED |
| MySQL 镜像 | mysql:8.4.12 | Compose 与集成固定一致；未拉取，digest 未验证 |
| ES 镜像 | 8.15.0 | Compose与用户服务小版本一致；容器镜像未拉取，digest未验证 |
| FFmpeg/TTS/字体 | 未配置／未实现视频路径 | TODO；未用空产物替代 |

## 实际兼容问题与修正

1. **ES版本基线调整**：曾使用9.5.4 Java／Rest5客户端，对实际8.15.0服务返回400/media_type_header_exception。用户明确选择MySQL8.4 + ES8.15后，父级统一管理Java Client与org.elasticsearch.client RestClient均8.15.5，适配官方HTTP4 transport。实际app JAR确认两依赖均8.15.5；真实info、生产索引写入／全集验证、BM25／kNN过滤与临时索引清理通过。此前Boot传递版本覆盖问题通过父级dependencyManagement继续避免。
2. **Windows Wrapper**：官方 only-script 3.3.4 的 Windows PowerShell 下载遭遇本机 TLS 失败，PowerShell 7 还遇到目录 Target 为 null 的索引兼容问题。保留 Apache 许可，优先 pwsh/显式 -Command、为空目录 Target 做防御检查。本机官方 ZIP 通过 Python 下载并核对官方 SHA-512，缓存后成功运行 Wrapper 3.9.11。新机器仍需可用系统证书／下载网络；提供系统 Maven 3.9.x 构建路径，不关闭 TLS 证书校验。
3. **默认 Security 用户**：正式应用禁用 UserDetailsServiceAutoConfiguration，避免生成默认开发密码；认证唯一入口为本项目随机 token 和 BCrypt。
4. **集成 profile**：真实 MySQL 用 Failsafe 与显式 -Pintegration；常规 verify 不要求容器或付费模型。当前没有可用 Docker，不把跳过写作通过。

版本来源与核验对象：[Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[LangChain4j](https://docs.langchain4j.dev/get-started/)、[ES transport](https://www.elastic.co/docs/reference/elasticsearch/clients/java/transport)、[CommonMark](https://github.com/commonmark/commonmark-java)。实际组合以依赖树与测试为证；镜像 digest／真实用量／数据服务版本待补。

## ES 用户名和密码认证适配

采用官方8.15 RestClient的默认Authorization Basic头机制（UTF-8），无凭证时不发认证头，地址内凭证和不完整配置启动前拒绝，配置字符串脱敏。用户指定ES_TRUST_ALL控制局部TLS：true跳过证书链和主机名，false保留校验并可指定ES_CA_CERTIFICATE。本机HTTP／HTTPS测试及真实ES8.15服务验证通过。官方说明：[Basic authentication](https://www.elastic.co/docs/reference/elasticsearch/clients/java/transport/rest-client/config/basic_authentication)。完整结果见connection-validation.md。

对话模型通过用户测试服务的 `/models` 列表核对并修正为 `deepseek-flash`；HTTP与项目LangChain4j网关均实际连接成功。模型列表与Chat协议参考[DeepSeek官方模型列表](https://api-docs.deepseek.com/api/list-models/)及[官方Chat API](https://api-docs.deepseek.com/api/create-chat-completion/)。此证据只证明单目标连接，不证明主备或RAG效果。

Embedding按供应商官方OpenAPI将模型代码修正为embedding-3；该模型支持256／512／1024／2048维，当前实际使用2048维。正式ModelGateway／LangChain4j单次调用已返回1个2048维向量。配置基础URL不包含末尾/embeddings，避免SDK重复追加路径；ES_INDEX更换到该模型与维度的新索引名。来源：[供应商官方OpenAPI](https://docs.bigmodel.cn/openapi/openapi.json)。

2026-10-03读取用户更新后的连接：实际服务MySQL8.4.11，JDBC／8.4版本门禁／Flyway info通过。当前库0张表、V1～V3均PENDING；Compose／Testcontainers仍固定8.4.12，实际服务补丁差异已记录。本次没有执行迁移或写入事务。
