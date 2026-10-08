# AI 模块目录与维护说明

## 固定学习工作流（2026-10-09）

普通任务队列由 `ReportTaskWorker` 领取，按类型分派到 `FixedLearningWorker.run()`。Java 在首次模型调用前绑定架构、Skill、授权原文区间、来源版本／处理代次与预算；通用 `FixedWorkflowExecutor` 负责节点输入摘要、结果校验与复用，业务固定步骤在 `QuizWorkflow`、`KnowledgeCompilationWorkflow` 中定义。两者不进入旧 `FixedReportPlan`／Planner 或 DAG 调度器，顺序执行，无运行时自动角色发现。

共用六阶段：准备 → 分批提取 → 组织 → 生成 → 质检 → 发布。自测组织阶段生成固定题目 ID 和知识点映射，生成答案及解析；整编生成目录与 `MERGE / COMPLEMENT / CONFLICT` 分组，每个提取条目恰好分配一组，再逐章生成。质检使用完整原文、条目和生成结果；自测附加本地重复题／选项检查。最多一次受影响题目或章节局部重写后重新质检，不通过则失败，不发布降级结果。

`LearningSourceReader` 完整读取 1～6 文档，最多 24,000 UTF-8 字节、12 批、64 个提取条目，原文区间为 UTF-16 左闭右开。超限／空白资料在模型调用前拒绝；模型容量不足同样失败，不静默截断。条目引文必须是对应批次原文子串；整编程序检查条目分配完整，但不能机械保证提取覆盖所有知识，教学准确性仍需人工确认。

`FixedWorkflowRepository` 在任务租约锁下保存不可变基线与节点、幂等唯一返工额度，发布事务同时核验全部原文读取节点、来源和结果检查点。完成批次／章节恢复时直接复用；待完成模型调用可能再次执行，恢复不重置持久额度。查询结构化结果和下载 Markdown 都重新验证本人权限、原文版本、处理代次及摘要。

V20 增加两张固定流程检查点表和任务额度列。自测预算 24 轮／36 尝试／32 工具，整编 32／48／32 工具，截止均为整任务 20 分钟。结果接口为 `GET /api/v1/tasks/{id}/result`，产物沿用私人下载；`qualityStatus` 为模型质检通过但人工待验，`fullSourceRead` 仅表明完整读取。旧 TaskRequest JSON／幂等摘要与 PPT 观察摘要排除新增空选项，避免影响历史任务恢复。

契约在 `lab-contract/dto/Learning.java` 和 `FixedWorkflowStorePort`；创建参数在 `TaskApplicationService` 校验，HTTP 在 `TaskController`；V20、Mapper 及持久化在 `lab-data`。首版不含评分／错题本或 Word／PDF 导出。实际接口见 [联调说明](../docs/前端接口与联调说明.md)。

本模块按“执行机制、业务工作流、共享能力”组织 Java 包，测试目录与对应职责一致。PPT 内容制作按服务端登记的 ReAct 架构执行，审批、生图、导出沿用原媒体链路；学习自测和资料整编按固定 Graph/Workflow + Skill 执行。

