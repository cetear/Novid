# 数据库访问规范

## 长期技术标准

生产数据库访问按以下顺序选择：

1. MyBatis-Plus：单表 CRUD、计数和简单条件更新。
2. MyBatis：联表、动态权限过滤、数据库函数、显式行锁、批量写入和条件更新等定制 SQL。
3. 原生 JDBC：只有前两者无法满足需求时使用，并在代码附近说明具体原因。

数据库实现放在 `lab-data` 内。业务与 Web 模块依赖契约端口，不接触 Mapper、PO、SQL 或 Wrapper。SQL 参数使用 MyBatis 绑定；动态列名使用固定分支，禁止拼接外部输入。

MyBatis-Plus Mapper 依赖通过 Spring 构造器注入，确保 MapperFactoryBean 完成注册后再使用。不要在仓库构造期间直接从 SqlSessionTemplate 获取尚未注册的 Mapper。

## 现有实现

账户、令牌和知识库的常规操作使用 MyBatis-Plus `BaseMapper` 与 Wrapper。其余仓库中的业务 JDBC SQL 已迁入按仓库组织的 Mapper 接口和 XML，保留原事务注解、行锁顺序、版本条件、执行代次、受影响行数检查及数据库时间函数。

复杂投影使用内部 `SqlRow` 和 MyBatis `SqlRowTypeHandler`，保留 NULL、列顺序及驱动的时间转换行为。该类型处理器由 MyBatis 调用，不独立执行数据库 SQL。MyBatis 一级缓存设为 `STATEMENT`，事务中重复读取锁定状态仍访问数据库。

启动版本检查保留 JDBC 元数据接口，用于建立 Mapper 之前确认 MySQL 版本；模式迁移继续交给 Flyway。验收脚本中的直接 SQL 用于准备、核对和清理合成测试夹具，已移至 `scripts/validation/ValidationSql.java`，生产 `SqlSupport` 不再暴露 JDBC。

## 历史改造与验证

迁移过程、同步修复和 2026-10-05 回归结论已归档至 [数据库访问迁移与回归记录](temp/数据库访问迁移与回归记录-2026-10-05.md)。后续测试和验收记录统一维护在 [temp/](temp/README.md)。
