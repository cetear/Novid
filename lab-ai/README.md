# AI 模块目录与维护说明

本模块按“执行机制、业务工作流、共享能力”组织 Java 包，测试目录与对应职责一致。目录整理不改变现有任务策略、审批、费用、模型调用及恢复规则。

```text
com.example.ailab.ai/
├─ orchestration/               执行机制，可由不同业务工作流组合使用
│  ├─ react/                    受限动作与工具反馈循环
│  ├─ planexecute/              模型计划的角色登记、Schema 和校验
│  ├─ fixed/                    程序定义的固定流程
│  ├─ multiagent/               多角色依赖调度与结果汇合
│  └─ support/                  编排共用的 Worker 日志上下文
├─ workflows/                   业务资料、角色输入、检查点和生命周期
│  ├─ report/                   FAQ／研究报告工作流
│  └─ media/                    PPT／视频工作流、预览与本地执行
├─ model/                       Chat／Embedding 网关、模型路由、结构化输出
├─ media/                       外部图片／视频协议及能力配置
├─ tools/                       工具登记、权限、参数校验和调用
│  └─ mcp/                     MCP 工具接入
├─ skills/                      Skill 读取、快照及角色指令
├─ runtime/                     各模式共用的执行预算与追踪上下文
├─ fees/                        模型费用预留、结算协调及报价配置
├─ rag/                         结构解析、异步入库、索引清理
├─ memory/                      会话历史与个人记忆
├─ aggregator/                  结果汇聚与交付检查
├─ gateway/                     在线助手入口
└─ config/                      Spring 装配
```

## 现有代码所在位置

| 职责 | 类与方法 | 当前位置 |
|---|---|---|
| ReAct 风格工具循环 | `BoundedToolLoop.run()` | `orchestration/react/BoundedToolLoop.java` |
| 计划协议与校验 | `AgentRegistry`、`PlanSchema`、`PlanValidator` | `orchestration/planexecute/` |
| 固定报告步骤 | `FixedReportPlan.steps()` | `orchestration/fixed/FixedReportPlan.java` |
| 多角色协作 | `AgentDagExecutor.submit()`、`await()` | `orchestration/multiagent/AgentDagExecutor.java` |
| 报告任务协调 | `ReportTaskWorker` | `workflows/report/ReportTaskWorker.java` |
| PPT／视频协调 | `MediaTaskWorker` | `workflows/media/MediaTaskWorker.java` |
| 审批后媒体执行 | `MediaExecution`、`PresentationExecution` | `workflows/media/` |
| PPT 内容／计划协议 | `MediaSchemas`、`MediaResultInput` | `workflows/media/` |
| Skill 绑定 | `SkillCatalog` | `skills/SkillCatalog.java` |
| 执行预算 | `ExecutionBudget` | `runtime/ExecutionBudget.java` |

`AgentDagExecutor` 只调度已登记步骤，继续使用工作流拥有的有界线程池、共同截止和角色结果检查点；它不生成计划、不创建新模型会话，也不拥有线程池生命周期。`multiagent` 是协作机制，能与固定流程或计划执行组合，不作为互斥的执行策略。

`FixedReportPlan` 从原报告 Worker 提取既有固定步骤，保持原有稳定 ID 和依赖关系；复用现有 `PlanValidator.Step` 校验结构，不增加模型调用。

## 当前实现与后续扩展

当前报告保留原 `FIXED`／`PLANNED` 分支；PPT 保留模型规划、角色协作及研究环节的工具循环。目录中存在某种模式的代码，不表示已经实现工作流路由器或完整、可恢复的 PPT ReAct 执行器。本次整理没有移除 PPT Planner，也没有改变外部 `TaskRequest.strategy`。

新增工作流时，业务输入、角色指令和状态迁移放入 `workflows/<业务名>`；动作循环、计划调度等可复用机制放入对应 `orchestration` 子包。模型、工具、Skill、预算和费用保持共用，不能为每种模式复制一份。

未来的执行策略路由入口应独立登记工作流版本、执行模式和执行器版本，再调用对应机制；它不属于某一个具体模式。可靠 ReAct 仍需增加动作级运行记录、PPT 受控动作及旧任务兼容，方案见项目文档 `docs/Agent工作流执行架构评估.md`。

## 维护约定

- `orchestration` 下的执行机制和共用能力不反向引用 `workflows` 中的业务实现，工作流通过输入和回调提供业务行为。
- Java 文件路径与 `package` 声明一致；测试按同样职责分类，保留同包协议测试所需的访问范围。
- 跨目录测试共用的合成样本放入测试树的 `fixtures`，测试类之间不通过包内辅助方法耦合。
- 变更包名时同步修改 Spring 装配、跨模块引用、测试及 `scripts/validation` 的独立探针；现有 Bean 名、配置键与 HTTP 协议不因目录整理而变化。
- 旧数据库迁移、已有审批和持久化 DTO 不随包结构搬迁；历史验收记录保留原含义。
- 清理旧编译输出后验证完整模块，避免旧包残留的 `.class` 导致重复 Spring Bean 或重复执行测试。

## 本地验证

目录迁移后的基础回归使用 `mvn -B -Dlab.tools.mcp.enabled=false -Dlab.media.worker-enabled=false clean verify`，临时关闭装配测试中的外部 MCP 发现及媒体后台任务。直接构造协议替身的 MCP／媒体测试仍执行；真实服务专项需要单独显式开启。Windows 沙箱中若 Mockito 动态附加受限，按验收报告使用启动代理，勿修改正式配置规避测试环境限制。
