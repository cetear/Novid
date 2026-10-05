# 数据库访问规范与本次回归

## 长期技术标准

生产数据库访问按以下顺序选择：

1. MyBatis-Plus：单表 CRUD、计数和简单条件更新。
2. MyBatis：联表、动态权限过滤、数据库函数、显式行锁、批量写入和条件更新等定制 SQL。
3. 原生 JDBC：只有前两者无法满足需求时使用，并在代码附近说明具体原因。

数据库实现放在 `lab-data` 内。业务与 Web 模块依赖契约端口，不接触 Mapper、PO、SQL 或 Wrapper。SQL 参数使用 MyBatis 绑定；动态列名使用固定分支，禁止拼接外部输入。

MyBatis-Plus Mapper 依赖通过 Spring 构造器注入，确保 MapperFactoryBean 完成注册后再使用。不要在仓库构造期间直接从 SqlSessionTemplate 获取尚未注册的 Mapper。

## 已完成的迁移

账户、令牌和知识库的常规操作使用 MyBatis-Plus `BaseMapper` 与 Wrapper。其余仓库中的业务 JDBC SQL 已迁入按仓库组织的 Mapper 接口和 XML，保留原事务注解、行锁顺序、版本条件、执行代次、受影响行数检查及数据库时间函数。

复杂投影使用内部 `SqlRow` 和 MyBatis `SqlRowTypeHandler`，保留 NULL、列顺序及驱动的时间转换行为。该类型处理器由 MyBatis 调用，不独立执行数据库 SQL。MyBatis 一级缓存设为 `STATEMENT`，事务中重复读取锁定状态仍访问数据库。

启动版本检查保留 JDBC 元数据接口，用于建立 Mapper 之前确认 MySQL 版本；模式迁移继续交给 Flyway。验收脚本中的直接 SQL 用于准备、核对和清理合成测试夹具，已移至 `scripts/validation/ValidationSql.java`，生产 `SqlSupport` 不再暴露 JDBC。

## 同步修复

- 网络总期限覆盖完整响应正文；超时或中断取消未完成请求，图片搜索在接收期间限制响应大小。
- 视频处理中已返回用量并结算时，后续成功／失败结果可补录终态；不重复结算，冲突用量仍拒绝。
- 媒体编辑拒绝空集合元素、缺失 ID／版式／配图方式；视频选择拒绝缺失必填能力字段。HTTP 无效参数返回 400。
- PPT 双栏按 Unicode 码点处理短文本和空白边界，无法形成两个非空栏时保留完整正文。
- PPT 检查先校验任务归属和类型，尚无预览或导出结果返回 204；归属错误仍返回 403。

## 验证结果

2026-10-05 全模块测试：335 项，330 项通过，5 项跳过，0 失败、0 错误；全模块打包成功。

新增 `MapperRegistrationTest` 使用真实 Mapper 扫描和 MyBatis-Plus 自动配置，无需连接数据库。该测试复现并防止令牌 Mapper 在注册前被仓库获取的启动错误；账户仓库已改用构造器注入 `AuthTokenMapper`。重新打包后通过 `LabApplication` 实际启动，健康接口返回 200；启动验证关闭后台任务和自动初始化。

覆盖 MyBatis XML 解析与动态分支、主键映射、网络正文超时和取消、视频状态与用量衔接、媒体 HTTP 参数校验、PPT 文件重开及双栏边界、无结果响应、Spring 应用装配。16 个原生验收入口已编译通过，未执行其中会写数据库或调用付费服务的流程。

静态核对了 308 处位置参数列表，没有参数数量差异；307 条固定 SQL 与迁移前一致。真实 MySQL 8.4 驱动只读测试查询常量，确认时间精度、NULL 和列顺序，未读写业务数据。

常规回归命令：

```powershell
mvn.cmd -o test
```

额外启用真实驱动只读验证（连接配置来自本地 `.env`）：

```powershell
mvn.cmd -o '-Dnovid.test.mysql.readonly=true' test
```

当前数据库账号没有创建独立测试库的权限。`MySqlMapperIntegrationTest` 的 5 项测试需有临时库创建、迁移和删除权限的 MySQL 8.4 测试连接；尚未验证实际写入、事务回滚和并发竞争，不能用单元测试结果代替这些结论。启用时只操作随机命名的 `novid_mapper_test_*` 临时库：

```powershell
mvn.cmd -o -pl lab-data -am '-Dnovid.test.mysql=true' '-Dtest=MySqlMapperIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```