```text
com.example.ailab.ai/
├─ orchestration/               执行机制，可由不同业务工作流组合使用
│  ├─ WorkflowRouter.java       工作流固定绑定架构与执行器版本
│  ├─ react/                    受限动作与工具反馈循环
│  ├─ planexecute/              模型计划的角色登记、Schema 和校验
│  ├─ fixed/                    程序定义的固定流程
│  ├─ multiagent/               多角色依赖调度与结果汇合
│  └─ support/                  编排共用的 Worker 日志上下文
├─ workflows/                   业务资料、角色输入、检查点和生命周期
│  ├─ report/                   旧 FAQ／研究报告与普通任务队列分派
│  ├─ quiz/                     学习自测蓝图、题目、质检及局部修复
│  ├─ compilation/              分类目录、章节融合及局部修复
│  ├─ support/                  固定学习 Worker、原文、协议和 Markdown
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
| 工作流架构登记 | `WorkflowRouter.route()`、`verify()` | `orchestration/WorkflowRouter.java` |
| 受控动作循环 | `ReActExecutor.run()`、`ReActActionSchema` | `orchestration/react/` |
| PPT ReAct 内容制作 | `PptReActWorkflow.run()` | `workflows/media/PptReActWorkflow.java` |
| 计划协议与校验 | `AgentRegistry`、`PlanSchema`、`PlanValidator` | `orchestration/planexecute/` |
| 固定节点执行与复用 | `FixedWorkflowExecutor.node()` | `orchestration/fixed/FixedWorkflowExecutor.java` |
| 学习任务协调 | `FixedLearningWorker.run()`、`LearningSession.extract()`、`publish()` | `workflows/support/` |
| 学习自测 | `QuizWorkflow.run()` | `workflows/quiz/QuizWorkflow.java` |
| 资料整编 | `KnowledgeCompilationWorkflow.run()` | `workflows/compilation/KnowledgeCompilationWorkflow.java` |
| 学习结构化协议与渲染 | `LearningSchemas`、`LearningRenderer`、`RecordSchema` | `workflows/support/`、`model/` |
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

`NOTES_PPT` 新任务固定绑定 `notes-ppt@1 / REACT / ppt-react-v1`。绑定及 Skill 快照在资料摘要的首次模型调用前保存；恢复只验证已保存版本，不按最新配置重新分配架构。另登记 `QUIZ_GENERATION → learning-quiz@1 / FIXED / quiz-fixed-v1`、`KNOWLEDGE_COMPILATION → knowledge-compilation@1 / FIXED / compilation-fixed-v1`。其余架构保留枚举及原目录，视频与旧 FAQ／研究报告沿原实现执行。

PPT 控制器每轮读取当前观察，结构化选择 `research/content/layout/visual/review/repair/finish` 中的当前可用动作，再由共用角色实现执行。程序强制内容、布局及最新质检齐全，必要事实图研究完成后才允许质检；本地版式预检可以把模型自报 ACCEPT 改为 REPAIR。最多 12 个动作、一次语义返工，继续共享原 24 逻辑轮、36 尝试及整任务截止，不重新分配额度。

`repair` 按问题步骤使旧观察及下游失效，未受影响的观察保留。新 PPT 不调用初始 Planner 或返工 Planner，不写 `media_plans`；只声明内存中的角色结果协议，实际次序保存在动作日志。旧 `GET /api/v1/tasks/{id}/media-plans` 查询对新 ReAct 任务返回空，不伪造模型计划。预览的旧字段 `planVersion` 对新 PPT 表示初版／返工内容版本（1／2）；请求仍兼容 `strategy=PLANNED`，这个旧字段不选择执行架构。

V19 新增工作流绑定与动作表，并以新增任务标记隔离历史任务。V19 前创建的 PPT 保留原 Planner 及检查点；新任务若已存在旧 Skill／计划事实也固定 legacy 执行器。原批准、生图及导出路径继续复用原操作，不由 ReAct 控制器发起支付。

动作决策先保存为 PENDING，再执行并保存 COMPLETED 观察。恢复重放已完成观察，未完成动作沿原决策继续，不重复调用控制器。完成记录提交前中断的模型请求或只读工具可能再次执行，仍消费原持久预算；不承诺远程模型调用恰好一次。数据仓库 `WorkflowRunRepository` 核验租约、fencing、连续序号和摘要，返工额度与 repair 决策同事务消费。

新增工作流时，业务输入、角色指令和状态迁移放入 `workflows/<业务名>`；动作循环、计划调度等可复用机制放入对应 `orchestration` 子包。模型、工具、Skill、预算和费用保持共用，不能为每种模式复制一份。

新增架构时先增加真实执行器，再在 `WorkflowRouter` 登记工作流版本；保留旧执行器版本以恢复历史任务。不要只新增枚举就声称该架构可执行。原始设计评估见 `docs/temp/Agent工作流执行架构评估.md`。

## 维护约定

- `orchestration` 下的执行机制和共用能力不反向引用 `workflows` 中的业务实现，工作流通过输入和回调提供业务行为。
- Java 文件路径与 `package` 声明一致；测试按同样职责分类，保留同包协议测试所需的访问范围。
- 跨目录测试共用的合成样本放入测试树的 `fixtures`，测试类之间不通过包内辅助方法耦合。
- 变更包名时同步修改 Spring 装配、跨模块引用、测试及 `scripts/validation` 的独立探针；现有 Bean 名、配置键与 HTTP 协议不因目录整理而变化。
- 旧数据库迁移、已有审批和持久化 DTO 不随包结构搬迁；历史验收记录保留原含义。
- 清理旧编译输出后验证完整模块，避免旧包残留的 `.class` 导致重复 Spring Bean 或重复执行测试。

## 本地验证

目录迁移后的基础回归使用 `mvn -B -Dlab.tools.mcp.enabled=false -Dlab.media.worker-enabled=false clean verify`，临时关闭装配测试中的外部 MCP 发现及媒体后台任务。直接构造协议替身的 MCP／媒体测试仍执行；真实服务专项需要单独显式开启。Windows 沙箱中若 Mockito 动态附加受限，按验收报告使用启动代理，勿修改正式配置规避测试环境限制。
